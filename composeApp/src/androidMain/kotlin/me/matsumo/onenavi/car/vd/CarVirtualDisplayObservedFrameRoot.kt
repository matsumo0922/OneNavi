package me.matsumo.onenavi.car.vd

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import me.matsumo.onenavi.feature.map.state.LocalMapHostViewport
import me.matsumo.onenavi.feature.map.state.MapHostInsets
import me.matsumo.onenavi.feature.map.state.MapHostViewport

@Composable
internal fun CarVirtualDisplayObservedFrameRoot(
    viewport: CarVirtualDisplayViewport,
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit,
) {
    val density = LocalDensity.current
    val observedFramePadding = viewport.observedFramePaddingValues(density)
    val hostViewport = viewport.toMapHostViewport(density)

    CompositionLocalProvider(
        LocalMapHostViewport provides hostViewport,
    ) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(observedFramePadding),
            contentAlignment = contentAlignment,
            content = content,
        )
    }
}

internal fun CarVirtualDisplayViewport.observedFramePaddingValues(density: Density): PaddingValues {
    val viewportObservedFrame = observedFrame

    return with(density) {
        PaddingValues(
            start = viewportObservedFrame.left.toDp(),
            top = viewportObservedFrame.top.toDp(),
            end = (surfaceWidth - viewportObservedFrame.right).toDp(),
            bottom = (surfaceHeight - viewportObservedFrame.bottom).toDp(),
        )
    }
}

internal fun CarVirtualDisplayViewport.toMapHostViewport(density: Density): MapHostViewport {
    val viewportObservedFrame = observedFrame

    return with(density) {
        MapHostViewport(
            visibleInsets = MapHostInsets(
                start = viewportObservedFrame.left.toDp(),
                top = viewportObservedFrame.top.toDp(),
                end = (surfaceWidth - viewportObservedFrame.right).toDp(),
                bottom = (surfaceHeight - viewportObservedFrame.bottom).toDp(),
            ),
            stableInsets = MapHostInsets(
                start = (stableLeft - viewportObservedFrame.left)
                    .coerceAtLeast(0)
                    .toDp(),
                top = (stableTop - viewportObservedFrame.top)
                    .coerceAtLeast(0)
                    .toDp(),
                end = (viewportObservedFrame.right - stableRight)
                    .coerceAtLeast(0)
                    .toDp(),
                bottom = (viewportObservedFrame.bottom - stableBottom)
                    .coerceAtLeast(0)
                    .toDp(),
            ),
        )
    }
}
