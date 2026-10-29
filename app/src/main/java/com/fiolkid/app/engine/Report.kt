package com.fiolkid.app.engine

import android.content.Context
import android.os.Build
import com.fiolkid.app.BuildConfig
import com.fiolkid.app.Power
import com.fiolkid.app.Service
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Отчёт для «Скопировать отчёт» и «Отправить»: версия, телефон, состояние, замеры способов и журнал —
 * как в версии для компьютера. Ключей и ссылок в нём нет (их в программе пока и нет).
 */
object Report {
    fun build(context: Context): String {
        val state = Engine.state.value
        val options = Options.state.value
        val traffic = TrafficMeter.traffic.value
        val now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        return buildString {
            appendLine("FiolKid для Android ${BuildConfig.VERSION_NAME}, $now, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine(
                "Включено: ${if (state.power == Power.Off) "нет" else "да"}; способ обхода: ${Engine.current.value ?: "—"}" +
                    " (${modeName(options.mode)})",
            )
            Service.entries.forEach { appendLine("${it.name}: ${state.looks[it]}") }
            val tg = TelegramProxy.state.value
            appendLine(
                "Прокси Telegram: ${if (tg.running) "работает" else "выключен"}; подключений сейчас ${tg.active}, " +
                    "всего ${tg.total}; в Telegram ${if (tg.connectedOnce) "добавлен" else "не добавлен"}",
            )
            TelegramProxy.routesReport().forEach { appendLine("  $it") }
            appendLine("Через обход: ${formatBytes(traffic.session)} за это включение, ${formatBytes(traffic.total)} за всё время")
            appendLine("Новогоднее оформление: ${yes(options.newYear)}; запуск вместе с телефоном: ${yes(options.autostart)}")
            val results = Engine.results.value
            if (results.isNotEmpty()) {
                appendLine("--- замер способов ---")
                results.entries.sortedBy { it.value.seconds }.forEach { (name, r) -> appendLine("$name: ${r.text}") }
            }
            appendLine("--- журнал ---")
            Engine.log.value.takeLast(300).forEach(::appendLine)
        }
    }

    fun modeName(mode: StrategyMode) = when (mode) {
        StrategyMode.First -> "первый рабочий"
        StrategyMode.Fastest -> "самый быстрый"
        StrategyMode.Manual -> "выбран вручную"
    }

    private fun yes(value: Boolean) = if (value) "да" else "нет"
}
