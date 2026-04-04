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
import me.matsumo.onenavi.core.common.formatDuration
import me.matsumo.onenavi.core.common.formatYen
import me.matsumo.onenavi.core.resource.Res
import me.matsumo.onenavi.core.resource.common_unit_day
import me.matsumo.onenavi.core.resource.common_unit_hour
import me.matsumo.onenavi.core.resource.common_unit_minute
import me.matsumo.onenavi.core.resource.home_map_route_result_general_road
import me.matsumo.onenavi.core.resource.home_map_route_result_toll_road
import me.matsumo.onenavi.feature.home.map.RouteResult
import org.jetbrains.compose.resources.stringResource
import com.mapbox.navigation.ui.maps.R as NavR

/**
 * ルート吹き出し。
 * Mapbox SDK の 9-patch drawable を流用し、ViewAnnotation composable で
 * 指定座標に配置する。アンカー位置の変更に応じて矢印の向きを自動切り替えする。
 */
@Suppress("COMPOSE_APPLIER_CALL_MISMATCH")
@OptIn(MapboxExperimental::class)
@Composable
internal fun HomeMapRouteCallout(
    point: Point,
    routeResult: RouteResult,
    isPrimary: Boolean,
    style: RouteCalloutStyle,
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
            style = style,
            anchor = currentAnchor,
            onClick = onClick,
        )
    }
}

@Composable
private fun HomeMapRouteCalloutContent(
    routeResult: RouteResult,
    style: RouteCalloutStyle,
    anchor: ViewAnnotationAnchor,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val durationText = formatDuration(
        totalSeconds = routeResult.item.durationSeconds,
        dayLabel = stringResource(Res.string.common_unit_day),
        hourLabel = stringResource(Res.string.common_unit_hour),
        minuteLabel = stringResource(Res.string.common_unit_minute),
    )
    val tollLabel = buildTollLabel(
        routeResult = routeResult,
        tollRoadLabel = stringResource(Res.string.home_map_route_result_toll_road),
        generalRoadLabel = stringResource(Res.string.home_map_route_result_general_road),
    )
    val displayText = "$durationText\n$tollLabel"

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
            shapeView.setTextSize(TypedValue.COMPLEX_UNIT_SP, style.textSizeSp)
            shapeView.backgroundTintList = ColorStateList.valueOf(style.shadowColor)
            shapeView.setBackgroundResource(shadowRes)

            etaView.text = displayText
            etaView.setTextSize(TypedValue.COMPLEX_UNIT_SP, style.textSizeSp)
            etaView.setTextColor(style.textColor)
            etaView.backgroundTintList = ColorStateList.valueOf(style.backgroundColor)
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

private fun buildTollLabel(
    routeResult: RouteResult,
    tollRoadLabel: String,
    generalRoadLabel: String,
): String {
    val item = routeResult.item
    val fee = item.tollFee
    return when {
        fee != null -> formatYen(fee)
        item.hasTolls -> tollRoadLabel
        else -> generalRoadLabel
    }
}
