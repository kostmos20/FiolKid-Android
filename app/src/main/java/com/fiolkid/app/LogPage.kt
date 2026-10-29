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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import java.time.format.DateTimeFormatter

/** Вид строки журнала — как раскрашивает версия для компьютера. */
enum class EventKind { Info, Good, Warning, Error }

data class LogEvent(val line: String, val time: String, val title: String, val detail: String?, val kind: EventKind)

private val Bad = listOf(
    "ОШИБКА", "Ошибка", "не запустил", "Не удалось", "не удалось", "Не прошло", "не работает", "не включился",
    "не отвечает", "Сбой", "упала", "не разрешён", "Ни один", "Не установилось",
)
private val Warn = listOf("Проверка не прошла", "Подбираю способ заново", "Замеряю", "отменено", "по просьбе")
private val Good = listOf("Все проверки прошли", "Самый быстрый", "Включено", "Работаю со способом", "Обновлено")
private val Opened = Regex("""открылось (\d+) из (\d+)""")

fun toEvent(line: String): LogEvent {
    val hasTime = line.length > 10 && line[2] == ':' && line[5] == ':'
    val time = if (hasTime) line.substring(0, 8) else ""
    val message = if (hasTime) line.substring(8).trimStart() else line
    val kind = Opened.find(line)?.let { m ->
        val (ok, total) = m.destructured
        when {
            ok == total && total != "0" -> EventKind.Good
            ok == "0" -> EventKind.Error
            else -> EventKind.Warning
        }
    } ?: when {
        Bad.any { it in line } -> EventKind.Error
        Warn.any { it in line } -> EventKind.Warning
        Good.any { it in line } -> EventKind.Good
        else -> EventKind.Info
    }
    val (title, detail) = split(message)
    return LogEvent(line, time, title, detail, kind)
}

/** Короткая строка — целиком заголовок; длинная — заголовок до первого «: » или «. », остальное ниже. */
private fun split(message: String): Pair<String, String?> {
    if (message.length <= 56) return message to null
    val cut = listOf(message.indexOf(": "), message.indexOf(". ")).filter { it in 3..60 }.minOrNull() ?: return message to null
    return message.substring(0, cut) to message.substring(cut + 2)
}

private enum class Filter(val label: String) { All("Все"), Important("Важное"), Errors("Ошибки") }

/**
 * «Журнал» как в версии для компьютера: кнопки отчёта, итог подключения с подробной сводкой под раскрытием
 * и лента событий с фильтрами и поиском. Фильтр только прячет строки — журнал и отчёт остаются полными.
 */
@Composable
fun LogPage(state: HomeState, data: AppData, actions: AppActions) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = 24.u, end = 24.u, top = 52.u, bottom = 20.u),
    ) {
        PageHeader("СОСТОЯНИЕ И СОБЫТИЯ", "Журнал", "Всё, что происходит с подключением.\nЕсли нужна помощь — отправь отчёт.")

        Row(Modifier.fillMaxWidth().padding(bottom = 16.u), horizontalArrangement = Arrangement.spacedBy(8.u)) {
            FiolButton("Скопировать отчёт", Modifier.weight(1f), Icons.Copy, gold = true, onClick = actions.copyReport)
            FiolButton("Отправить", icon = Icons.Share, onClick = actions.shareReport)
            FiolButton("Проверить", icon = Icons.Refresh, onClick = actions.check)
        }

        Connection(state, data)
        Events(data)
    }
}

