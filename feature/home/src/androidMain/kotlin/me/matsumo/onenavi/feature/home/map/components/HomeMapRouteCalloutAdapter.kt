package me.matsumo.onenavi.feature.home.map.components

import android.content.Context
import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.mapbox.maps.ViewAnnotationAnchor
import com.mapbox.maps.ViewAnnotationAnchorConfig
import com.mapbox.maps.viewannotation.annotationAnchors
import com.mapbox.maps.viewannotation.viewAnnotationOptions
import com.mapbox.navigation.base.ExperimentalPreviewMapboxNavigationAPI
import com.mapbox.navigation.base.route.NavigationRoute
import com.mapbox.navigation.ui.maps.route.callout.api.MapboxRouteCalloutAdapter
import com.mapbox.navigation.ui.maps.route.callout.model.CalloutViewHolder
import com.mapbox.navigation.ui.maps.route.callout.model.RouteCallout
import me.matsumo.onenavi.core.model.RouteResult
import com.mapbox.navigation.ui.maps.R as NavR

/**
 * ルート吹き出しのカスタムアダプター。
 * SDK の矢印つき吹き出しレイアウトを流用しつつ、
 * 所要時間 + 有料道路料金/「一般道」ラベルを2行で表示する。
 */
@OptIn(ExperimentalPreviewMapboxNavigationAPI::class)
internal class HomeMapRouteCalloutAdapter(
    private val context: Context,
) : MapboxRouteCalloutAdapter() {

    private var routeResults: List<RouteResult> = emptyList()
    private var onCalloutClicked: ((NavigationRoute) -> Unit)? = null
    private val calloutViews = mutableMapOf<NavigationRoute, View>()

    fun updateRouteResults(results: List<RouteResult>) {
        routeResults = results
    }

    fun setOnCalloutClickListener(listener: (NavigationRoute) -> Unit) {
        onCalloutClicked = listener
    }

    /**
     * 既存の吹き出し View の選択色を in-place で切り替える。
     * View の再生成や位置の再計算は行わない。
     */
    fun updateSelectionStyling(selectedRoute: NavigationRoute?) {
        for ((route, view) in calloutViews) {
            val isSelected = route === selectedRoute
            val etaView = view.findViewById<TextView>(NavR.id.eta) ?: continue
            etaView.setTextColor(if (isSelected) SELECTED_TEXT else UNSELECTED_TEXT)
            etaView.backgroundTintList = ColorStateList.valueOf(
                if (isSelected) SELECTED_BG else UNSELECTED_BG,
            )
        }
    }

    override fun onCreateViewHolder(callout: RouteCallout): CalloutViewHolder {
        val isPrimary = callout.isPrimary

        val durationMinutes = (callout.route.directionsRoute.duration() / 60).toInt()
        val tollLabel = findTollLabel(callout.route)
        val displayText = "$durationMinutes 分\n$tollLabel"

        val bgColor = if (isPrimary) SELECTED_BG else UNSELECTED_BG
        val textColor = if (isPrimary) SELECTED_TEXT else UNSELECTED_TEXT

        val wrapper = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            )
        }

        LayoutInflater.from(context).inflate(
            NavR.layout.mapbox_navigation_route_callout,
            wrapper,
            true,
        )

        val shapeView = wrapper.findViewById<TextView>(NavR.id.shape)
        val etaView = wrapper.findViewById<TextView>(NavR.id.eta)

        shapeView.text = displayText
        shapeView.setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SIZE_SP)
        shapeView.backgroundTintList = ColorStateList.valueOf(SHADOW_COLOR)

        etaView.text = displayText
        etaView.setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SIZE_SP)
        etaView.setTextColor(textColor)
        etaView.backgroundTintList = ColorStateList.valueOf(bgColor)

        wrapper.setOnClickListener {
            onCalloutClicked?.invoke(callout.route)
        }

        calloutViews[callout.route] = wrapper

        return CalloutViewHolder.Builder(wrapper)
            .options(
                viewAnnotationOptions {
                    ignoreCameraPadding(true)
                    priority(if (isPrimary) 0 else 1)
                    annotationAnchors(
                        { anchor(ViewAnnotationAnchor.BOTTOM_LEFT) },
                        { anchor(ViewAnnotationAnchor.BOTTOM_RIGHT) },
                        { anchor(ViewAnnotationAnchor.TOP_LEFT) },
                        { anchor(ViewAnnotationAnchor.TOP_RIGHT) },
                    )
                },
            )
            .build()
    }

    override fun onUpdateAnchor(view: View, anchor: ViewAnnotationAnchorConfig) {
        val shapeView = view.findViewById<TextView>(NavR.id.shape) ?: return
        val etaView = view.findViewById<TextView>(NavR.id.eta) ?: return

        val bgRes = when (anchor.anchor) {
            ViewAnnotationAnchor.BOTTOM_LEFT -> NavR.drawable.mapbox_ic_route_callout_bottom_left
            ViewAnnotationAnchor.BOTTOM_RIGHT -> NavR.drawable.mapbox_ic_route_callout_bottom_right
            ViewAnnotationAnchor.TOP_LEFT -> NavR.drawable.mapbox_ic_route_callout_top_left
            ViewAnnotationAnchor.TOP_RIGHT -> NavR.drawable.mapbox_ic_route_callout_top_right
            else -> NavR.drawable.mapbox_ic_route_callout_bottom_left
        }

        val shadowRes = when (anchor.anchor) {
            ViewAnnotationAnchor.BOTTOM_LEFT -> NavR.drawable.mapbox_ic_route_callout_bottom_left_shadow
            ViewAnnotationAnchor.BOTTOM_RIGHT -> NavR.drawable.mapbox_ic_route_callout_bottom_right_shadow
            ViewAnnotationAnchor.TOP_LEFT -> NavR.drawable.mapbox_ic_route_callout_top_left_shadow
            ViewAnnotationAnchor.TOP_RIGHT -> NavR.drawable.mapbox_ic_route_callout_top_right_shadow
            else -> NavR.drawable.mapbox_ic_route_callout_bottom_left_shadow
        }

        etaView.setBackgroundResource(bgRes)
        shapeView.setBackgroundResource(shadowRes)
    }

    private fun findTollLabel(navigationRoute: NavigationRoute): String {
        val matchedItem = routeResults.find { it.platformRoute === navigationRoute }?.item
        return when {
            matchedItem == null -> getTollLabelFromRoute(navigationRoute)
            matchedItem.tollFee != null -> "¥${matchedItem.tollFee}"
            matchedItem.hasTolls -> "有料道路"
            else -> "一般道"
        }
    }

    private fun getTollLabelFromRoute(navigationRoute: NavigationRoute): String {
        val hasTolls = navigationRoute.directionsRoute.legs().orEmpty().any { leg ->
            leg.steps().orEmpty().any { step ->
                step.intersections().orEmpty().any { intersection ->
                    intersection.tollCollection() != null ||
                        intersection.classes().orEmpty().contains("toll")
                }
            }
        }
        return if (hasTolls) "有料道路" else "一般道"
    }

    companion object {
        private const val SELECTED_BG = 0xFF4285F4.toInt()
        private const val UNSELECTED_BG = 0xFFFFFFFF.toInt()
        private const val SELECTED_TEXT = 0xFFFFFFFF.toInt()
        private const val UNSELECTED_TEXT = 0xFF333333.toInt()
        private const val SHADOW_COLOR = 0x40000000
        private const val TEXT_SIZE_SP = 14f
    }
}
