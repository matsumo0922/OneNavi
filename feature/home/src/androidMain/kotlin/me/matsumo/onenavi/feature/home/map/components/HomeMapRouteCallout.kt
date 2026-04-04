package me.matsumo.onenavi.feature.home.map.components

import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.mapbox.geojson.Point
import com.mapbox.maps.MapboxExperimental
import com.mapbox.maps.ViewAnnotationAnchor
import com.mapbox.maps.ViewAnnotationAnchorConfig
import com.mapbox.maps.extension.compose.annotation.ViewAnnotation
import com.mapbox.maps.viewannotation.OnViewAnnotationUpdatedListener
import com.mapbox.maps.viewannotation.annotationAnchors
import com.mapbox.maps.viewannotation.geometry
import com.mapbox.maps.viewannotation.viewAnnotationOptions
import me.matsumo.onenavi.feature.home.map.RouteResult
import com.mapbox.navigation.ui.maps.R as NavR

private const val SELECTED_BG = 0xFF4285F4.toInt()
private const val UNSELECTED_BG = 0xFFFFFFFF.toInt()
private const val SELECTED_TEXT = 0xFFFFFFFF.toInt()
private const val UNSELECTED_TEXT = 0xFF333333.toInt()
private const val SHADOW_COLOR = 0x40000000
private const val TEXT_SIZE_SP = 14f

/**
 * ルート吹き出し。
 * Mapbox SDK の 9-patch drawable を流用し、ViewAnnotation composable で
 * ルートの中間地点に直接配置する。SDK の setCalloutAdapter と異なり
 * ズームレベルに関係なく常に表示される。
 *
 * アンカー位置の変更に応じて矢印の向きを自動的に切り替える。
 */
@Suppress("COMPOSE_APPLIER_CALL_MISMATCH")
@OptIn(MapboxExperimental::class)
@Composable
internal fun HomeMapRouteCallout(
    point: Point,
    routeResult: RouteResult,
    isPrimary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var currentAnchor by remember { mutableStateOf(ViewAnnotationAnchor.BOTTOM_LEFT) }

    ViewAnnotation(
        modifier = modifier,
        options = viewAnnotationOptions {
            geometry(point)
            allowOverlap(true)
            allowOverlapWithPuck(true)
            ignoreCameraPadding(true)
            priority(if (isPrimary) 0 else 1)
            annotationAnchors(
                { anchor(ViewAnnotationAnchor.BOTTOM_LEFT) },
                { anchor(ViewAnnotationAnchor.BOTTOM_RIGHT) },
                { anchor(ViewAnnotationAnchor.TOP_LEFT) },
                { anchor(ViewAnnotationAnchor.TOP_RIGHT) },
            )
        },
        onUpdatedListener = object : OnViewAnnotationUpdatedListener {
            override fun onViewAnnotationAnchorUpdated(
                view: View,
                anchor: ViewAnnotationAnchorConfig,
            ) {
                currentAnchor = anchor.anchor
            }
        },
    ) {
        HomeMapRouteCalloutContent(
            routeResult = routeResult,
            isPrimary = isPrimary,
            anchor = currentAnchor,
            onClick = onClick,
        )
    }
}

@Composable
private fun HomeMapRouteCalloutContent(
    routeResult: RouteResult,
    isPrimary: Boolean,
    anchor: ViewAnnotationAnchor,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val durationMinutes = (routeResult.item.durationSeconds / 60).toInt()
    val tollLabel = buildTollLabel(routeResult)
    val displayText = "$durationMinutes 分\n$tollLabel"

    val bgColor = if (isPrimary) SELECTED_BG else UNSELECTED_BG
    val textColor = if (isPrimary) SELECTED_TEXT else UNSELECTED_TEXT

    val bgRes = anchorToBgRes(anchor)
    val shadowRes = anchorToShadowRes(anchor)

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val wrapper = FrameLayout(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                )
            }

            LayoutInflater.from(ctx).inflate(
                NavR.layout.mapbox_navigation_route_callout,
                wrapper,
                true,
            )

            wrapper.setOnClickListener { onClick() }
            wrapper
        },
        update = { wrapper ->
            val shapeView = wrapper.findViewById<TextView>(NavR.id.shape)
            val etaView = wrapper.findViewById<TextView>(NavR.id.eta)

            shapeView.text = displayText
            shapeView.setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SIZE_SP)
            shapeView.backgroundTintList = ColorStateList.valueOf(SHADOW_COLOR)
            shapeView.setBackgroundResource(shadowRes)

            etaView.text = displayText
            etaView.setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SIZE_SP)
            etaView.setTextColor(textColor)
            etaView.backgroundTintList = ColorStateList.valueOf(bgColor)
            etaView.setBackgroundResource(bgRes)
        },
    )
}

private fun anchorToBgRes(anchor: ViewAnnotationAnchor): Int = when (anchor) {
    ViewAnnotationAnchor.BOTTOM_LEFT -> NavR.drawable.mapbox_ic_route_callout_bottom_left
    ViewAnnotationAnchor.BOTTOM_RIGHT -> NavR.drawable.mapbox_ic_route_callout_bottom_right
    ViewAnnotationAnchor.TOP_LEFT -> NavR.drawable.mapbox_ic_route_callout_top_left
    ViewAnnotationAnchor.TOP_RIGHT -> NavR.drawable.mapbox_ic_route_callout_top_right
    else -> NavR.drawable.mapbox_ic_route_callout_bottom_left
}

private fun anchorToShadowRes(anchor: ViewAnnotationAnchor): Int = when (anchor) {
    ViewAnnotationAnchor.BOTTOM_LEFT -> NavR.drawable.mapbox_ic_route_callout_bottom_left_shadow
    ViewAnnotationAnchor.BOTTOM_RIGHT -> NavR.drawable.mapbox_ic_route_callout_bottom_right_shadow
    ViewAnnotationAnchor.TOP_LEFT -> NavR.drawable.mapbox_ic_route_callout_top_left_shadow
    ViewAnnotationAnchor.TOP_RIGHT -> NavR.drawable.mapbox_ic_route_callout_top_right_shadow
    else -> NavR.drawable.mapbox_ic_route_callout_bottom_left_shadow
}

private fun buildTollLabel(routeResult: RouteResult): String {
    val item = routeResult.item
    return when {
        item.tollFee != null -> "¥${item.tollFee}"
        item.hasTolls -> "有料道路"
        else -> "一般道"
    }
}
