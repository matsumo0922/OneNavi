package me.matsumo.onenavi.feature.map.components.callout

import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalView
import androidx.core.graphics.applyCanvas
import androidx.core.graphics.createBitmap
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory

@Composable
internal fun rememberMapComposeBitmapDescriptor(
    vararg keys: Any,
    content: @Composable () -> Unit,
): BitmapDescriptor {
    val parent = LocalView.current as ViewGroup
    val compositionContext = rememberCompositionContext()
    val currentContent by rememberUpdatedState(content)

    return remember(parent, compositionContext, currentContent, *keys) {
        renderComposeContentToBitmapDescriptor(
            parent = parent,
            compositionContext = compositionContext,
            content = currentContent,
        )
    }
}

private fun renderComposeContentToBitmapDescriptor(
    parent: ViewGroup,
    compositionContext: CompositionContext,
    content: @Composable () -> Unit,
): BitmapDescriptor {
    val composeView = ComposeView(parent.context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        setParentCompositionContext(compositionContext)
        setContent(content)
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
