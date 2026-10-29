package com.fiolkid.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fiolkid.app.engine.Strategies
import com.fiolkid.app.engine.StrategyMode
import kotlinx.coroutines.launch

/**
 * «Настройки» в стиле версии для компьютера: заголовок, быстрые переходы и панели с номерами. На телефоне
 * пока: способ обхода, оформление и запуск, журнал и отчёты, обновления; ключ, подписка и Telegram — скоро.
 */
@Composable
fun SettingsPage(state: HomeState, version: String, data: AppData, actions: AppActions) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val positions = remember { mutableStateMapOf<String, Int>() }
    fun Modifier.anchor(name: String) = onGloballyPositioned { positions[name] = it.positionInParent().y.toInt() }
    fun jump(name: String) = scope.launch { positions[name]?.let { scroll.animateScrollTo((it - 20).coerceAtLeast(0)) } }

    Column(
        Modifier.fillMaxSize().verticalScroll(scroll)
            .padding(start = 24.u, end = 24.u, top = 52.u, bottom = 20.u),
    ) {
        PageHeader("ТВОЙ FIOLKID", "Настройки", "Обход, поведение и немного зимы.\nВсё нужное — в одном месте.")

        // Быстрый переход к разделу
        Row(Modifier.padding(bottom = 14.u), horizontalArrangement = Arrangement.spacedBy(6.u)) {
            listOf("strategy" to "Способ обхода", "look" to "Оформление", "about" to "О программе").forEach { (name, label) ->
                val shape = RoundedCornerShape(20.u)
                Box(
                    Modifier.background(Color(0x6939373D), shape).border(1.dp, Color(0x24B5A386), shape)
                        .then(Tap({ jump(name) })).padding(horizontal = 11.u, vertical = 7.u),
                ) { BasicText(label, style = TextStyle(color = Color(0xFFC6BEB2), fontSize = 11.us)) }
            }
        }

        Box(Modifier.anchor("strategy")) { StrategyPanel(state, data, actions) }
        TelegramPanel(state, data, actions)
        Box(Modifier.anchor("look")) { LookPanel(data, actions) }
        ReportPanel(actions)
        Box(Modifier.anchor("about")) { UpdatesPanel(version, data, actions) }
        SoonPanel()
    }
}

@Composable
private fun StrategyPanel(state: HomeState, data: AppData, actions: AppActions) {
    val mode = data.options.mode
    Card {
        PanelHeader(Icons.Refresh, "Способ обхода", "YouTube и Discord · byedpi", "01", bottom = 17.u)
        Segments(
            listOf(StrategyMode.First to "Первый\nрабочий", StrategyMode.Fastest to "Самый\nбыстрый", StrategyMode.Manual to "Выбрать\nвручную"),
            mode, actions.setMode,
        )
        Hint(mode.hint)
        if (data.current != null) Hint("Сейчас работает: «${data.current}»")

        Box(Modifier.padding(top = 14.u, bottom = 14.u).fillMaxWidth().height(1.dp).background(Palette.Divider))
        Disclosure(
            header = {
                BasicText("Все способы", style = TextStyle(color = Color(0xFFB9BBC7), fontSize = 12.us))
                Box(
                    Modifier.padding(start = 6.u).background(Color(0x08FFFFFF), RoundedCornerShape(5.u))
                        .padding(horizontal = 6.u, vertical = 1.u),
                ) { BasicText("${Strategies.all.size}", style = TextStyle(color = Color(0xFFB9BBC7), fontSize = 10.us)) }
            },
            startOpen = mode == StrategyMode.Manual,
        ) {
            Column(Modifier.padding(top = 4.u)) {
                Strategies.all.forEach { strategy ->
                    val result = data.results[strategy.name]
                    val selected = if (mode == StrategyMode.Manual) strategy.name == (data.options.manual ?: Strategies.all.first().name)
                    else strategy.name == data.current
                    ChoiceRow(
                        title = strategy.name,
                        note = if (strategy.name == data.current) "работает сейчас" else null,
                        right = result?.text ?: "не замерен",
                        rightColor = when {
                            result == null -> Color(0xFF9CA5B3)
                            result.passed == result.total -> Palette.GreenText
                            result.passed == 0 -> Palette.RedText
                            else -> Palette.AmberText
                        },
                        selected = selected,
                        onClick = { actions.pickStrategy(strategy.name) },
                    )
                }
            }
        }
        val measuring = state.measuring
        FiolButton(
            if (measuring != null) "Замеряю… ${measuring.first} из ${measuring.second}" else "Замерить скорость способов",
            Modifier.padding(top = 14.u), Icons.Refresh, enabled = measuring == null && state.power != Power.Connecting,
            onClick = actions.benchmark,
        )
        Caption("Пару минут YouTube и Discord могут подтормаживать")
    }
}

