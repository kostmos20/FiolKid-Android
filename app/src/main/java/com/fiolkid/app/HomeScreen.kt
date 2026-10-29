package com.fiolkid.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.time.LocalDateTime

enum class Tab { Home, Log, Settings }

/**
 * Весь экран приложения: фон-улица, шапка, страница, вкладки внизу и снег поверх. Размеры — в единицах
 * версии для компьютера (окно 500 точек шириной), см. [LocalUnit].
 */
@Composable
fun FiolKidApp(
    state: HomeState,
    version: String,
    onPower: () -> Unit,
    time: Float = animationTime(),
    now: LocalDateTime? = null,
    initialTab: Tab = Tab.Home,
    log: List<String> = emptyList(),
    update: UpdateUi? = null,
) {
    var tab by remember { mutableStateOf(initialTab) }
    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF18191F))) {
        val unit = maxWidth.value / 500f
        CompositionLocalProvider(LocalUnit provides unit) {
            // Фон: зимняя улица и затемнение книзу, чтобы текст и плитки читались
            Image(
                painterResource(R.drawable.background), null,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0f to Color(0x61121219), 0.15f to Color(0x4D15141B), 0.39f to Color(0xA318171E),
                        0.62f to Color(0xDB17171E), 0.91f to Color(0xF716171E),
                    ),
                ),
            )
            if (tab != Tab.Home)
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(0f to Color(0xBB191B26), 0.35f to Color(0xED1B1D27), 1f to Color(0xFA171B25)),
                    ),
                )

            Column(Modifier.fillMaxSize()) {
                Header(version)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    Garland(
                        time, unit, dim = if (tab == Tab.Home) 1f else 0.4f,
                        modifier = Modifier.padding(start = 16.u, end = 16.u, top = 2.u).fillMaxWidth().height(34.u),
                    )
                    when (tab) {
                        Tab.Home -> HomePage(state, onPower, now, update)
                        Tab.Log -> LogPage(log)
                        Tab.Settings -> SettingsPage(version, update)
                    }
                }
                NavBar(tab) { tab = it }
            }

            if (tab == Tab.Home)
                Snow(time, unit, Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun Header(version: String) {
    Box(
        Modifier.fillMaxWidth().background(Color(0x80171923)).drawBehind {
            drawLine(Color(0x07FFFFFF), Offset(0f, size.height), Offset(size.width, size.height))
        }.statusBarsPadding().height(43.u),
    ) {
        Row(Modifier.padding(start = 17.u).align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically) {
            PathIcon("M16 3v12M9 7.3a12 12 0 1 0 14 0", Color(0xFFDFCEB0), 17.u, stroke = 2f)
            BasicText(
                "FiolKid", Modifier.padding(start = 8.u),
                style = TextStyle(color = WinterColors.Text, fontSize = 13.us, fontWeight = FontWeight.SemiBold),
            )
            BasicText(version, Modifier.padding(start = 7.u, top = 1.u), style = TextStyle(color = Color(0xFFB8B5B9), fontSize = 11.us))
        }
    }
}

@Composable
private fun HomePage(state: HomeState, onPower: () -> Unit, fixedNow: LocalDateTime?, update: UpdateUi?) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(43.u))
        Countdown(fixedNow)
        Spacer(Modifier.weight(1f))

        // Главное состояние и кнопка
        val (headline, subline) = state.headline
        BasicText(
            headline,
            style = TextStyle(
                color = WinterColors.Text, fontSize = 32.us, fontWeight = FontWeight.SemiBold,
                shadow = Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, 4f), 32f),
            ),
        )
        BasicText(
            subline, Modifier.padding(start = 30.u, end = 30.u, top = 9.u),
            style = TextStyle(
                color = WinterColors.Sub, fontSize = 13.us, textAlign = TextAlign.Center,
                shadow = Shadow(Color.Black.copy(alpha = 0.7f), Offset(0f, 4f), 16f),
            ),
        )
        Spacer(Modifier.height(25.u))
        PowerButton(state, onPower)

        Spacer(Modifier.weight(0.9f))
        Services(state)
        Spacer(Modifier.weight(1f))

        // Тихая строка внизу; если вышло обновление — на её месте ссылка «Обновить», как на компьютере
        val available = update?.state?.available
        if (update != null && available != null) {
            BasicText(
                if (update.state.busy) update.state.status else "Вышла версия ${available.version} — обновить",
                Modifier.clickable(remember { MutableInteractionSource() }, null, onClick = update.onInstall),
                style = TextStyle(
                    color = WinterColors.Warm, fontSize = 11.5.us, fontWeight = FontWeight.SemiBold,
                    textDecoration = TextDecoration.Underline,
                ),
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PathIcon("m5 12 4 4L19 6", Color(0xFFA3B4AA), 13.u, stroke = 1.5f)
                BasicText(
                    "Привычные сайты работают как обычно", Modifier.padding(start = 6.u),
                    style = TextStyle(color = Color(0xFFB6B8C2), fontSize = 11.us),
                )
            }
        }
        Spacer(Modifier.height(26.u))
    }
}

