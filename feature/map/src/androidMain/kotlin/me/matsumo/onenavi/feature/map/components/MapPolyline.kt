package me.matsumo.onenavi.feature.map.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import me.matsumo.onenavi.core.model.CongestionSegment
import me.matsumo.onenavi.core.model.CongestionSeverity
import me.matsumo.onenavi.core.model.RoadClassSegment
import me.matsumo.onenavi.core.model.RoutePoint
import me.matsumo.onenavi.core.ui.theme.RouteColors

/**
 * ルート Polyline の表示スタイル。
 *
 * Google マップアプリ準拠で「枠線（外側）+ 本体（内側）」の 2 本構成で描画する。
 * 選択中ルートは濃い色 + 最前面、非選択ルートは暗めの灰青 + 背面で表示する。
 * 道路種別 (`roadClassSegments`) が渡された区間は [borderColor] / [bodyColor] ではなく
 * 種別ごとの色で塗り分ける。
 * 渋滞区間 (`congestionSegments`) は本体の上に赤 / オレンジのオーバーレイを重ねるため
 * 枠線の濃色はそのまま透けて見える。
 *
 * 線幅は dp で保持し、`MapScreenMapCanvasLayer` が供給する地図描画 density 空間（VirtualDisplay では
 * 焼付 density に揃えた density）で px へ換算する。`Polyline.width` は screen px 指定で GoogleMap の描画
 * density 空間に乗るため、地図タイルや marker と同じ density 空間へ揃えないと見かけの太さがずれる。
 *
 * @param borderColor 枠線（外側）の色。道路種別で塗り分ける場合は使われない。
 * @param bodyColor 本体（内側）の色。道路種別で塗り分ける場合は使われない。
 * @param bodyWidthDp 本体の線幅 (dp)
 * @param borderWidthDp 枠線の線幅 (dp)
 * @param zIndex 描画順。値が大きいほど前面に描画される
 */
internal enum class MapPolylineStyle(
    val borderColor: Color,
    val bodyColor: Color,
    val bodyWidthDp: Float,
    val borderWidthDp: Float,
    val zIndex: Float,
) {
    /** 選択中ルート: 濃い青の枠線 + 最前面に描画。道路種別があれば種別ごとに塗り分ける。 */
    Selected(
        borderColor = Color(0xFF1A56C7),
        bodyColor = Color(0xFF4DA6F7),
        bodyWidthDp = 6.4f,
        borderWidthDp = 9.6f,
        zIndex = 10f,
    ),

    /** 非選択ルート: 彩度を落とした灰青で背面に描画。 */
    Unselected(
        borderColor = Color(0xFF59698C),
        bodyColor = Color(0xFF9DACC8),
        bodyWidthDp = 4.8f,
        borderWidthDp = 7.2f,
        zIndex = 0f,
    ),
}

/**
 * 渋滞オーバーレイ本体色。NORMAL / UNKNOWN は塗り替えない（null を返す）。
 * 枠線（border）はそのまま下に残るので、本体だけ赤 / オレンジで描き換える。
 */
private fun congestionBodyColorOf(severity: CongestionSeverity): Color? = when (severity) {
    CongestionSeverity.TRAFFIC_JAM -> Color(0xFFE53935)
    CongestionSeverity.SLOW -> Color(0xFFFB8C00)
    CongestionSeverity.NORMAL, CongestionSeverity.UNKNOWN -> null
}

private const val ROUTE_BODY_Z_OFFSET = 0.5f
private const val CONGESTION_OVERLAY_Z_OFFSET = 2f

@Composable
internal fun MapPolyline(
    googleMap: GoogleMap,
    points: ImmutableList<RoutePoint>,
    style: MapPolylineStyle = MapPolylineStyle.Selected,
    roadClassSegments: ImmutableList<RoadClassSegment> = persistentListOf(),
    congestionSegments: ImmutableList<CongestionSegment> = persistentListOf(),
) {
    val density = LocalDensity.current.density
    val bodyWidthPx = style.bodyWidthDp * density
    val borderWidthPx = style.borderWidthDp * density

    DisposableEffect(googleMap, points, style, roadClassSegments, congestionSegments, density) {
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
                    .width(borderWidthPx)
                    .zIndex(style.zIndex),
            )
            polylines += googleMap.addPolyline(
                PolylineOptions()
                    .addAll(latLngPoints)
                    .color(style.bodyColor.toArgb())
                    .width(bodyWidthPx)
                    .zIndex(style.zIndex + ROUTE_BODY_Z_OFFSET),
            )
        } else {
            // 道路種別あり: 枠線を全区間ぶん描いてから、その上に本体を全区間ぶん描く
            // （隣接区間の枠線同士は端点を共有するので継ぎ目は目立たない）。
            for (segment in usableSegments) {
                polylines += googleMap.addPolyline(
                    PolylineOptions()
                        .addAll(latLngPoints.subList(segment.startPointIndex, segment.endPointIndex + 1))
                        .color(RouteColors.polyline(segment.roadClass).border.toArgb())
                        .width(borderWidthPx)
                        .zIndex(style.zIndex),
                )
            }
            for (segment in usableSegments) {
                polylines += googleMap.addPolyline(
                    PolylineOptions()
                        .addAll(latLngPoints.subList(segment.startPointIndex, segment.endPointIndex + 1))
                        .color(RouteColors.polyline(segment.roadClass).body.toArgb())
                        .width(bodyWidthPx)
                        .zIndex(style.zIndex + ROUTE_BODY_Z_OFFSET),
                )
            }
        }

        // 渋滞オーバーレイ: 本体と同じ太さで body の上に描き、border はそのまま下に透ける。
        // NORMAL / UNKNOWN は色を変えない（congestionBodyColorOf が null を返す）。
        for (segment in congestionSegments) {
            val overlayColor = congestionBodyColorOf(segment.severity) ?: continue
            if (latLngPoints.isEmpty()) continue

            var startIndex = segment.startPolylinePointIndex.coerceIn(0, latLngPoints.lastIndex)
            var endIndex = segment.endPolylinePointIndex.coerceIn(startIndex, latLngPoints.lastIndex)

            // 単点 segment (start==end) は polyline が 1 点しかなく Polygon API で描画できないため、
            // 隣の polyline 点まで広げて最低 2 点を確保する（外環道 attr_ex の短い区間で発生）。
            if (endIndex == startIndex) {
                when {
                    endIndex < latLngPoints.lastIndex -> endIndex += 1
                    startIndex > 0 -> startIndex -= 1
                    else -> continue
                }
            }

            polylines += googleMap.addPolyline(
                PolylineOptions()
                    .addAll(latLngPoints.subList(startIndex, endIndex + 1))
                    .color(overlayColor.toArgb())
                    .width(bodyWidthPx)
                    .zIndex(style.zIndex + CONGESTION_OVERLAY_Z_OFFSET),
            )
        }

        onDispose {
            for (polyline in polylines) {
                polyline.remove()
            }
        }
    }
}
