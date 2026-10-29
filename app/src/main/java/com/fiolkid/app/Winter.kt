package com.fiolkid.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp

/**
 * Зимнее оформление — один в один с версией для компьютера. Там окно шириной 500 точек, поэтому все размеры
 * здесь в тех же единицах: 1 единица = ширина экрана / 500. На любом телефоне картинка та же, только в масштабе.
 */
val LocalUnit = staticCompositionLocalOf { 1f }

val Number.u: Dp
    @Composable @ReadOnlyComposable get() = (toFloat() * LocalUnit.current).dp

/** Размер шрифта в тех же единицах (без учёта крупного шрифта в настройках — макет рассчитан на точные размеры). */
val Number.us: TextUnit
    @Composable @ReadOnlyComposable get() = with(LocalDensity.current) { (toFloat() * LocalUnit.current).dp.toSp() }

object WinterColors {
    val Text = Color(0xFFF3F1EC)
    val Sub = Color(0xFFDBD5CF)
    val Warm = Color(0xFFEBD2AD)
    val Nav = Color(0xFFB3B7C4)
}

/**
 * Картинка больше своего места (снег и свечение у плиток выходят за край): рисуем её размером [w]×[h]
 * со сдвигом [x], [y], а место в разметке занимает как обычно.
 */
fun Modifier.bleed(w: Dp, h: Dp, x: Dp, y: Dp) = layout { measurable, constraints ->
    val placeable = measurable.measure(Constraints.fixed(w.roundToPx(), h.roundToPx()))
    layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(x.roundToPx(), y.roundToPx()) }
}

/** Путь из строки SVG (значки взяты из версии для компьютера). */
fun svg(data: String): Path = PathParser().parsePathString(data).toPath()
