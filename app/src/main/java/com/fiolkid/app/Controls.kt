package com.fiolkid.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Палитра «Журнала» и «Настроек» — из App.xaml версии для компьютера. */
object Palette {
    val Text = Color(0xFFF3F1EC)
    val Muted = Color(0xFFC0C0CA)
    val Faint = Color(0xFFA9ADBA)
    val Warm = Color(0xFFD1AA72)
    val Green = Color(0xFFA9C6AE)
    val Red = Color(0xFFD1958E)
    val Amber = Color(0xFFD0AE7B)
    val Gray = Color(0xFF8C929F)
    val GreenText = Color(0xFFBED0B8)
    val RedText = Color(0xFFE0ACA6)
    val AmberText = Color(0xFFE0C29C)
    val Divider = Color(0x0DFFFFFF)
    val Well = Color(0x52181E28)
}

// Значки — контуры из версии для компьютера
object Icons {
    const val Refresh = "M20 10a8 8 0 1 0-2 8M20 4v6h-6"
    const val Copy = "M10 7h10a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H10a2 2 0 0 1-2-2V9a2 2 0 0 1 2-2ZM15 7V3H4v13h4"
    const val Share = "M12 15V3m-5 5 5-5 5 5M4 14v6h16v-6"
    const val Download = "M12 3v12m-5-5 5 5 5-5M4 16v5h16v-5"
    const val Log = "M8 3h10a3 3 0 0 1 3 3v12a3 3 0 0 1-3 3H8a3 3 0 0 1-3-3V6a3 3 0 0 1 3-3ZM5 7H3m2 5H3m2 5H3m6-10h7m-7 5h7m-7 5h4"
    const val Shield = "m12 3 8 3v6c0 5-8 9-8 9s-8-4-8-9V6ZM8 12l3 3 5-6"
    const val Snowflake = "M12 2v20M3.3 7l17.4 10M3.3 17 20.7 7M9 4l3 3 3-3M9 20l3-3 3 3M3 10l4-1-1-4m12 14-1-4 4-1M6 19l1-4-4-1m18-4-4-1 1-4"
    const val Key = "M13 9a5 5 0 1 1-10 0a5 5 0 1 1 10 0ZM12 13l8 8m-4-4 3-3m-6 0 3-3"
    const val Arrow = "m9 5 7 7-7 7"
    const val Telegram = "m3 14 26-10-6 25-9-8-6 4 2-8zm7 3L24 9 14 21"
}