@Composable
private fun Connection(state: HomeState, data: AppData) {
    val looks = Service.entries.associateWith { state.looks.getValue(it) }
    val broken = looks.filterValues { it == Look.NotWorking }.keys
    val checking = looks.values.any { it == Look.Checking } || state.measuring != null
    val title = when {
        state.power == Power.Off -> "FiolKid выключен"
        checking || state.power == Power.Connecting -> "Проверяю…"
        broken.isEmpty() -> "FiolKid включён"
        else -> "Нужна проверка"
    }
    val (badge, kind) = when {
        state.power == Power.Off -> "Ожидание" to EventKind.Info
        checking || state.power == Power.Connecting -> "Проверяю" to EventKind.Warning
        broken.size == 1 -> "Сбой ${broken.first().title}" to EventKind.Error
        broken.size > 1 -> "Есть сбои" to EventKind.Error
        else -> "Всё в порядке" to EventKind.Good
    }
    val (dot, text, back) = when (kind) {
        EventKind.Good -> Triple(Palette.Green, Palette.GreenText, Color(0x0CA4C7A4))
        EventKind.Warning -> Triple(Palette.Amber, Palette.AmberText, Color(0x09E3C398))
        EventKind.Error -> Triple(Palette.Red, Palette.RedText, Color(0x08E8ADA8))
        EventKind.Info -> Triple(Palette.Gray, Palette.Muted, Color(0x07FFFFFF))
    }

    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                BasicText("ПОДКЛЮЧЕНИЕ", style = TextStyle(color = Color(0xFFB6B3B9), fontSize = 10.us, fontWeight = FontWeight.SemiBold))
                BasicText(
                    title, Modifier.padding(top = 5.u),
                    style = TextStyle(color = Palette.Text, fontSize = 20.us, fontWeight = FontWeight.SemiBold),
                )
            }
            val shape = RoundedCornerShape(16.u)
            Row(
                Modifier.background(back, shape).border(1.dp, Color(0x0EFFFFFF), shape).padding(horizontal = 10.u, vertical = 6.u),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(5.u).background(dot, CircleShape))
                BasicText(badge, Modifier.padding(start = 6.u), style = TextStyle(color = text, fontSize = 11.us))
            }
        }

        // Четыре сервиса «пилюлями»
        Row(Modifier.padding(top = 16.u).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.u)) {
            looks.forEach { (service, look) ->
                val shape = RoundedCornerShape(8.u)
                Row(
                    Modifier.weight(1f).background(Color(0x2B111620), shape).border(1.dp, Color(0x0AFFFFFF), shape)
                        .padding(vertical = 7.u),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(4.u).background(
                            when (look) {
                                Look.Working -> Palette.Green
                                Look.NotWorking -> Palette.Red
                                Look.Checking, Look.NeedsKey, Look.NeedsConnect -> Palette.Amber
                                else -> Palette.Gray
                            },
                            CircleShape,
                        ),
                    )
                    BasicText(
                        if (service == Service.Ai) "VPN" else service.title, Modifier.padding(start = 5.u),
                        style = TextStyle(color = Color(0xFFB6B9C5), fontSize = 11.us), maxLines = 1,
                    )
                }
            }
        }

        Box(Modifier.padding(top = 13.u).fillMaxWidth().height(1.dp).background(Color(0x0CFFFFFF)))
        Box(Modifier.padding(top = 11.u)) {
            Disclosure({ BasicText("Техническая сводка", style = TextStyle(color = Color(0xFFB9BBC7), fontSize = 12.us)) }) {
                Column(Modifier.padding(top = 10.u)) {
                    details(state, data).forEach { (line, color) ->
                        BasicText(
                            line,
                            style = TextStyle(color = color, fontSize = 11.us, lineHeight = 18.us, fontFamily = FontFamily.Monospace),
                        )
                    }
                }
            }
        }
    }
}

/** Подробная сводка: каждая строка своим цветом, чтобы с одного взгляда было видно, что не так. */
private fun details(state: HomeState, data: AppData): List<Pair<String, Color>> = buildList {
    val on = state.power != Power.Off
    add("Включено: ${if (on) "да" else "нет"}   способ: ${data.current ?: "—"}" to if (on) Palette.GreenText else Palette.Muted)
    add("Выбор способа: ${com.fiolkid.app.engine.Report.modeName(data.options.mode)}" to Palette.Muted)
    Service.entries.forEach { service ->
        val look = state.looks.getValue(service)
        add(
            "${service.name}: $look" to when (look) {
                Look.Working -> Palette.GreenText
                Look.NotWorking -> Palette.RedText
                Look.Checking, Look.NeedsKey, Look.NeedsConnect -> Palette.AmberText
                else -> Palette.Muted
            },
        )
    }
    val tg = data.telegram
    add(
        "Прокси Telegram: ${if (tg.running) "работает" else "выключен"}, подключений ${tg.active} (всего ${tg.total})" +
            (if (tg.connectedOnce) "" else ", в Telegram не добавлен") to Palette.Muted,
    )
    add(
        "Через обход: ${com.fiolkid.app.engine.formatBytes(data.traffic.session)}, за всё время " +
            com.fiolkid.app.engine.formatBytes(data.traffic.total) to Palette.Muted,
    )
}

