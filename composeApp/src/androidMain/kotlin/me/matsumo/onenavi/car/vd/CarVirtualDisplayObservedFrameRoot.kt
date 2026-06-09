package me.matsumo.onenavi.car.vd

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

@Composable
internal fun CarVirtualDisplayObservedFrameRoot(
    viewport: CarVirtualDisplayViewport,
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit,
) {
    val observedFramePadding = viewport.observedFramePaddingValues(LocalDensity.current)

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(observedFramePadding),
        contentAlignment = contentAlignment,
        content = content,
    )
}

private fun CarVirtualDisplayViewport.observedFramePaddingValues(density: Density): PaddingValues {
    val viewportObservedFrame = observedFrame

    return with(density) {
        PaddingValues(
            start = viewportObservedFrame.left.toDp(),
            end = (surfaceWidth - viewportObservedFrame.right).toDp(),
        )
    }
}
