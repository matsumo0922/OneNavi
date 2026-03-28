package me.matsumo.onenavi.core.ui.utils

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import kotlin.math.roundToInt

@Composable
fun pxToMm(px: Int): Float = with(LocalDensity.current) {
    val dpi = density * 160f
    px / dpi * 25.4f
}

@Composable
fun mmToPx(mm: Float): Int = with(LocalDensity.current) {
    val dpi = density * 160f
    (mm / 25.4f * dpi).roundToInt()
}

@Composable
fun Int.toDp(): Dp = with(LocalDensity.current) {
    toDp()
}

@Composable
fun Dp.toPx(): Float = with(LocalDensity.current) {
    toPx()
}
