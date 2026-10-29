package com.fiolkid.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight

private val Gray = Color(0xFFB2B5C1)

/**
 * Карточка сервиса: плитка-картинка со снежной шапкой (серая, пока сервис не работает, и его цвета — когда
 * работает), логотип, название, описание и строка состояния.
 */
@Composable
fun ServiceCard(service: Service, look: Look, modifier: Modifier = Modifier) {
    val working = look == Look.Working
    val logoColor by animateColorAsState(if (look == Look.Off || look == Look.Soon) Gray else service.accent, tween(500), label = "logo")
    val (status, statusColor) = when (look) {
        Look.Off -> "Выключено" to Color(0xFF9296A0)
        Look.Checking -> "Проверяю…" to Color(0xFFD9C28E)
        Look.Working -> "Работает" to Color(0xFFB6CFB9)
        Look.NotWorking -> "Не работает" to Color(0xFFE39A8E)
        Look.NeedsKey -> "Нужен ключ" to Color(0xFFD9C28E)
        Look.Soon -> "Скоро" to Color(0xFF9296A0)
    }

    Box(modifier) {
        // Плитка шире и выше карточки — снег и свечение выходят за край
        Image(
            painter = painterResource(if (working) service.plate else R.drawable.card_off),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.bleed(232.u, 222.u, (-7).u, (-44).u),
        )
        Column(Modifier.padding(start = 18.u, top = 25.u, end = 18.u)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Logo(service, logoColor, glow = working, modifier = Modifier.size(29.u))
                Spacer(Modifier.width(10.u))
                BasicText(
                    service.title,
                    style = TextStyle(color = Color(0xFFF3F1EC), fontSize = 18.us, fontWeight = FontWeight.SemiBold),
                )
            }
            BasicText(
                service.description,
                modifier = Modifier.padding(top = 8.u),
                style = TextStyle(color = Color(0xFFC4C4CC), fontSize = 11.us),
                maxLines = 1,
            )
            Row(Modifier.padding(top = 15.u), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(5.u).background(statusColor, CircleShape))
                Spacer(Modifier.width(6.u))
                BasicText(
                    status,
                    style = TextStyle(color = if (look == Look.Off || look == Look.Soon) Color(0xFFC8C8D0) else statusColor, fontSize = 12.us),
                )
            }
        }
    }
}

// Контурные логотипы — из Tabler Icons (MIT, © Paweł Kuna), сетка 24×24, линия толщиной 2, как неоновые трубки
private val Outlines = mapOf(
    Service.YouTube to listOf(
        "M2 8a4 4 0 0 1 4 -4h12a4 4 0 0 1 4 4v8a4 4 0 0 1 -4 4h-12a4 4 0 0 1 -4 -4v-8",
        "M10 9l5 3l-5 3l0 -6",
    ),
    Service.Discord to listOf(
        "M8 12a1 1 0 1 0 2 0a1 1 0 0 0 -2 0",
        "M14 12a1 1 0 1 0 2 0a1 1 0 0 0 -2 0",
        "M15.5 17c0 1 1.5 3 2 3c1.5 0 2.833 -1.667 3.5 -3c.667 -1.667 .5 -5.833 -1.5 -11.5c-1.457 -1.015 -3 -1.34 -4.5 -1.5" +
            "l-.972 1.923a11.913 11.913 0 0 0 -4.053 0l-.975 -1.923c-1.5 .16 -3.043 .485 -4.5 1.5c-2 5.667 -2.167 9.833 -1.5 11.5" +
            "c.667 1.333 2 3 3.5 3c.5 0 2 -2 2 -3",
        "M7 16.5c3.5 1 6.5 1 10 0",
    ),
    Service.Telegram to listOf("M15 10l-4 4l6 6l4 -16l-18 7l4 2l2 6l3 -4"),
)

/** Четырёхлучевая искорка с вогнутыми сторонами (на сетке 48×48). */
private fun sparkle(x: Float, y: Float, r: Float) = Path().apply {
    moveTo(x, y - r)
    quadraticTo(x, y, x + r, y)
    quadraticTo(x, y, x, y + r)
    quadraticTo(x, y, x - r, y)
    quadraticTo(x, y, x, y - r)
    close()
}

@Composable
private fun Logo(service: Service, color: Color, glow: Boolean, modifier: Modifier) {
    val outlines = remember(service) { Outlines[service]?.map(::svg) }
    val stars = remember { listOf(sparkle(19f, 27f, 15f), sparkle(36.5f, 11.5f, 8f), sparkle(37f, 35f, 6f)) }
    Canvas(modifier) {
        if (outlines != null) {
            scale(size.width / 24f, pivot = Offset.Zero) {
                // Свечение работающего — несколько широких полупрозрачных обводок под основной
                if (glow) {
                    for ((width, alpha) in listOf(6f to 0.10f, 4f to 0.18f)) {
                        outlines.forEach {
                            drawPath(it, color.copy(alpha = alpha), style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
                        }
                    }
                } else {
                    outlines.forEach {
                        drawPath(it, Color.Black.copy(alpha = 0.4f), style = Stroke(2.4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                }
                outlines.forEach {
                    drawPath(it, color, style = Stroke(2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
        } else {
            scale(size.width / 48f, pivot = Offset.Zero) {
                if (glow) stars.forEach { drawPath(it, color.copy(alpha = 0.18f), style = Stroke(5f, join = StrokeJoin.Round)) }
                stars.forEach { drawPath(it, color) }
            }
        }
    }
}
