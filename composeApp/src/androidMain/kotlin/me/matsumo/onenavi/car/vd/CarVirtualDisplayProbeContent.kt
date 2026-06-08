package me.matsumo.onenavi.car.vd

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun CarVirtualDisplayProbeContent(
    displayId: Int,
    expectedDisplayId: Int,
    rendererLabel: String,
    viewport: CarVirtualDisplayProbeViewport,
    inputState: CarVirtualDisplayProbeInputState,
    modifier: Modifier = Modifier,
) {
    val contentHorizontalPadding = viewport.contentHorizontalPaddingValues(
        density = LocalDensity.current,
    )
    val observedFrameLeft = viewport.observedFrameLeft
    val observedFrameRight = viewport.observedFrameRight
    val hostSlotRightInset = viewport.surfaceWidth - viewport.visibleRight
    val observedFrameWidth = observedFrameRight - observedFrameLeft
    val observedFrameRightInset = viewport.surfaceWidth - observedFrameRight
    val observedFrameLabel = "blue frame=${observedFrameWidth}x${viewport.surfaceHeight} " +
        "L=$observedFrameLeft R=$observedFrameRightInset " +
        "Rect($observedFrameLeft,0 - $observedFrameRight,${viewport.surfaceHeight})"
    val hostSlotFrameLabel = "pale frame=${viewport.visibleWidth}x${viewport.surfaceHeight} " +
        "L=${viewport.visibleLeft} R=$hostSlotRightInset " +
        "Rect(${viewport.visibleLeft},0 - ${viewport.visibleRight},${viewport.surfaceHeight})"
    val hostStableLabel = "host stable=${viewport.stableWidth}x${viewport.stableHeight} " +
        "${viewport.stableAreaLabel}"
    val surfacePointLabel = "pt surface=${inputState.surfacePointLabel} " +
        "surfaceIn=${inputState.insideSurfaceLabel}"
    val hostVisiblePointLabel = "hostVisible=${inputState.hostVisiblePointLabel} " +
        "hostVisibleIn=${inputState.insideHostVisibleAreaLabel}"
    val gestureLabel = "d=${inputState.distanceLabel} v=${inputState.velocityLabel} " +
        "scale=${inputState.scaleFactorLabel}"

    MaterialTheme {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color(0xFF111827)),
            contentAlignment = Alignment.Center,
        ) {
            CarVirtualDisplayProbeViewportOverlay(
                modifier = Modifier.fillMaxSize(),
                viewport = viewport,
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentHorizontalPadding),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier.padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "OneNavi VD $rendererLabel",
                        color = Color.White,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "display=$displayId / expected=$expectedDisplayId",
                        color = Color(0xFF93C5FD),
                        fontSize = 18.sp,
                    )
                    Text(
                        text = "surface=${viewport.surfaceWidth}x${viewport.surfaceHeight} dpi=${viewport.densityDpi}",
                        color = Color(0xFFE5E7EB),
                        fontSize = 14.sp,
                    )
                    Text(
                        text = observedFrameLabel,
                        color = Color(0xFF60A5FA),
                        fontSize = 14.sp,
                    )
                    Text(
                        text = hostSlotFrameLabel,
                        color = Color(0xFFC7D2FE),
                        fontSize = 14.sp,
                    )
                    Text(
                        text = hostStableLabel,
                        color = Color(0xFFE5E7EB),
                        fontSize = 14.sp,
                    )
                    Text(
                        text = "input #${inputState.sequence} ${inputState.kind.label} pan=${inputState.panModeLabel}",
                        color = Color(0xFFFDE68A),
                        fontSize = 14.sp,
                    )
                    Text(
                        text = surfacePointLabel,
                        color = Color(0xFFFDE68A),
                        fontSize = 14.sp,
                    )
                    Text(
                        text = hostVisiblePointLabel,
                        color = Color(0xFFFDE68A),
                        fontSize = 14.sp,
                    )
                    Text(
                        text = gestureLabel,
                        color = Color(0xFFFDE68A),
                        fontSize = 14.sp,
                    )
                    Text(
                        text = "Surface -> VirtualDisplay -> $rendererLabel",
                        color = Color(0xFFE5E7EB),
                        fontSize = 16.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun CarVirtualDisplayProbeViewportOverlay(
    viewport: CarVirtualDisplayProbeViewport,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier = modifier,
    ) {
        drawProbeViewportFrames(viewport = viewport)
    }
}

private fun DrawScope.drawProbeViewportFrames(viewport: CarVirtualDisplayProbeViewport) {
    drawProbeFrame(
        color = Color(0xCC60A5FA),
        left = viewport.observedFrameLeft,
        top = 0,
        right = viewport.observedFrameRight,
        bottom = viewport.surfaceHeight,
        strokeWidth = OBSERVED_FRAME_STROKE_WIDTH,
    )
    drawProbeFrame(
        color = Color(0x66C7D2FE),
        left = viewport.visibleLeft,
        top = 0,
        right = viewport.visibleRight,
        bottom = viewport.surfaceHeight,
        strokeWidth = HOST_SLOT_STROKE_WIDTH,
    )
}

private fun DrawScope.drawProbeFrame(
    color: Color,
    left: Int,
    top: Int,
    right: Int,
    bottom: Int,
    strokeWidth: Float,
) {
    val strokeInset = strokeWidth / 2f
    val frameLeft = left.toFloat() + strokeInset
    val frameTop = top.toFloat() + strokeInset
    val frameWidth = (right - left).toFloat() - strokeWidth
    val frameHeight = (bottom - top).toFloat() - strokeWidth

    drawRect(
        color = color,
        topLeft = Offset(
            x = frameLeft,
            y = frameTop,
        ),
        size = Size(
            width = frameWidth.coerceAtLeast(0f),
            height = frameHeight.coerceAtLeast(0f),
        ),
        style = Stroke(width = strokeWidth),
    )
}

private fun CarVirtualDisplayProbeViewport.contentHorizontalPaddingValues(
    density: Density,
): PaddingValues {
    return with(density) {
        PaddingValues(
            start = visibleLeft.toDp(),
            end = (surfaceWidth - visibleRight).toDp(),
        )
    }
}

private val CarVirtualDisplayProbeViewport.horizontalSafetyInset: Int
    get() = minOf(
        visibleLeft,
        surfaceWidth - visibleRight,
    ).coerceAtLeast(0)

private val CarVirtualDisplayProbeViewport.observedFrameLeft: Int
    get() = (visibleLeft - horizontalSafetyInset).coerceIn(0, surfaceWidth)

private val CarVirtualDisplayProbeViewport.observedFrameRight: Int
    get() = (visibleRight + horizontalSafetyInset).coerceIn(observedFrameLeft, surfaceWidth)

/** host 上で OneNavi として観測できる描画枠を示す線幅。 */
private const val OBSERVED_FRAME_STROKE_WIDTH = 2f

/** split 後に host が表示している横スロットを示す検証枠の線幅。 */
private const val HOST_SLOT_STROKE_WIDTH = 2f