@Composable
private fun Events(data: AppData) {
    var filter by rememberSaveable { mutableStateOf(Filter.All) }
    var query by rememberSaveable { mutableStateOf("") }
    val events = remember(data.log) { data.log.map(::toEvent) }
    val shown = events.filter { e ->
        when (filter) {
            Filter.All -> true
            Filter.Important -> e.kind == EventKind.Error || e.kind == EventKind.Warning
            Filter.Errors -> e.kind == EventKind.Error
        } && (query.isBlank() || e.line.contains(query.trim(), ignoreCase = true))
    }

    Card(padding = 17.u) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(
                "Лента событий", Modifier.weight(1f),
                style = TextStyle(color = Palette.Text, fontSize = 15.us, fontWeight = FontWeight.SemiBold),
            )
            val shape = RoundedCornerShape(7.u)
            Box(Modifier.background(Color(0x12BFA37E), shape).border(1.dp, Color(0x15BFA37E), shape).padding(horizontal = 7.u, vertical = 3.u)) {
                BasicText("${shown.size} / ${events.size}", style = TextStyle(color = Color(0xFFC3B69F), fontSize = 11.us))
            }
        }

        Row(Modifier.padding(top = 14.u, bottom = 4.u), verticalAlignment = Alignment.CenterVertically) {
            val shape = RoundedCornerShape(9.u)
            Row(Modifier.background(Color(0x5C171B24), shape).border(1.dp, Color(0x06FFFFFF), shape).padding(3.u)) {
                Filter.entries.forEach { f ->
                    val on = f == filter
                    Box(
                        Modifier.background(if (on) Color(0x20C2A071) else Color.Transparent, RoundedCornerShape(6.u))
                            .then(Tap({ filter = f })).padding(horizontal = 9.u, vertical = 6.u),
                    ) {
                        BasicText(f.label, style = TextStyle(color = if (on) Color(0xFFEAD1AC) else Color(0xFFAEB2BF), fontSize = 11.us))
                    }
                }
            }
            val field = RoundedCornerShape(8.u)
            Box(
                Modifier.padding(start = 9.u).weight(1f).height(32.u)
                    .background(Color(0x52191E28), field).border(1.dp, Color(0x0DFFFFFF), field).padding(horizontal = 10.u),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (query.isEmpty())
                    BasicText("Найти событие…", style = TextStyle(color = Color(0xFF9A9FAB), fontSize = 11.5.us))
                BasicTextField(
                    query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                    textStyle = TextStyle(color = Color(0xFFE8E7E5), fontSize = 11.5.us),
                    cursorBrush = SolidColor(Color(0xFFE8E7E5)),
                )
            }
        }

        if (shown.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(top = 24.u, bottom = 16.u), horizontalAlignment = Alignment.CenterHorizontally) {
                PathIcon(Icons.Log, Color(0xFFB7A382), 28.u, stroke = 1.4f)
                BasicText(
                    "Здесь спокойно", Modifier.padding(top = 12.u),
                    style = TextStyle(color = Palette.Text, fontSize = 14.us, fontWeight = FontWeight.Medium),
                )
                BasicText(
                    "Событий по этому фильтру нет.", Modifier.padding(top = 7.u, bottom = 10.u),
                    style = TextStyle(color = Color(0xFFAAB0BE), fontSize = 11.5.us),
                )
                BasicText(
                    "Показать все события",
                    Tap({
                        filter = Filter.All
                        query = ""
                    }),
                    style = TextStyle(color = Color(0xFFDEC198), fontSize = 11.5.us, textDecoration = TextDecoration.Underline),
                )
            }
        } else {
            // Своя прокрутка ленты; новые события — внизу, лента сама докручивается к ним
            val scroll = rememberScrollState()
            LaunchedEffect(shown.size) { scroll.scrollTo(scroll.maxValue) }
            Column(Modifier.heightIn(max = 300.u).verticalScroll(scroll)) {
                shown.forEach { EventRow(it) }
            }
        }

        Box(Modifier.padding(top = 8.u, bottom = 12.u).fillMaxWidth().height(1.dp).background(Color(0x0BFFFFFF)))
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(
                data.lastCheck?.let { "Последняя проверка · ${it.format(DateTimeFormatter.ofPattern("HH:mm:ss"))}" } ?: "Проверки ещё не было",
                Modifier.weight(1f), style = TextStyle(color = Color(0xFF9299A7), fontSize = 10.5.us),
            )
            PathIcon(Icons.Shield, Color(0xFFACBBA9), 11.u, stroke = 1.6f)
            BasicText("Без ключей и ссылок", Modifier.padding(start = 5.u), style = TextStyle(color = Color(0xFF9299A7), fontSize = 10.5.us))
        }
    }
}

@Composable
private fun EventRow(event: LogEvent) {
    val (mark, titleColor) = when (event.kind) {
        EventKind.Error -> Palette.Red to Palette.RedText
        EventKind.Warning -> Palette.Amber to Palette.AmberText
        EventKind.Good -> Palette.Green to Palette.Text
        EventKind.Info -> Palette.Gray to Palette.Text
    }
    Column {
        Row(Modifier.padding(vertical = 12.u)) {
            BasicText(
                event.time, Modifier.width(56.u).padding(top = 2.u),
                style = TextStyle(color = Color(0xFF929AAA), fontSize = 10.5.us, fontFamily = FontFamily.Monospace),
            )
            Box(Modifier.width(16.u).padding(top = 6.u)) { Box(Modifier.size(6.u).background(mark, CircleShape)) }
            Column(Modifier.weight(1f)) {
                BasicText(event.title, style = TextStyle(color = titleColor, fontSize = 12.us, fontWeight = FontWeight.Medium))
                if (event.detail != null)
                    BasicText(
                        event.detail, Modifier.padding(top = 3.u),
                        style = TextStyle(color = Color(0xFFA9AEBC), fontSize = 11.us, lineHeight = 17.us),
                    )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x06FFFFFF)))
    }
}