/** Значок-контур из строки SVG, растянутый по размеру значка (как Stretch=Uniform); толщина — в единицах экрана. */
@Composable
fun PathIcon(data: String, color: Color, size: Dp, stroke: Float, modifier: Modifier = Modifier) {
    val path = remember(data) { svg(data) }
    val unit = LocalUnit.current
    Canvas(modifier.size(size)) {
        val bounds = path.getBounds()
        val width = stroke * unit * density
        val k = minOf((this.size.width - width) / bounds.width, (this.size.height - width) / bounds.height)
        val dx = (this.size.width - bounds.width * k) / 2 - bounds.left * k
        val dy = (this.size.height - bounds.height * k) / 2 - bounds.top * k
        translate(dx, dy) {
            scale(k, pivot = Offset.Zero) {
                drawPath(path, color, style = Stroke(width / k, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

@Composable
fun Tap(onClick: () -> Unit, enabled: Boolean = true): Modifier =
    Modifier.clickable(remember { MutableInteractionSource() }, null, enabled = enabled, onClick = onClick)

/** Заголовок страницы: надпись капсом, крупное название и подпись. */
@Composable
fun PageHeader(kicker: String, title: String, subtitle: String) {
    BasicText(kicker, style = TextStyle(color = Color(0xFFC9B397), fontSize = 10.us, fontWeight = FontWeight.SemiBold))
    BasicText(
        title, Modifier.padding(top = 10.u),
        style = TextStyle(color = Palette.Text, fontSize = 30.us, fontWeight = FontWeight.SemiBold),
    )
    BasicText(
        subtitle, Modifier.padding(top = 10.u, bottom = 20.u),
        style = TextStyle(color = Color(0xFFC1C0C7), fontSize = 12.5.us, lineHeight = 20.us),
    )
}

/** Карточка-панель (стиль Card): тёмный градиент, тонкая рамка, скругление 20. */
@Composable
fun Card(modifier: Modifier = Modifier, padding: Dp = 19.u, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(20.u)
    Column(
        modifier.fillMaxWidth().padding(bottom = 14.u)
            .background(
                Brush.linearGradient(0f to Color(0xED3B3A42), 0.42f to Color(0xF72C2D37), 1f to Color(0xFA242731)),
                shape,
            )
            .border(1.dp, Color(0x26BFB5A5), shape)
            .padding(padding),
    ) { content() }
}

/** Заголовок панели настроек: значок в тёплой рамке, название, подпись и номер справа. */
@Composable
fun PanelHeader(
    icon: String, title: String, subtitle: String, number: String? = null,
    iconColor: Color = Color(0xFFD5BA91), iconBack: Color? = null, bottom: Dp = 7.u,
) {
    Row(Modifier.fillMaxWidth().padding(bottom = bottom), verticalAlignment = Alignment.CenterVertically) {
        val shape = RoundedCornerShape(11.u)
        Box(
            Modifier.size(34.u)
                .background(
                    iconBack?.let { Brush.linearGradient(listOf(it, it)) }
                        ?: Brush.linearGradient(listOf(Color(0x55594C3C), Color(0x602E3039))),
                    shape,
                )
                .border(1.dp, Color(0x24BBA78A), shape),
            contentAlignment = Alignment.Center,
        ) { PathIcon(icon, iconColor, 18.u, stroke = 1.5f) }
        Column(Modifier.padding(start = 11.u).weight(1f)) {
            BasicText(title, style = TextStyle(color = Palette.Text, fontSize = 17.us, fontWeight = FontWeight.SemiBold))
            BasicText(subtitle, Modifier.padding(top = 4.u), style = TextStyle(color = Color(0xFFACAFBB), fontSize = 11.us))
        }
        if (number != null)
            BasicText(number, style = TextStyle(color = Color(0xFF8F8C90), fontSize = 11.us))
    }
}

@Composable
fun Hint(text: String, modifier: Modifier = Modifier) =
    BasicText(
        text, modifier.padding(top = 10.u),
        style = TextStyle(color = Palette.Muted, fontSize = 12.5.us, lineHeight = 20.us),
    )

@Composable
fun Caption(text: String, modifier: Modifier = Modifier) =
    BasicText(
        text, modifier.padding(top = 10.u),
        style = TextStyle(color = Color(0xFFAEB0BC), fontSize = 11.us, lineHeight = 17.us),
    )

/** Строка состояния: точка своего цвета и текст чуть светлее. */
@Composable
fun StateLine(text: String, dot: Color, color: Color, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(5.u).background(dot, CircleShape))
        BasicText(text, Modifier.padding(start = 7.u), style = TextStyle(color = color, fontSize = 12.5.us))
    }
}

/** Кнопка: золотая (главная) или графитовая. Значок слева от подписи. */
@Composable
fun FiolButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: String? = null,
    gold: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(11.u)
    val content = if (gold) Color(0xFF191B22) else Color(0xFFE4E1DF)
    Row(
        modifier.heightIn(min = 36.u).alpha(if (enabled) 1f else 0.5f)
            .background(
                if (gold) Brush.linearGradient(listOf(Color(0xFFBDA079), Color(0xFF927753)))
                else Brush.linearGradient(listOf(Color(0xD944434B), Color(0xE633363F))),
                shape,
            )
            .border(1.dp, if (gold) Color(0x5CDCC095) else Color(0x2CC5B69F), shape)
            .then(Tap(onClick, enabled))
            .padding(horizontal = 13.u, vertical = 8.u),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) PathIcon(icon, content, 14.u, stroke = 1.7f, if (text.isEmpty()) Modifier else Modifier.padding(end = 8.u))
        if (text.isNotEmpty())
            BasicText(text, style = TextStyle(color = content, fontSize = 13.us, fontWeight = FontWeight.Medium), maxLines = 1)
    }
}

/** Переключатель (стиль Switch): дорожка 37×22, у включённого — золотистая. */
@Composable
fun SwitchRow(title: String, subtitle: String, checked: Boolean, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
    val knob by animateFloatAsState(if (checked) 1f else 0f, tween(160), label = "knob")
    Row(modifier.fillMaxWidth().then(Tap({ onChange(!checked) })), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            BasicText(title, style = TextStyle(color = Color(0xFFE0DFE3), fontSize = 13.us, fontWeight = FontWeight.Medium))
            BasicText(subtitle, Modifier.padding(top = 4.u), style = TextStyle(color = Color(0xFFA9ADBA), fontSize = 11.us))
        }
        val shape = RoundedCornerShape(11.u)
        Box(
            Modifier.padding(start = 12.u).size(37.u, 22.u)
                .background(if (checked) Color(0x59A48A63) else Color(0xFF171E28), shape)
                .border(1.dp, Color(0x1AFFFFFF), shape),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                Modifier.offset(x = (3 + 17 * knob).u).size(14.u)
                    .background(if (checked) Color(0xFFE0C79F) else Color(0xFF818997), CircleShape),
            )
        }
    }
}

