package me.matsumo.onenavi.feature.map.components.callout

import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionContext
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.core.graphics.applyCanvas
import androidx.core.graphics.createBitmap
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import me.matsumo.onenavi.feature.map.LocalMapRenderScale

@Composable
internal fun rememberMapComposeBitmapDescriptor(
    vararg keys: Any,
    content: @Composable () -> Unit,
): BitmapDescriptor {
    val parent = LocalView.current as ViewGroup
    val compositionContext = rememberCompositionContext()
    val currentContent by rememberUpdatedState(content)
    val mapRenderScale = LocalMapRenderScale.current
    val displayDensity = LocalDensity.current
    val bitmapDensity = remember(displayDensity, mapRenderScale) {
        Density(
            density = displayDensity.density * mapRenderScale,
            fontScale = displayDensity.fontScale,
        )
    }

    return remember(parent, compositionContext, currentContent, bitmapDensity, *keys) {
        renderComposeContentToBitmapDescriptor(
            parent = parent,
            compositionContext = compositionContext,
            bitmapDensity = bitmapDensity,
            content = currentContent,
        )
    }
}

/**
 * Compose slot を密度 [bitmapDensity] で measure / 描画して marker icon 用 bitmap へ変換する。
 *
 * GoogleMap は marker icon を焼付 density 空間で描画するため、VirtualDisplay などで実 density と
 * 焼付 density がずれる環境では bitmap 解像度を焼付 density（[bitmapDensity]）で生成しないと、
 * icon だけが描画スケールぶん小さく表示される。
 *
 * @param parent ComposeView を一時的に attach する親
 * @param compositionContext 親 composition から引き継ぐ context
 * @param bitmapDensity bitmap を measure / 描画する密度
 * @param content bitmap 化する Compose slot
 * @return measure 済みサイズで描画した bitmap の [BitmapDescriptor]
 */
private fun renderComposeContentToBitmapDescriptor(
    parent: ViewGroup,
    compositionContext: CompositionContext,
    bitmapDensity: Density,
    content: @Composable () -> Unit,
): BitmapDescriptor {
    val composeView = ComposeView(parent.context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        setParentCompositionContext(compositionContext)
        setContent {
            CompositionLocalProvider(LocalDensity provides bitmapDensity) {
                content()
            }
        }
    }

    parent.addView(composeView)

    try {
        composeView.measure(UnspecifiedMeasureSpec, UnspecifiedMeasureSpec)
        check(composeView.measuredWidth > 0 && composeView.measuredHeight > 0) {
            "CallOut marker content must have non-zero width and height."
        }

        composeView.layout(
            0,
            0,
            composeView.measuredWidth,
            composeView.measuredHeight,
        )

        val bitmap = createBitmap(
            width = composeView.measuredWidth,
            height = composeView.measuredHeight,
        )

        bitmap.applyCanvas { composeView.draw(this) }

        return BitmapDescriptorFactory.fromBitmap(bitmap)
    } finally {
        parent.removeView(composeView)
    }
}

private val UnspecifiedMeasureSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
