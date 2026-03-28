package me.matsumo.onenavi.core.ui.screen.view

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

@Composable
fun ColorSlider(
    color: Color?,
    onColorChanged: (Color) -> Unit,
    modifier: Modifier = Modifier,
    trackHeight: Dp = 20.dp,
    knobRadius: Dp = 16.dp,
    enabled: Boolean = true,
) {
    val trackStops = remember {
        listOf(Color.White) +
            List(13) { i -> Color.hsv(i * 30f, 1f, 1f) } +
            listOf(Color.White)
    }
    val trackBrush = remember { Brush.horizontalGradient(trackStops) }
    val currentNorm = remember(color) {
        when (color) {
            null -> 0f
            Color.White -> 1f
            else -> color.hue360() / 360f
        }
    }

    val animatedPos by animateFloatAsState(
        targetValue = currentNorm,
        label = "slider_pos",
    )

    val density = LocalDensity.current
    val knobRadiusPx = with(density) { knobRadius.toPx() }

    Box(
        modifier
            .requiredHeight(trackHeight)
            .semantics(mergeDescendants = true) {
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = currentNorm,
                    range = 0f..1f,
                )
            }
            .focusable(enabled)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { offset ->
                    val newNorm = (offset.x / size.width).coerceIn(0f, 0.99f)
                    val newColor = when {
                        newNorm <= 0f -> Color.White
                        newNorm >= 1f -> Color.White
                        else -> Color.hsv(
                            hue = newNorm * 360f,
                            saturation = 1f,
                            value = 1f,
                        )
                    }
                    onColorChanged(newColor)
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectDragGestures { change, _ ->
                    val newNorm = (change.position.x / size.width).coerceIn(0f, 0.99f)
                    val newColor = when {
                        newNorm <= 0f -> Color.White
                        newNorm >= 1f -> Color.White
                        else -> Color.hsv(
                            hue = newNorm * 360f,
                            saturation = 1f,
                            value = 1f,
                        )
                    }
                    onColorChanged(newColor)
                }
            },
    ) {
        Canvas(Modifier.fillMaxWidth().height(trackHeight)) {
            // == トラック描画 ==
            drawRoundRect(
                brush = trackBrush,
                cornerRadius = CornerRadius(size.height / 2),
            )

            // == つまみ描画 (color が null なら非表示) ==
            if (color != null) {
                val center = Offset(animatedPos * size.width, size.height / 2)
                // 外枠
                drawCircle(
                    color = Color.White,
                    radius = knobRadiusPx,
                    center = center,
                )
                // 内側：選択中の color をそのまま使う
                drawCircle(
                    color = color,
                    radius = knobRadiusPx * 0.7f,
                    center = center,
                )
            }
        }
    }
}

private fun Color.hue360(): Float {
    val r = red
    val g = green
    val b = blue

    val max = max(max(r, g), b)
    val min = min(min(r, g), b)
    val delta = max - min

    if (delta == 0f) return 0f

    val hue = when (max) {
        r -> 60f * (((g - b) / delta) % 6f)
        g -> 60f * (((b - r) / delta) + 2f)
        else -> 60f * (((r - g) / delta) + 4f)
    }
    return if (hue < 0f) hue + 360f else hue
}
