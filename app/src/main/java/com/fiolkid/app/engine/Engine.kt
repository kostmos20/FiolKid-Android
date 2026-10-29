package com.fiolkid.app.engine

import android.content.Context
import com.fiolkid.app.HomeState
import com.fiolkid.app.Look
import com.fiolkid.app.Power
import com.fiolkid.app.Service
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.concurrent.thread

/** Последний замер способа: сколько адресов открылось и за сколько секунд. */
data class StrategyResult(val passed: Int, val total: Int, val seconds: Double) {
    val text get() = String.format(Locale.forLanguageTag("ru"), "%d из %d, %.1f с", passed, total, seconds)
}

/**
 * Общее состояние для экрана и VPN-службы (они в одном процессе): что показывать, журнал, замеры способов
 * и запуск byedpi.
 */
object Engine {
    const val PORT = 10808
    private const val PREFS = "engine"
    private const val KEY_STRATEGY = "strategy"
    private const val MAX_LOG = 500

    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    /** Замеры способов по названию. */
    private val _results = MutableStateFlow<Map<String, StrategyResult>>(emptyMap())
    val results: StateFlow<Map<String, StrategyResult>> = _results.asStateFlow()

    /** Каким способом работаем сейчас (null — выключено). */
    private val _current = MutableStateFlow<String?>(null)
    val current: StateFlow<String?> = _current.asStateFlow()

    private val _lastCheck = MutableStateFlow<LocalDateTime?>(null)
    val lastCheck: StateFlow<LocalDateTime?> = _lastCheck.asStateFlow()

    private var dpi: Thread? = null
    /** С каким способом byedpi запущен сейчас. */
    private var running: String? = null
    private var logFile: File? = null
    private lateinit var prefs: android.content.SharedPreferences