/** Telegram — как панель 05 на компьютере: состояние прокси, «Подключить Telegram» и перезапуск. */
@Composable
private fun TelegramPanel(state: HomeState, data: AppData, actions: AppActions) {
    val tg = data.telegram
    Card {
        PanelHeader(Icons.Telegram, "Telegram", "Локальный прокси для мессенджера", "02", iconColor = Color(0xFF94C5DF), iconBack = Color(0x107299BC))
        val look = state.looks.getValue(Service.Telegram)
        val (text, dot, color) = when {
            !tg.running -> Triple("Прокси заработает, когда включишь FiolKid", Palette.Gray, Palette.Muted)
            look == Look.NotWorking -> Triple("Прокси не достаёт до Telegram — попробуй перезапустить", Palette.Red, Palette.RedText)
            !tg.connectedOnce -> Triple("Прокси работает — осталось добавить его в Telegram", Palette.Amber, Palette.AmberText)
            else -> Triple("Работает: подключений сейчас ${tg.active}", Palette.Green, Palette.GreenText)
        }
        StateLine(text, dot, color, Modifier.padding(top = 10.u))
        Row(Modifier.padding(top = 14.u), horizontalArrangement = Arrangement.spacedBy(9.u)) {
            FiolButton("Подключить Telegram", gold = true, onClick = actions.connectTelegram)
            FiolButton("", icon = Icons.Refresh, enabled = tg.running, onClick = actions.restartTelegram)
        }
        Caption(
            "«Подключить» открывает Telegram, и он предложит добавить прокси — нажми там «Подключить». " +
                "Прокси работает, пока включён FiolKid. Кнопка со стрелкой перезапускает прокси.",
        )
    }
}

@Composable
private fun LookPanel(data: AppData, actions: AppActions) {
    Card {
        PanelHeader(Icons.Snowflake, "Оформление и запуск", "Пусть будет удобно и уютно", "03", bottom = 5.u)
        SwitchRow("Новогоднее оформление", "Снег, гирлянда и таймер", data.options.newYear, Modifier.padding(vertical = 12.u), actions.setNewYear)
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x0BFFFFFF)))
        SwitchRow(
            "Запускать вместе с телефоном", "Обход включится сам после перезагрузки",
            data.options.autostart, Modifier.padding(top = 12.u), actions.setAutostart,
        )
        Caption("На Xiaomi, Huawei и Honor ещё нужно разрешить FiolKid автозапуск в настройках телефона.")
    }
}

@Composable
private fun ReportPanel(actions: AppActions) {
    Card {
        PanelHeader(Icons.Log, "Журнал и отчёты", "Информация для диагностики", "04")
        Hint("Журнал хранится в телефоне, последние 500 событий. В отчёт не попадают ключи и ссылки.")
        Row(Modifier.padding(top = 14.u), horizontalArrangement = Arrangement.spacedBy(9.u)) {
            FiolButton("Скопировать отчёт", icon = Icons.Copy, onClick = actions.copyReport)
            FiolButton("Отправить", icon = Icons.Share, onClick = actions.shareReport)
        }
    }
}

@Composable
private fun UpdatesPanel(version: String, data: AppData, actions: AppActions) {
    val update = data.update
    val available = update.available
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { PanelHeader(Icons.Download, "Обновления", "FiolKid $version", bottom = 0.u) }
            val shape = RoundedCornerShape(7.u)
            Box(
                Modifier.background(if (available != null) Color(0x33D1AA72) else Color(0x12BFA37E), shape)
                    .border(1.dp, Color(0x15BFA37E), shape).padding(horizontal = 7.u, vertical = 3.u),
            ) {
                BasicText(
                    if (available == null) "ТЕКУЩАЯ" else "ЕСТЬ НОВАЯ",
                    style = TextStyle(color = Color(0xFFC3B69F), fontSize = 9.5.us, fontWeight = FontWeight.SemiBold),
                )
            }
        }
        Hint(update.status.ifEmpty { "Ещё не проверял" })
        // Что нового в найденной версии — текст со страницы выпуска, до раздела «Как установить»
        val notes = available?.notes?.substringBefore("###")?.trim()
        if (!notes.isNullOrEmpty()) {
            val shape = RoundedCornerShape(11.u)
            Column(
                Modifier.padding(top = 12.u).fillMaxWidth().background(Palette.Well, shape)
                    .border(1.dp, Color(0x0BFFFFFF), shape).padding(12.u),
            ) {
                BasicText(
                    "Что нового в ${available.version}",
                    style = TextStyle(color = Color(0xFFEAD1AC), fontSize = 12.us, fontWeight = FontWeight.SemiBold),
                )
                BasicText(
                    notes, Modifier.padding(top = 6.u),
                    style = TextStyle(color = Palette.Muted, fontSize = 12.us, lineHeight = 19.us),
                )
            }
        }
        Column(Modifier.padding(top = 14.u), verticalArrangement = Arrangement.spacedBy(9.u)) {
            if (available != null)
                FiolButton("Обновить до ${available.version}", icon = Icons.Download, gold = true, enabled = !update.busy, onClick = actions.installUpdate)
            FiolButton("Проверить обновления", icon = Icons.Refresh, enabled = !update.busy, onClick = actions.checkUpdate)
        }
        Caption(
            "Обновления берутся только со страницы FiolKid-Android на GitHub и ставятся, только если подписаны " +
                "тем же ключом. Настройки и статистика сохраняются.",
        )
    }
}

@Composable
private fun SoonPanel() {
    Card {
        PanelHeader(Icons.Key, "Скоро", "Как в версии для компьютера")
        Hint("Ключ Amnezia и подписка — нейросети через VPN; Telegram через свой прокси.")
    }
}
