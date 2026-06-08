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
    modifier: Modifier = Modifier,
) {
    val visiblePadding = viewport.visiblePaddingValues(
        density = LocalDensity.current,
    )

    MaterialTheme {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color(0xFF020617)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(visiblePadding)
                    .background(Color(0xFF111827))
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
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
                        text = "visible=${viewport.visibleWidth}x${viewport.visibleHeight} ${viewport.visibleAreaLabel}",
                        color = Color(0xFFE5E7EB),
                        fontSize = 14.sp,
                    )
                    Text(
                        text = "stable=${viewport.stableWidth}x${viewport.stableHeight} ${viewport.stableAreaLabel}",
                        color = Color(0xFFC7D2FE),
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

private fun CarVirtualDisplayProbeViewport.visiblePaddingValues(
    density: Density,
): PaddingValues {
    return with(density) {
        PaddingValues(
            start = visibleLeft.toDp(),
            top = visibleTop.toDp(),
            end = (surfaceWidth - visibleRight).toDp(),
            bottom = (surfaceHeight - visibleBottom).toDp(),
        )
    }
}
