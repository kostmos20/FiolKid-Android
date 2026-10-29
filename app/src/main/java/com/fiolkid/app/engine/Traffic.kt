package com.fiolkid.app.engine

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/** Сколько трафика прошло через обход: за это включение и за всё время (хранится между запусками). */
data class Traffic(val session: Long = 0, val total: Long = 0)

object TrafficMeter {
    private const val PREFS = "traffic"
    private const val KEY_TOTAL = "total"

    private val _traffic = MutableStateFlow(Traffic())
    val traffic: StateFlow<Traffic> = _traffic.asStateFlow()

    private var base = 0L

    fun load(context: Context) {
        val total = prefs(context).getLong(KEY_TOTAL, 0)
        _traffic.value = Traffic(0, total)
    }

    /** Начало включения: счётчик hev-socks5-tunnel начинается с нуля. */
    fun begin(context: Context) {
        base = prefs(context).getLong(KEY_TOTAL, 0)
        _traffic.value = Traffic(0, base)
    }

    /** Счётчики hev-socks5-tunnel: [пакеты туда, байты туда, пакеты обратно, байты обратно]. */
    fun update(context: Context, stats: LongArray) {
        if (stats.size < 4) return
        val session = stats[1] + stats[3]
        val total = base + session
        _traffic.value = Traffic(session, total)
        prefs(context).edit().putLong(KEY_TOTAL, total).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** «830 КБ», «152 МБ», «1,2 ГБ». */
fun formatBytes(bytes: Long): String {
    val ru = Locale.forLanguageTag("ru")
    return when {
        bytes < 1024L * 1024 -> "${bytes / 1024} КБ"
        bytes < 1024L * 1024 * 1024 -> "${bytes / (1024 * 1024)} МБ"
        else -> String.format(ru, "%.1f ГБ", bytes / (1024.0 * 1024 * 1024))
    }
}