    /** Журнал хранится в файле — после перезапуска и падения он на месте, его видно в отчёте. */
    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _results.value = prefs.getStringSet("results", emptySet()).orEmpty().mapNotNull(::parseResult).toMap()
        val file = File(context.filesDir, "log.txt")
        logFile = file
        if (file.exists())
            _log.value = file.readLines().takeLast(MAX_LOG)
        note("Программа запущена ${LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))}")
    }

    fun show(state: HomeState) {
        _state.value = state
    }

    @Synchronized
    fun note(message: String) {
        val line = "${LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))}  $message"
        _log.value = (_log.value + line).takeLast(MAX_LOG)
        val file = logFile ?: return
        runCatching {
            file.appendText(line + "\n")
            // Файл не растёт бесконечно: держим последние строки
            if (file.length() > 256 * 1024) file.writeText(_log.value.joinToString("\n", postfix = "\n"))
        }
    }

    /** Проверка через текущий byedpi: итог в журнал, время — в «последнюю проверку». */
    fun check(): CheckResult {
        val result = Checks.run(PORT)
        result.problems.forEach { (service, problems) -> problems.forEach { note("Не прошло: ${service.name}: $it") } }
        _lastCheck.value = LocalDateTime.now()
        return result
    }

    private fun measure(strategy: Strategy): CheckResult? {
        if (!startDpi(strategy)) {
            note("Способ «${strategy.name}» не запустился")
            return null
        }
        val result = Checks.run(PORT)
        _lastCheck.value = LocalDateTime.now()
        remember(strategy, StrategyResult(result.passed, result.total, result.seconds))
        note("Способ «${strategy.name}»: открылось ${result.passed} из ${result.total}, ${"%.1f".format(Locale.forLanguageTag("ru"), result.seconds)} с")
        return result
    }

    /**
     * Подбирает способ обхода по выбранному режиму и оставляет byedpi запущенным с ним.
     * Первый рабочий — сначала тот, что сработал в прошлый раз, затем остальные по порядку; самый быстрый —
     * замер всех; вручную — только выбранный. Если всё открыть не удалось — берём тот, с которым открылось больше.
     */
    fun pickStrategy(): Pair<Strategy, CheckResult>? {
        val options = Options.state.value
        if (options.mode == StrategyMode.Manual) {
            val chosen = Strategies.all.firstOrNull { it.name == options.manual } ?: Strategies.all.first()
            val result = measure(chosen) ?: return null
            _current.value = chosen.name
            return chosen to result
        }

        val saved = prefs.getString(KEY_STRATEGY, null)
        val order = Strategies.all.sortedBy { if (it.name == saved) 0 else 1 }
        if (options.mode == StrategyMode.Fastest) note("Подбираю самый быстрый способ (${order.size} шт.)")
        val tried = mutableListOf<Pair<Strategy, CheckResult>>()
        for (strategy in order) {
            val result = measure(strategy) ?: continue
            tried += strategy to result
            if (result.allPassed && options.mode == StrategyMode.First) break
        }
        val best = tried.filter { it.second.allPassed }.minByOrNull { it.second.seconds }
            ?: tried.maxByOrNull { it.second.passed }
            ?: return null
        if (best.second.allPassed) prefs.edit().putString(KEY_STRATEGY, best.first.name).apply()
        if (options.mode == StrategyMode.Fastest)
            note("Самый быстрый способ: «${best.first.name}» (${"%.1f".format(Locale.forLanguageTag("ru"), best.second.seconds)} с)")
        if (running != best.first.name && !startDpi(best.first)) return null
        _current.value = best.first.name
        return best
    }

    /** Замер всех способов (как «Замерить скорость альтов»). byedpi после него — с лучшим способом. */
    fun benchmark(onProgress: (Int, Int) -> Unit): Pair<Strategy, CheckResult>? {
        val all = Strategies.all
        note("Замеряю скорость способов (${all.size} шт.)")
        val started = System.nanoTime()
        val tried = all.mapIndexedNotNull { index, strategy ->
            onProgress(index + 1, all.size)
            measure(strategy)?.let { strategy to it }
        }
        note("Замер занял ${((System.nanoTime() - started) / 1e9).toInt()} с")
        val best = tried.filter { it.second.allPassed }.minByOrNull { it.second.seconds }
            ?: tried.maxByOrNull { it.second.passed }
            ?: return null
        val manual = Options.state.value.let { o -> if (o.mode == StrategyMode.Manual) all.firstOrNull { it.name == o.manual } else null }
        val chosen = manual?.let { m -> tried.firstOrNull { it.first == m } } ?: best
        if (!startDpi(chosen.first)) return null
        _current.value = chosen.first.name
        return chosen
    }

    /** Переключиться на выбранный вручную способ, не выключая VPN. */
    fun apply(strategy: Strategy): CheckResult? {
        val result = measure(strategy) ?: return null
        _current.value = strategy.name
        note("Работаю со способом «${strategy.name}»")
        return result
    }

    private fun remember(strategy: Strategy, result: StrategyResult) {
        _results.value = _results.value + (strategy.name to result)
        prefs.edit().putStringSet(
            "results",
            _results.value.map { (name, r) -> "$name|${r.passed}|${r.total}|${r.seconds}" }.toSet(),
        ).apply()
    }

    private fun parseResult(line: String): Pair<String, StrategyResult>? {
        val parts = line.split('|')
        if (parts.size != 4) return null
        val (name, passed, total, seconds) = parts
        return name to StrategyResult(passed.toIntOrNull() ?: return null, total.toIntOrNull() ?: return null, seconds.toDoubleOrNull() ?: return null)
    }

    /** Запускает byedpi в своём потоке и ждёт, пока он начнёт принимать подключения. */
    fun startDpi(strategy: Strategy): Boolean {
        stopDpi()
        val args = Strategies.args(strategy, PORT)
        val worker = thread(name = "byedpi") {
            val code = ByeDpi.run(args)
            if (code != 0) note("byedpi завершился с кодом $code")
        }
        dpi = worker
        running = strategy.name
        repeat(30) {
            if (!worker.isAlive) return false
            try {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", PORT), 200) }
                return true
            } catch (_: Exception) {
                Thread.sleep(100)
            }
        }
        return false
    }

    fun stopDpi() {
        val worker = dpi ?: return
        ByeDpi.stop()
        worker.join(3000)
        dpi = null
        running = null
    }

    fun connecting() = show(HomeState(Power.Connecting, HomeState.looks(Look.Checking, Look.Checking)))

    fun on(looks: Map<Service, Look>) =
        show(HomeState(Power.On, HomeState.looks(looks.getValue(Service.YouTube), looks.getValue(Service.Discord))))

    /** Пока идёт замер — заголовок на главной, как на компьютере. */
    fun measuring(number: Int, count: Int) = show(_state.value.copy(measuring = number to count))

    fun off() {
        _current.value = null
        show(HomeState())
    }
}