/** Сегменты (стиль Segment) — выбор одного из нескольких в тёмной «лунке». */
@Composable
fun <T> Segments(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    val outer = RoundedCornerShape(12.u)
    Row(
        Modifier.fillMaxWidth().background(Color(0x69191E27), outer).border(1.dp, Color(0x09FFFFFF), outer).padding(4.u),
        horizontalArrangement = Arrangement.spacedBy(3.u),
    ) {
        options.forEach { (value, label) ->
            val on = value == selected
            val shape = RoundedCornerShape(9.u)
            Box(
                Modifier.weight(1f).heightIn(min = 43.u)
                    .background(
                        if (on) Brush.linearGradient(listOf(Color(0x4C6D5B44), Color(0x3A3F3A36))) else Brush.linearGradient(listOf(Color.Transparent, Color.Transparent)),
                        shape,
                    )
                    .border(1.dp, if (on) Color(0x33B4986D) else Color.Transparent, shape)
                    .then(Tap({ onSelect(value) }))
                    .padding(horizontal = 4.u, vertical = 7.u),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    label,
                    style = TextStyle(
                        color = if (on) Color(0xFFEAD2B0) else Color(0xFFB5BAC6), fontSize = 12.us, lineHeight = 15.us,
                        textAlign = TextAlign.Center,
                    ),
                )
            }
        }
    }
}

/** Строка списка (стиль RowItem): кружок выбора, название с пометкой, результат справа. */
@Composable
fun ChoiceRow(title: String, note: String?, right: String, rightColor: Color, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(11.u)
    Row(
        Modifier.padding(top = 6.u).fillMaxWidth()
            .background(if (selected) Color(0x178B6E47) else Color(0x351B202A), shape)
            .border(1.dp, if (selected) Color(0x5EC4A071) else Color(0x0BFFFFFF), shape)
            .then(Tap(onClick))
            .padding(horizontal = 10.u, vertical = 9.u),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(13.u).border(1.dp, if (selected) Color(0xFFC9AA7E) else Color(0x40FFFFFF), CircleShape),
            contentAlignment = Alignment.Center,
        ) { if (selected) Box(Modifier.size(7.u).background(Color(0xFFC9AA7E), CircleShape)) }
        Column(Modifier.padding(start = 10.u).weight(1f)) {
            BasicText(title, style = TextStyle(color = Palette.Text, fontSize = 12.us, fontWeight = FontWeight.Medium), maxLines = 1)
            if (!note.isNullOrEmpty())
                BasicText(note, Modifier.padding(top = 2.u), style = TextStyle(color = Color(0xFF9CA5B3), fontSize = 10.5.us))
        }
        BasicText(right, Modifier.padding(start = 10.u), style = TextStyle(color = rightColor, fontSize = 10.5.us, textAlign = TextAlign.End))
    }
}

/** Раскрывающийся блок (стиль Disclosure): заголовок со стрелкой, которая поворачивается вниз. */
@Composable
fun Disclosure(
    header: @Composable RowScope.() -> Unit,
    startOpen: Boolean = false,
    content: @Composable () -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(startOpen) }
    val turn by animateFloatAsState(if (open) 90f else 0f, tween(180), label = "arrow")
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 24.u).then(Tap({ open = !open })), verticalAlignment = Alignment.CenterVertically) {
            header()
            Box(Modifier.weight(1f))
            PathIcon(Icons.Arrow, Color(0xFFB9BBC7), 12.u, stroke = 1.6f, Modifier.rotate(turn))
        }
        AnimatedVisibility(open) { content() }
    }
}

/** Тонкая черта-разделитель. */
@Composable
fun Divider(modifier: Modifier = Modifier) =
    Box(modifier.fillMaxWidth().padding(vertical = 0.u).heightIn(min = 1.dp).background(Palette.Divider))
