package me.matsumo.onenavi.core.navigation.newguidance.model

import androidx.compose.runtime.Immutable
import me.matsumo.onenavi.core.model.RoadClass
import me.matsumo.onenavi.core.model.RoutePoint

/**
 * 案内中 UI が読む進捗スナップショット (L2 progress 層)。
 *
 * tick ごとの現在地スカラと走行状態だけを持ち、案内イベント由来の表示 (次案内・パネル行・レーン)
 * は複製しない。それらは presentation 層の
 * [me.matsumo.onenavi.core.navigation.newguidance.presentation.GuidancePresentation] が射影する。
 *
 * @param distanceRemainingMeters 目的地までの残距離
 * @param durationRemainingSeconds 目的地までの残所要時間
 * @param etaEpochMillis 到着予想時刻
 * @param traveledMeters 走行済み距離
 * @param elapsedSeconds 案内開始からの経過時間 (秒)
 * @param currentCumulativeMeters route geometry 上の現在累積距離
 * @param snappedLocation ルート上に snap した自車位置
 * @param bearingDegrees 自車マーカーの向き
 * @param observedLocation 位置情報プロバイダから観測した実位置。取得できない場合は null
 * @param observedBearingDegrees 観測位置の進行方向。取得できない場合は null
 * @param observedAccuracyMeters 観測位置の水平精度。取得できない場合は null
 * @param locationTimestampMillis 位置情報の計測時刻
 * @param locationElapsedRealtimeNanos 位置情報の monotonic clock 時刻。取得できない場合は null
 * @param vehicleSpeedMps 自車速度。取得できない場合は null
 * @param currentRoadName 走行中道路名
 * @param currentRoadClass 走行中道路種別
 * @param currentSpeedLimitKmh 現在区間の制限速度
 * @param routeMatchState 現在位置と案内 route の一致状態
 * @param positionSource 位置が実測・推定・初期値のいずれかを表す種別
 * @param projectionErrorMeters 生位置と route-snapped 位置の距離。計算できない場合は null
 */
@Immutable
data class GuidanceProgress(
    val distanceRemainingMeters: Int,
    val durationRemainingSeconds: Int,
    val etaEpochMillis: Long,
    val traveledMeters: Int,
    val elapsedSeconds: Int,
    val currentCumulativeMeters: Double,
    val snappedLocation: RoutePoint,
    val bearingDegrees: Float,
    val observedLocation: RoutePoint?,
    val observedBearingDegrees: Float?,
    val observedAccuracyMeters: Float?,
    val locationTimestampMillis: Long,
    val locationElapsedRealtimeNanos: Long?,
    val vehicleSpeedMps: Float?,
    val currentRoadName: String?,
    val currentRoadClass: RoadClass,
    val currentSpeedLimitKmh: Int?,
    val routeMatchState: RouteMatchState,
    val positionSource: VehiclePositionSource,
    val projectionErrorMeters: Double?,
)
