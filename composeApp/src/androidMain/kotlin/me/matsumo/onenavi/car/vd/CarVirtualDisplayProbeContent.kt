package me.matsumo.onenavi.car.vd

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
import androidx.compose.ui.graphics.Color
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
    val hostVisibleLabel = "draw=surface contentX=${viewport.visibleWidth} " +
        "${viewport.visibleAreaLabel}"
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
                        text = hostVisibleLabel,
                        color = Color(0xFFE5E7EB),
                        fontSize = 14.sp,
                    )
                    Text(
                        text = hostStableLabel,
                        color = Color(0xFFC7D2FE),
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
