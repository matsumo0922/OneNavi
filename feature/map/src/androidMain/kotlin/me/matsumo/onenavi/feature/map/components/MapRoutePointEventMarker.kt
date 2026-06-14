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
import androidx.compose.ui.draw.scale
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
 * @param zIndex marker の zIndex
 */
@Composable
internal fun MapRoutePointEventMarker(
    googleMap: GoogleMap,
    latitude: Double,
    longitude: Double,
    kind: RoutePointEventKind,
    zIndex: Float,
) {
    val icon = rememberRoutePointEventMarkerIcon(kind)

    DisposableEffect(googleMap, latitude, longitude, kind, zIndex, icon) {
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
private fun rememberRoutePointEventMarkerIcon(kind: RoutePointEventKind): BitmapDescriptor {
    return rememberMapComposeBitmapDescriptor(kind) {
        RoutePointEventMarkerIcon(
            kind = kind,
        )
    }
}

@Composable
private fun RoutePointEventMarkerIcon(
    kind: RoutePointEventKind,
    modifier: Modifier = Modifier,
) {
    val markerSize = routePointEventMarkerSize(kind)
    val iconSize = routePointEventIconSize(kind)
    val iconScale = routePointEventIconScale(kind)
    val painter = painterResource(routePointEventSignResource(kind))

    Box(
        modifier = modifier.size(markerSize),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            modifier = Modifier
                .offset(y = RoutePointEventMarkerShadowOffsetY)
                .size(iconSize)
                .scale(iconScale)
                .blur(RoutePointEventMarkerShadowBlur),
            painter = painter,
            contentDescription = null,
            colorFilter = ColorFilter.tint(RoutePointEventMarkerShadowColor),
        )

        Image(
            modifier = Modifier
                .size(iconSize)
                .scale(iconScale),
            painter = painter,
            contentDescription = null,
        )
    }
}

private fun routePointEventMarkerSize(kind: RoutePointEventKind): Dp = when (kind) {
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

private fun routePointEventIconScale(kind: RoutePointEventKind): Float = when (kind) {
    RoutePointEventKind.TRAFFIC_LIGHT -> RoutePointEventTrafficLightIconScale
    RoutePointEventKind.STOP_LINE,
    RoutePointEventKind.RAILWAY_CROSSING,
    -> RoutePointEventIconScale
}

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

/** 地点イベント marker の標準標識アイコン拡大率。 */
private const val RoutePointEventIconScale = 1f

/** 通過予定の信号機 marker の標識アイコン拡大率。 */
private const val RoutePointEventTrafficLightIconScale = 1.25f

/** 地点イベント marker の影の縦方向 offset。 */
private val RoutePointEventMarkerShadowOffsetY = 1.5.dp

/** 地点イベント marker の影のぼかし量。 */
private val RoutePointEventMarkerShadowBlur = 2.dp

/** 地点イベント marker の影色。 */
private val RoutePointEventMarkerShadowColor = Color(0x55000000)
