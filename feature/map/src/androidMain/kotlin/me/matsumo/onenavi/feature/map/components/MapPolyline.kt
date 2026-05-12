package me.matsumo.onenavi.feature.map.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import me.matsumo.onenavi.core.model.RoadClass
import me.matsumo.onenavi.core.model.RoadClassSegment
import me.matsumo.onenavi.core.model.RoutePoint

/**
 * ルート Polyline の表示スタイル。
 *
 * Google マップアプリ準拠で「枠線（外側）+ 本体（内側）」の 2 本構成で描画する。
 * 選択中ルートは濃い色 + 最前面、非選択ルートは暗めの灰青 + 背面で表示する。
 * 道路種別 (`roadClassSegments`) が渡された区間は [borderColor] / [bodyColor] ではなく
 * 種別ごとの色で塗り分ける。
 *
 * @param borderColor 枠線（外側）の色。道路種別で塗り分ける場合は使われない。
 * @param bodyColor 本体（内側）の色。道路種別で塗り分ける場合は使われない。
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
    /** 選択中ルート: 濃い青の枠線 + 最前面に描画。道路種別があれば種別ごとに塗り分ける。 */
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

/** 道路種別ごとの枠線（外側）色。高速＝濃い青、一般道＝濃い緑。 */
private fun borderColorOf(roadClass: RoadClass): Color = when (roadClass) {
    RoadClass.HIGHWAY -> Color(0xFF1A56C7)
    RoadClass.ORDINARY -> Color(0xFF146C34)
}

/** 道路種別ごとの本体（内側）色。高速＝明るい青、一般道＝明るい黄緑。 */
private fun bodyColorOf(roadClass: RoadClass): Color = when (roadClass) {
    RoadClass.HIGHWAY -> Color(0xFF5AB7FF)
    RoadClass.ORDINARY -> Color(0xFF8BD24A)
}

@Composable
internal fun MapPolyline(
    googleMap: GoogleMap,
    points: ImmutableList<RoutePoint>,
    style: MapPolylineStyle = MapPolylineStyle.Selected,
    roadClassSegments: ImmutableList<RoadClassSegment> = persistentListOf(),
) {
    DisposableEffect(googleMap, points, style, roadClassSegments) {
        val latLngPoints = points.map { LatLng(it.latitude, it.longitude) }
        val polylines = mutableListOf<Polyline>()

        val usableSegments = roadClassSegments.filter { segment ->
            segment.startPointIndex >= 0 &&
                segment.startPointIndex < segment.endPointIndex &&
                segment.endPointIndex <= latLngPoints.lastIndex
        }

        if (usableSegments.isEmpty()) {
            // 道路種別の情報なし: ルート全体を 1 組（枠線 + 本体）の単色で描く。
            polylines += googleMap.addPolyline(
                PolylineOptions()
                    .addAll(latLngPoints)
                    .color(style.borderColor.toArgb())
                    .width(style.borderWidthPx)
                    .zIndex(style.zIndex),
            )
            polylines += googleMap.addPolyline(
                PolylineOptions()
                    .addAll(latLngPoints)
                    .color(style.bodyColor.toArgb())
                    .width(style.bodyWidthPx)
                    .zIndex(style.zIndex + 0.5f),
            )
        } else {
            // 道路種別あり: 枠線を全区間ぶん描いてから、その上に本体を全区間ぶん描く
            // （隣接区間の枠線同士は端点を共有するので継ぎ目は目立たない）。
            for (segment in usableSegments) {
                polylines += googleMap.addPolyline(
                    PolylineOptions()
                        .addAll(latLngPoints.subList(segment.startPointIndex, segment.endPointIndex + 1))
                        .color(borderColorOf(segment.roadClass).toArgb())
                        .width(style.borderWidthPx)
                        .zIndex(style.zIndex),
                )
            }
            for (segment in usableSegments) {
                polylines += googleMap.addPolyline(
                    PolylineOptions()
                        .addAll(latLngPoints.subList(segment.startPointIndex, segment.endPointIndex + 1))
                        .color(bodyColorOf(segment.roadClass).toArgb())
                        .width(style.bodyWidthPx)
                        .zIndex(style.zIndex + 0.5f),
                )
            }
        }

        onDispose {
            for (polyline in polylines) {
                polyline.remove()
            }
        }
    }
}
