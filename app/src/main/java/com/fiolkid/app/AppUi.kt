package com.fiolkid.app

import com.fiolkid.app.engine.OptionsState
import com.fiolkid.app.engine.StrategyMode
import com.fiolkid.app.engine.StrategyResult
import com.fiolkid.app.engine.Traffic
import com.fiolkid.app.engine.UpdateState
import java.time.LocalDateTime

/** Всё, что экрану нужно знать, кроме главного состояния: журнал, настройки, замеры, обновления. */
data class AppData(
    val log: List<String> = emptyList(),
    val update: UpdateState = UpdateState(),
    val options: OptionsState = OptionsState(),
    val results: Map<String, StrategyResult> = emptyMap(),
    val current: String? = null,
    val traffic: Traffic = Traffic(),
    val lastCheck: LocalDateTime? = null,
    /** Всплывающая подсказка снизу («Отчёт скопирован» и т. п.). */
    val toast: String? = null,
)

/** Что делать по кнопкам. По умолчанию — ничего (для снимков экрана). */
class AppActions(
    val check: () -> Unit = {},
    val copyReport: () -> Unit = {},
    val shareReport: () -> Unit = {},
    val setMode: (StrategyMode) -> Unit = {},
    val pickStrategy: (String) -> Unit = {},
    val benchmark: () -> Unit = {},
    val setNewYear: (Boolean) -> Unit = {},
    val setAutostart: (Boolean) -> Unit = {},
    val checkUpdate: () -> Unit = {},
    val installUpdate: () -> Unit = {},
)
