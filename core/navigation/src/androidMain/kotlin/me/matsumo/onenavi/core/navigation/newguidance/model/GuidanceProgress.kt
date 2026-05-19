package me.matsumo.onenavi.core.navigation.newguidance.model

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import me.matsumo.onenavi.core.model.RoadClass
import me.matsumo.onenavi.core.model.RoutePoint

/**
 * 案内中 UI が読む進捗スナップショット。
 *
 * @param distanceRemainingMeters 目的地までの残距離
 * @param durationRemainingSeconds 目的地までの残所要時間
 * @param etaEpochMillis 到着予想時刻
 * @param traveledMeters 走行済み距離
 * @param snappedLocation ルート上に snap した自車位置
 * @param bearingDegrees 自車マーカーの向き
 * @param nextManeuver 次の案内。案内対象が無い場合は null
 * @param followupManeuver 次の次の案内。無い場合は null
 * @param lanes レーンガイダンス
 * @param directionSign 方面看板
 * @param highwayPanel IC / JCT / SA / PA パネル
 * @param currentRoadName 走行中道路名
 * @param currentRoadClass 走行中道路種別
 * @param currentSpeedLimitKmh 現在区間の制限速度
 */
@Immutable
data class GuidanceProgress(
    val distanceRemainingMeters: Int,
    val durationRemainingSeconds: Int,
    val etaEpochMillis: Long,
    val traveledMeters: Int,
    val snappedLocation: RoutePoint,
    val bearingDegrees: Float,
    val nextManeuver: GuidanceManeuverInfo?,
    val followupManeuver: GuidanceManeuverInfo?,
    val lanes: ImmutableList<LaneGuidance>,
    val directionSign: DirectionSign?,
    val highwayPanel: HighwayPanel?,
    val currentRoadName: String?,
    val currentRoadClass: RoadClass,
    val currentSpeedLimitKmh: Int?,
)
