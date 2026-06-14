package me.matsumo.onenavi.feature.map.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
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
    Image(
        modifier = modifier.size(RoutePointEventMarkerIconSize),
        painter = painterResource(routePointEventSignResource(kind)),
        contentDescription = null,
    )
}

private fun routePointEventSignResource(kind: RoutePointEventKind): DrawableResource = when (kind) {
    RoutePointEventKind.TRAFFIC_LIGHT -> Res.drawable.ic_sign_traffic_light
    RoutePointEventKind.STOP_LINE -> Res.drawable.ic_sign_stop
    RoutePointEventKind.RAILWAY_CROSSING -> Res.drawable.ic_sign_railroad_crossing
}

/** 地点イベント marker の標識アイコンサイズ。 */
private val RoutePointEventMarkerIconSize = 34.dp
