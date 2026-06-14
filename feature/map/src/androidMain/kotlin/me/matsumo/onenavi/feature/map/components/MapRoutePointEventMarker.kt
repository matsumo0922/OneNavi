package me.matsumo.onenavi.feature.map.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import me.matsumo.onenavi.core.model.RoutePointEventKind
import me.matsumo.onenavi.core.resource.Res
import me.matsumo.onenavi.core.resource.ic_sign_railroad_crossing
import me.matsumo.onenavi.core.resource.ic_sign_stop
import me.matsumo.onenavi.core.resource.ic_sign_traffic_light
import me.matsumo.onenavi.feature.map.components.callout.rememberMapComposeBitmapDescriptor
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

/**
 * ルート上の地点イベント marker を描画する。
 *
 * @param googleMap marker 描画先の GoogleMap
 * @param latitude marker の緯度
 * @param longitude marker の経度
 * @param kind 地点イベントの種別
 * @param isGuidanceTarget 現在の案内地点に紐付く marker かどうか
 * @param zIndex marker の zIndex
 */
@Composable
internal fun MapRoutePointEventMarker(
    googleMap: GoogleMap,
    latitude: Double,
    longitude: Double,
    kind: RoutePointEventKind,
    isGuidanceTarget: Boolean,
    zIndex: Float,
) {
    val icon = rememberRoutePointEventMarkerIcon(kind, isGuidanceTarget)

    DisposableEffect(googleMap, latitude, longitude, kind, isGuidanceTarget, zIndex, icon) {
        val marker = googleMap.addMarker(
            MarkerOptions()
                .position(LatLng(latitude, longitude))
                .anchor(0.5f, 0.5f)
                .icon(icon)
                .zIndex(zIndex),
        )
        onDispose { marker?.remove() }
    }
}

@Composable
private fun rememberRoutePointEventMarkerIcon(kind: RoutePointEventKind, isGuidanceTarget: Boolean): BitmapDescriptor {
    return rememberMapComposeBitmapDescriptor(
        kind,
        isGuidanceTarget,
        routePointEventMarkerSize(kind, isGuidanceTarget),
        routePointEventIconRenderSize(kind, isGuidanceTarget),
    ) {
        RoutePointEventMarkerIcon(
            kind = kind,
            isGuidanceTarget = isGuidanceTarget,
        )
    }
}

@Composable
private fun RoutePointEventMarkerIcon(
    kind: RoutePointEventKind,
    isGuidanceTarget: Boolean,
    modifier: Modifier = Modifier,
) {
    val markerSize = routePointEventMarkerSize(kind, isGuidanceTarget)
    val iconSize = routePointEventIconRenderSize(kind, isGuidanceTarget)
    val painter = painterResource(routePointEventSignResource(kind))

    Box(
        modifier = modifier.size(markerSize),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            modifier = Modifier
                .offset(y = RoutePointEventMarkerShadowOffsetY)
                .size(iconSize)
                .blur(RoutePointEventMarkerShadowBlur),
            painter = painter,
            contentDescription = null,
            colorFilter = ColorFilter.tint(RoutePointEventMarkerShadowColor),
        )

        Image(
            modifier = Modifier.size(iconSize),
            painter = painter,
            contentDescription = null,
        )
    }
}

private fun routePointEventMarkerSize(kind: RoutePointEventKind, isGuidanceTarget: Boolean): Dp {
    val baseSize = routePointEventBaseMarkerSize(kind)

    return baseSize * routePointEventGuidanceTargetScale(isGuidanceTarget)
}

private fun routePointEventBaseMarkerSize(kind: RoutePointEventKind): Dp = when (kind) {
    RoutePointEventKind.TRAFFIC_LIGHT -> RoutePointEventTrafficLightMarkerSize
    RoutePointEventKind.STOP_LINE,
    RoutePointEventKind.RAILWAY_CROSSING,
    -> RoutePointEventMarkerSize
}

private fun routePointEventIconSize(kind: RoutePointEventKind): Dp = when (kind) {
    RoutePointEventKind.TRAFFIC_LIGHT -> RoutePointEventTrafficLightIconSize
    RoutePointEventKind.STOP_LINE,
    RoutePointEventKind.RAILWAY_CROSSING,
    -> RoutePointEventIconSize
}

private fun routePointEventIconRenderSize(kind: RoutePointEventKind, isGuidanceTarget: Boolean): Dp {
    val baseSize = routePointEventIconSize(kind)

    return baseSize * routePointEventGuidanceTargetScale(isGuidanceTarget)
}

private fun routePointEventGuidanceTargetScale(isGuidanceTarget: Boolean): Float =
    if (isGuidanceTarget) RoutePointEventGuidanceTargetScale else RoutePointEventScale

private fun routePointEventSignResource(kind: RoutePointEventKind): DrawableResource = when (kind) {
    RoutePointEventKind.TRAFFIC_LIGHT -> Res.drawable.ic_sign_traffic_light
    RoutePointEventKind.STOP_LINE -> Res.drawable.ic_sign_stop
    RoutePointEventKind.RAILWAY_CROSSING -> Res.drawable.ic_sign_railroad_crossing
}

/** 地点イベント marker の標準描画領域サイズ。 */
private val RoutePointEventMarkerSize = 40.dp

/** 通過予定の信号機 marker の描画領域サイズ。 */
private val RoutePointEventTrafficLightMarkerSize = 38.dp

/** 地点イベント marker の標準標識アイコンサイズ。 */
private val RoutePointEventIconSize = 32.dp

/** 通過予定の信号機 marker の標識アイコンサイズ。 */
private val RoutePointEventTrafficLightIconSize = 30.dp

/** 地点イベント marker の標準拡大率。 */
private const val RoutePointEventScale = 1f

/** 現在の案内地点に紐付く地点イベント marker の拡大率。 */
private const val RoutePointEventGuidanceTargetScale = 1.8f

/** 地点イベント marker の影の縦方向 offset。 */
private val RoutePointEventMarkerShadowOffsetY = 1.5.dp

/** 地点イベント marker の影のぼかし量。 */
private val RoutePointEventMarkerShadowBlur = 2.dp

/** 地点イベント marker の影色。 */
private val RoutePointEventMarkerShadowColor = Color(0x55000000)