@Composable
private fun Countdown(fixedNow: LocalDateTime?) {
    var text by remember { mutableStateOf(countdown(fixedNow ?: LocalDateTime.now())) }
    if (fixedNow == null)
        LaunchedEffect(Unit) {
            while (true) {
                text = countdown(LocalDateTime.now())
                delay(1000)
            }
        }
    Row(
        Modifier.background(Color(0x8C24212B), RoundedCornerShape(16.u))
            .border(1.dp, Color(0x2BE3D3B8), RoundedCornerShape(16.u))
            .padding(horizontal = 13.u, vertical = 6.u),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText("✧", Modifier.padding(end = 7.u).offset(y = (-1).u), style = TextStyle(color = Color(0xFFE2BD82), fontSize = 15.us))
        BasicText(text, style = TextStyle(color = Color(0xFFEBE1D1), fontSize = 11.5.us))
    }
}

/** Кнопка-картинка: у выключенной — приглушённая копия. Подпись поверх, правее значка; огонёк справа. */
@Composable
private fun PowerButton(state: HomeState, onPower: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    Box(
        Modifier.offset(y = if (pressed) 2.u else 0.u).size(218.u, 65.u)
            .clickable(press, indication = null, onClick = onPower),
    ) {
        Image(
            painterResource(if (state.power == Power.Off) R.drawable.button_off else R.drawable.button_on), null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.bleed(236.u, 81.u, (-9).u, (-8).u).alpha(if (pressed) 0.92f else 1f),
        )
        BasicText(
            state.powerLabel, Modifier.align(Alignment.Center).padding(start = 12.u, bottom = 1.u),
            style = TextStyle(color = Color(0xFFF4EEE3), fontSize = 16.us, fontWeight = FontWeight.SemiBold),
        )
        if (state.power != Power.Off) {
            val light = if (state.power == Power.Connecting) blink() else 1f
            Box(
                Modifier.align(Alignment.CenterEnd).padding(end = 9.u).size(6.u).alpha(light)
                    .background(Color(0xFFB3C8AC), CircleShape),
            )
        }
    }
}

@Composable
private fun blink(): Float {
    val t = animationTime()
    return 0.2f + 0.8f * (1 + kotlin.math.cos(t / 0.6f * Math.PI.toFloat())) / 2
}

@Composable
private fun Services(state: HomeState) {
    Column(Modifier.width(452.u)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 1.u), verticalAlignment = Alignment.CenterVertically) {
            BasicText(
                "Твои сервисы", Modifier.weight(1f),
                style = TextStyle(color = Color(0xFFE6DFD4), fontSize = 13.us, fontWeight = FontWeight.SemiBold),
            )
            BasicText(state.summary, style = TextStyle(color = Color(0xFFC2BAB0), fontSize = 10.5.us))
        }
        Spacer(Modifier.height(27.u))
        val rows = Service.entries.chunked(2)
        rows.forEachIndexed { index, pair ->
            if (index > 0) Spacer(Modifier.height(28.u))
            Row(horizontalArrangement = Arrangement.spacedBy(16.u)) {
                pair.forEach { ServiceCard(it, state.looks.getValue(it), Modifier.size(218.u, 132.u)) }
            }
        }
    }
}

