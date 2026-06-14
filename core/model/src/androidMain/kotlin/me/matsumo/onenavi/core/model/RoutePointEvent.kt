package me.matsumo.onenavi.core.model

import androidx.compose.runtime.Immutable

/**
 * ルート上に表示する地点イベントの種別。
 */
enum class RoutePointEventKind {
    /** 信号機。 */
    TRAFFIC_LIGHT,

    /** 一時停止。 */
    STOP_LINE,

    /** 踏切。 */
    RAILWAY_CROSSING,
}

/**
 * ルート polyline 上に表示する地点イベント。
 *
 * @param kind 地点イベントの種別
 * @param location 地図上に marker を置く座標
 * @param distanceFromStartMeters ルート始点からの累積距離（m）
 * @param polylinePointIndex [location] に最も近い [RouteDetail.geometry] の index
 */
@Immutable
data class RoutePointEvent(
    val kind: RoutePointEventKind,
    val location: RoutePoint,
    val distanceFromStartMeters: Double,
    val polylinePointIndex: Int,
)
