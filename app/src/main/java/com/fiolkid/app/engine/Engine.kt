package com.fiolkid.app.engine

import android.content.Context
import com.fiolkid.app.HomeState
import com.fiolkid.app.Look
import com.fiolkid.app.Power
import com.fiolkid.app.Service
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.InetSocketAddress
import java.net.Socket
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.concurrent.thread

/**
 * Общее состояние для экрана и VPN-службы (они в одном процессе): что показывать, журнал и запуск byedpi.
 */
object Engine {
    const val PORT = 10808
    private const val PREFS = "engine"
    private const val KEY_STRATEGY = "strategy"

    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private var dpi: Thread? = null

    fun show(state: HomeState) {
        _state.value = state
    }

    fun note(message: String) {
        val line = "${LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))}  $message"
        _log.value = (_log.value + line).takeLast(500)
    }

    /**
     * Подбирает способ обхода: сначала тот, что сработал в прошлый раз, затем остальные по порядку. Возвращает
     * первый, с которым открылись и YouTube, и Discord; если такого нет — тот, с которым открылось больше всего.
     * byedpi остаётся запущенным с выбранным способом.
     */
    fun pickStrategy(context: Context): Pair<Strategy, Map<Service, Look>>? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_STRATEGY, null)
        val order = Strategies.all.sortedBy { if (it.name == saved) 0 else 1 }

        var best: Pair<Strategy, Map<Service, Look>>? = null
        for (strategy in order) {
            if (!startDpi(strategy)) {
                note("Способ «${strategy.name}» не запустился")
                continue
            }
            val looks = Checks.services.associateWith { service ->
                val problems = Checks.run(service, PORT)
                problems.forEach { note("Не прошло: ${service.name}: $it") }
                if (problems.isEmpty()) Look.Working else Look.NotWorking
            }
            val passed = looks.values.count { it == Look.Working }
            note("Способ «${strategy.name}»: открылось $passed из ${looks.size}")
            if (passed == looks.size) {
                prefs.edit().putString(KEY_STRATEGY, strategy.name).apply()
                return strategy to looks
            }
            if (best == null || passed > best.second.values.count { it == Look.Working })
                best = strategy to looks
            stopDpi()
        }
        // Ни один не открыл всё — берём лучший из попробованных
        val (strategy, looks) = best ?: return null
        if (!startDpi(strategy)) return null
        return strategy to looks
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
    }

    fun connecting() = show(HomeState(Power.Connecting, HomeState.looks(Look.Checking, Look.Checking)))

    fun on(looks: Map<Service, Look>) =
        show(HomeState(Power.On, HomeState.looks(looks.getValue(Service.YouTube), looks.getValue(Service.Discord))))

    fun off() = show(HomeState())
}