/** Журнал пока простым списком: полный вид, как на компьютере, — позже. */
@Composable
private fun LogPage(log: List<String>) {
    Column(Modifier.fillMaxSize().padding(top = 60.u, start = 24.u, end = 24.u)) {
        BasicText("Журнал", style = TextStyle(color = WinterColors.Text, fontSize = 30.us, fontWeight = FontWeight.SemiBold))
        Column(
            Modifier.padding(top = 14.u).fillMaxWidth().weight(1f)
                .background(Color(0x66111620), RoundedCornerShape(16.u))
                .padding(14.u)
                .verticalScroll(rememberScrollState()),
        ) {
            if (log.isEmpty())
                BasicText("Событий пока нет — нажми «Включить» на главной.", style = TextStyle(color = Color(0xFFC0C0CA), fontSize = 13.us))
            log.forEach {
                BasicText(it, Modifier.padding(bottom = 6.u), style = TextStyle(color = Color(0xFFDCD8D2), fontSize = 12.us))
            }
        }
        Spacer(Modifier.height(16.u))
    }
}

/** Вкладки: тёмная панель со снежной шапкой сверху, у выбранной — тёплый цвет и чёрточка снизу. */
@Composable
private fun NavBar(tab: Tab, onTab: (Tab) -> Unit) {
    Box(
        Modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color(0xFF242630), Color(0xFF191C25))))
            .drawBehind { drawLine(Color(0x18D6DFEC), Offset.Zero, Offset(size.width, 0f)) }
            .navigationBarsPadding(),
    ) {
        Box(Modifier.fillMaxWidth().height(83.u)) {
            Image(
                painterResource(R.drawable.snow_cap), null, contentScale = ContentScale.FillBounds,
                modifier = Modifier.bleed(510.u, 93.5.u, (-5).u, (-36).u),
            )
            Row(
                Modifier.fillMaxWidth().padding(start = 24.u, end = 24.u, top = 9.u).align(Alignment.Center),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                TabButton("Главная", "m3 10 9-7 9 7M5.5 9v11h5v-6h3v6h5V9", tab == Tab.Home) { onTab(Tab.Home) }
                TabButton(
                    "Журнал",
                    "M8 3h10a3 3 0 0 1 3 3v12a3 3 0 0 1-3 3H8a3 3 0 0 1-3-3V6a3 3 0 0 1 3-3ZM5 7H3m2 5H3m2 5H3m6-10h7m-7 5h7m-7 5h4",
                    tab == Tab.Log,
                ) { onTab(Tab.Log) }
                TabButton(
                    "Настройки",
                    "m10 3-.6 2.5L7 6.8 4.5 6 3 8.6l1.8 1.8v3.2L3 15.4 4.5 18l2.5-.8 2.4 1.3.6 2.5h3l.6-2.5 2.4-1.3 2.5.8 1.5-2.6-1.8-1.8v-3.2L20 8.6 18.5 6l-2.5.8-2.4-1.3L13 3ZM14.5 12a3 3 0 1 1-6 0a3 3 0 1 1 6 0Z",
                    tab == Tab.Settings,
                ) { onTab(Tab.Settings) }
            }
        }
    }
}

@Composable
private fun TabButton(label: String, icon: String, selected: Boolean, onClick: () -> Unit) {
    val color = if (selected) WinterColors.Warm else WinterColors.Nav
    Box(
        Modifier.width(105.u).height(58.u).clickable(remember { MutableInteractionSource() }, null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            PathIcon(icon, color, 21.u, stroke = 1.4f)
            BasicText(label, Modifier.padding(top = 7.u), style = TextStyle(color = color, fontSize = 10.5.us))
        }
        if (selected)
            Box(
                Modifier.align(Alignment.BottomCenter).offset(y = 0.u).size(15.u, 2.u)
                    .background(Color(0xFFCDA76C), RoundedCornerShape(1.u)),
            )
    }
}

/** Значок-контур из строки SVG, растянутый по размеру значка (как Stretch=Uniform); толщина — в единицах экрана. */
@Composable
private fun PathIcon(data: String, color: Color, size: Dp, stroke: Float) {
    val path = remember(data) { svg(data) }
    val unit = LocalUnit.current
    Canvas(Modifier.size(size)) {
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
