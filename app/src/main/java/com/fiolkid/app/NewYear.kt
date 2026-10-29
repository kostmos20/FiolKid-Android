package com.fiolkid.app

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalDensity
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Время с начала показа в секундах — для снега и гирлянды (кадр за кадром). */
@Composable
fun animationTime(): Float {
    val time by produceState(0f) {
        val start = withFrameNanos { it }
        while (true)
            withFrameNanos { value = (it - start) / 1_000_000_000f }
    }
    return time
}

private class Flake(
    val x: Float, val depth: Float, val patterned: Boolean, val fall: Float, val start: Float,
    val sway: Float, val swayPeriod: Float, val swayStart: Float, val spin: Float, val spinStart: Float,
)

/**
 * Снег, как в версии для компьютера: узорчатые снежинки вперемешку с мелкими точками. Снежинки медленно
 * вращаются, всё падает с покачиванием; дальние — мельче, бледнее и медленнее.
 */
@Composable
fun Snow(time: Float, unit: Float, modifier: Modifier = Modifier, count: Int = 55) {
    val flakes = remember {
        val random = Random(2026)
        List(count) {
            val patterned = random.nextFloat() < 0.5f
            val depth = random.nextFloat()
            val fall = 10 + (1 - depth) * 9 + random.nextFloat() * 4
            Flake(
                x = random.nextFloat(), depth = depth, patterned = patterned, fall = fall,
                start = random.nextFloat() * fall,
                sway = 12 + random.nextFloat() * 10, swayPeriod = 2.5f + random.nextFloat() * 2.5f,
                swayStart = random.nextFloat() * 3,
                spin = if (random.nextFloat() < 0.5f) 360f / (8 + random.nextFloat() * 10) else -360f / (8 + random.nextFloat() * 10),
                spinStart = random.nextFloat() * 8,
            )
        }
    }
    val px = with(LocalDensity.current) { unit * density }
    Canvas(modifier) {
        for (f in flakes) {
            val progress = ((time + f.start) / f.fall) % 1f
            val y = -24 * px + progress * (size.height + 40 * px)
            val swing = sin(((time + f.swayStart) / f.swayPeriod) * PI.toFloat()) * f.sway * px
            val x = f.x * size.width + swing
            val alpha = 0.3f + f.depth * 0.55f
            if (f.patterned) {
                val radius = (11 + f.depth * 12) * px * 0.5f
                rotate((time + f.spinStart) * f.spin, Offset(x, y)) { snowflake(Offset(x, y), radius, alpha) }
            } else {
                drawCircle(Color.White.copy(alpha = alpha), radius = (2 + f.depth * 2.5f) * px / 2, center = Offset(x, y))
            }
        }
    }
}

/** Шестилучевая снежинка с веточками. */
private fun DrawScope.snowflake(center: Offset, radius: Float, alpha: Float) {
    val color = Color.White.copy(alpha = alpha)
    val width = (radius * 0.16f).coerceAtLeast(1f)
    for (i in 0 until 6) {
        val angle = i * PI.toFloat() / 3
        val dir = Offset(cos(angle), sin(angle))
        val tip = center + dir * radius
        drawLine(color, center, tip, width, StrokeCap.Round)
        for (at in listOf(0.5f, 0.75f)) {
            val base = center + dir * (radius * at)
            val branch = radius * (if (at == 0.5f) 0.34f else 0.24f)
            for (side in listOf(-1f, 1f)) {
                val a = angle + side * PI.toFloat() / 4
                drawLine(color, base, base + Offset(cos(a), sin(a)) * branch, width, StrokeCap.Round)
            }
        }
    }
}

private val BulbColors = listOf(Color(0xFFEF4444), Color(0xFFFACC15), Color(0xFF22C55E), Color(0xFF38BDF8), Color(0xFFF472B6))

/** Гирлянда: провисающий провод и лампочки, которые по очереди загораются и гаснут. */
@Composable
fun Garland(time: Float, unit: Float, modifier: Modifier = Modifier, bulbs: Int = 13, dim: Float = 1f) {
    val px = with(LocalDensity.current) { unit * density }
    Canvas(modifier) {
        val sag = 12 * px
        val top = 4 * px
        val w = size.width
        val wire = Path().apply {
            moveTo(0f, top)
            quadraticTo(w / 4, top + sag * 2, w / 2, top)
            quadraticTo(w * 3 / 4, top + sag * 2, w, top)
        }
        drawPath(wire, Color(0x99334155), alpha = dim, style = Stroke(1.5f * px))
        for (i in 0 until bulbs) {
            val x = (i + 0.5f) * w / bulbs
            val phase = (x / (w / 2)) % 1f
            val y = top + sag * 2 * 2 * phase * (1 - phase)
            val color = BulbColors[i % BulbColors.size]
            // Мигание: 0,9 с туда и обратно, лампочки сдвинуты по фазе тройками
            val t = time - (i % 3) * 0.6f
            val wave = if (t < 0) 0f else (1 - cos(t / 0.9f * PI.toFloat())) / 2
            val light = (1f - 0.75f * wave) * dim
            val center = Offset(x, y + 1 * px + 6 * px)
            drawCircle(
                Brush.radialGradient(listOf(color.copy(alpha = 0.55f * light), Color.Transparent), center, 13 * px),
                radius = 13 * px, center = center,
            )
            drawOval(color.copy(alpha = light), topLeft = center - Offset(4.5f * px, 6 * px), size = Size(9 * px, 12 * px))
        }
    }
}

/** Текст таймера: с 1 по 14 января — поздравление, иначе сколько осталось до Нового года. */
fun countdown(now: LocalDateTime): String {
    if (now.monthValue == 1 && now.dayOfMonth <= 14) return "С Новым годом!"
    val target = LocalDateTime.of(now.year + 1, 1, 1, 0, 0)
    var seconds = ChronoUnit.SECONDS.between(now, target)
    val days = (seconds / 86400).toInt()
    seconds %= 86400
    return "До Нового года — $days ${daysWord(days)} %02d:%02d:%02d".format(seconds / 3600, seconds % 3600 / 60, seconds % 60)
}

fun daysWord(days: Int) = when {
    days % 100 in 11..14 -> "дней"
    days % 10 == 1 -> "день"
    days % 10 in 2..4 -> "дня"
    else -> "дней"
}
