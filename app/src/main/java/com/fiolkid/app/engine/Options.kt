package com.fiolkid.app.engine

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Как выбирать способ обхода — те же три режима, что у альтов в версии для компьютера. */
enum class StrategyMode(val hint: String) {
    First("Пробую способы по очереди и беру первый, с которым открылись YouTube и Discord. Удачный запоминаю."),
    Fastest("Замеряю все способы и беру самый быстрый из тех, с которыми всё открылось. Включение дольше."),
    Manual("Работает только выбранный способ. Выбери его в списке ниже."),
}

/** Настройки программы: хранятся между запусками, экран следит за ними. */
data class OptionsState(
    val mode: StrategyMode = StrategyMode.First,
    val manual: String? = null,
    val newYear: Boolean = true,
    val autostart: Boolean = false,
)

object Options {
    private const val PREFS = "options"

    private lateinit var prefs: SharedPreferences
    private val _state = MutableStateFlow(OptionsState())
    val state: StateFlow<OptionsState> = _state.asStateFlow()

    fun load(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _state.value = OptionsState(
            mode = runCatching { StrategyMode.valueOf(prefs.getString("mode", null) ?: "") }.getOrDefault(StrategyMode.First),
            manual = prefs.getString("manual", null),
            newYear = prefs.getBoolean("newYear", true),
            autostart = prefs.getBoolean("autostart", false),
        )
    }

    fun update(change: (OptionsState) -> OptionsState) {
        val value = change(_state.value)
        _state.value = value
        prefs.edit()
            .putString("mode", value.mode.name)
            .putString("manual", value.manual)
            .putBoolean("newYear", value.newYear)
            .putBoolean("autostart", value.autostart)
            .apply()
    }
}
