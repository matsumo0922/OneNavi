package me.matsumo.onenavi.feature.map.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.PolylineOptions
import kotlinx.collections.immutable.ImmutableList
import me.matsumo.onenavi.core.model.RoutePoint

/**
 * ルート Polyline の表示スタイル。
 *
 * Google マップアプリ準拠で「濃い青の枠線 + 水色の本体」の 2 本構成で描画する。
 * 選択中ルートは濃い色 + 最前面、非選択ルートは薄い色 + 背面で表示する。
 *
 * @param borderColor 枠線（外側）の色
 * @param bodyColor 本体（内側）の色
 * @param bodyWidthPx 本体の線幅 (px)
 * @param borderWidthPx 枠線の線幅 (px)
 * @param zIndex 描画順。値が大きいほど前面に描画される
 */
internal enum class MapPolylineStyle(
    val borderColor: Color,
    val bodyColor: Color,
    val bodyWidthPx: Float,
    val borderWidthPx: Float,
    val zIndex: Float,
) {
    /** 選択中ルート: 濃い青の枠線 + 水色の本体を最前面に描画。 */
    Selected(
        borderColor = Color(0xFF1A56C7),
        bodyColor = Color(0xFF4DA6F7),
        bodyWidthPx = 16f,
        borderWidthPx = 24f,
        zIndex = 10f,
    ),

    /** 非選択ルート: 彩度を落とした暗めの灰青で背面に描画。 */
    Unselected(
        borderColor = Color(0xFF4A5A78),
        bodyColor = Color(0xFF8A99B5),
        bodyWidthPx = 12f,
        borderWidthPx = 18f,
        zIndex = 0f,
    ),
}

@Composable
internal fun MapPolyline(
    googleMap: GoogleMap,
    points: ImmutableList<RoutePoint>,
    style: MapPolylineStyle = MapPolylineStyle.Selected,
) {
    DisposableEffect(googleMap, points, style) {
        val latLngPoints = points.map { LatLng(it.latitude, it.longitude) }
        val borderPolyline = googleMap.addPolyline(
            PolylineOptions()
                .addAll(latLngPoints)
                .color(style.borderColor.toArgb())
                .width(style.borderWidthPx)
                .zIndex(style.zIndex),
        )
        val bodyPolyline = googleMap.addPolyline(
            PolylineOptions()
                .addAll(latLngPoints)
                .color(style.bodyColor.toArgb())
                .width(style.bodyWidthPx)
                .zIndex(style.zIndex + 0.5f),
        )
        onDispose {
            borderPolyline.remove()
            bodyPolyline.remove()
        }
    }
}
