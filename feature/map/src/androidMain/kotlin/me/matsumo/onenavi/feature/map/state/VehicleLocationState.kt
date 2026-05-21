package me.matsumo.onenavi.feature.map.state

import androidx.compose.runtime.Immutable
import me.matsumo.onenavi.core.model.RoutePoint
import me.matsumo.onenavi.core.navigation.newguidance.model.RouteMatchState

/**
 * 地図上に表示する自車位置の取得元。
 */
enum class VehicleLocationSource {
    /** 案内中 route geometry に投影した位置。 */
    ROUTE_SNAPPED,

    /** Navigation SDK の road-snapped location。 */
    SDK_ROAD_SNAPPED,

    /** road-snapped location が使えない場合の生 GPS 位置。 */
    RAW_GPS,
}

/**
 * 地図 UI が読む自車位置。
 *
 * @param location 地図に表示する自車位置
 * @param bearingDegrees 進行方向。取得できない場合は null
 * @param accuracyMeters 水平精度。取得できない場合は null
 * @param timestampMillis 位置情報の計測時刻
 * @param elapsedRealtimeNanos 位置情報の monotonic clock 時刻。取得できない場合は null
 * @param speedMps 自車速度。取得できない場合は null
 * @param routeProgressMeters route geometry 上の累積距離。route-snapped 位置でない場合は null
 * @param source 位置情報の取得元
 * @param routeMatchState 現在位置と案内 route の一致状態
 * @param projectionErrorMeters 生位置と route-snapped 位置の距離。計算できない場合は null
 */
@Immutable
data class VehicleLocationState(
    val location: RoutePoint,
    val bearingDegrees: Float?,
    val accuracyMeters: Float?,
    val timestampMillis: Long,
    val elapsedRealtimeNanos: Long?,
    val speedMps: Float?,
    val routeProgressMeters: Double?,
    val source: VehicleLocationSource,
    val routeMatchState: RouteMatchState = RouteMatchState.ON_ROUTE,
    val projectionErrorMeters: Double? = null,
)
