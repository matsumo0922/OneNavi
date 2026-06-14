package me.matsumo.onenavi.core.model

import androidx.compose.runtime.Immutable

/**
 * ルート上インシデントの種別。
 */
enum class RouteIncidentMarkerCategory {
    /** 事故。 */
    Accident,

    /** 規制。 */
    Regulation,
}

/**
 * ルート上の規制・事故地点を表す中立モデル。
 *
 * @param category インシデントの種別（事故 / 規制）
 * @param coord 地図上に marker を置く座標
 * @param displayText 表示用テキスト
 * @param distanceFromStartMeters ルート始点からの累積距離（m）
 * @param polylinePointIndex [RouteDetail.geometry] 上の最近傍 index
 * @param placeName 地点名。取れなければ null
 * @param roadNumbering 路線番号（例: "E51"）。取れなければ null
 */
@Immutable
data class RouteIncidentMarker(
    val category: RouteIncidentMarkerCategory,
    val coord: RoutePoint,
    val displayText: String,
    val distanceFromStartMeters: Double,
    val polylinePointIndex: Int,
    val placeName: String?,
    val roadNumbering: String?,
)
