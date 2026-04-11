package me.matsumo.onenavi.core.navigation.guidance

import androidx.compose.runtime.Stable

/**
 * 発話済み判定に使う案内イベントの一意キー。
 */
@Stable
data class GuidanceEventId(
    val routeId: String,
    val category: GuideCategory,
    val legIndex: Int,
    val stepIndex: Int,
    val geometryIndex: Int?,
    val distanceBucket: DistanceBucket?,
    val variant: String,
)

/**
 * TTS と UI に流す構造化案内イベント。
 */
sealed interface GuidanceEvent {
    val id: GuidanceEventId
    val priority: GuidancePriority
    val distanceMeters: Double?
}

/**
 * マニューバの方向。
 */
enum class Direction {
    LEFT,
    RIGHT,
    SLIGHT_LEFT,
    SLIGHT_RIGHT,
    SHARP_LEFT,
    SHARP_RIGHT,
    STRAIGHT,
    UTURN,
    UNKNOWN,
}

/**
 * ターン案内の発話タイミング。
 */
enum class TurnTiming {
    FAR,
    MIDDLE,
    SOON,
}

/**
 * 基本的な曲がり角案内イベント。
 */
@Stable
data class TurnGuideEvent(
    override val id: GuidanceEventId,
    override val priority: GuidancePriority,
    override val distanceMeters: Double,
    val direction: Direction,
    val timing: TurnTiming,
    val roadName: String?,
) : GuidanceEvent

/**
 * 近接した 2 つの曲がり角を連結して案内するイベント。
 */
@Stable
data class LinkedTurnGuideEvent(
    override val id: GuidanceEventId,
    override val priority: GuidancePriority,
    override val distanceMeters: Double,
    val firstDirection: Direction,
    val nextDirection: Direction,
) : GuidanceEvent

/**
 * 高速道路関連案内の種類。
 */
enum class HighwayGuideKind {
    ENTER,
    EXIT,
    FORK,
    MERGE,
    TOLL_GATE,
    ROAD_KIND_CHANGED,
}

/**
 * 高速道路、ランプ、分岐、料金所に関する案内イベント。
 */
@Stable
data class HighwayGuideEvent(
    override val id: GuidanceEventId,
    override val priority: GuidancePriority,
    override val distanceMeters: Double?,
    val kind: HighwayGuideKind,
    val direction: Direction,
    val name: String?,
) : GuidanceEvent

/**
 * 推奨車線を案内するイベント。
 */
@Stable
data class LaneGuideEvent(
    override val id: GuidanceEventId,
    override val priority: GuidancePriority,
    override val distanceMeters: Double,
    val laneCount: Int,
    val validLaneIndices: List<Int>,
) : GuidanceEvent

/**
 * オフルートまたは新ルート適用を案内するイベント。
 */
@Stable
data class RerouteGuideEvent(
    override val id: GuidanceEventId,
    override val priority: GuidancePriority,
    override val distanceMeters: Double? = null,
    val offRoute: Boolean,
) : GuidanceEvent

/**
 * 安全注意案内の種類。
 */
enum class SafetyGuideKind {
    RAILWAY_CROSSING,
    STOP_SIGN,
    CROSSWALK,
}

/**
 * 踏切や一時停止などの安全注意を案内するイベント。
 */
@Stable
data class SafetyGuideEvent(
    override val id: GuidanceEventId,
    override val priority: GuidancePriority,
    override val distanceMeters: Double,
    val kind: SafetyGuideKind,
) : GuidanceEvent

/**
 * 次の案内地点まで道なりで進むことを案内するイベント。
 */
@Stable
data class AlongRoadGuideEvent(
    override val id: GuidanceEventId,
    override val priority: GuidancePriority,
    override val distanceMeters: Double,
    val bucket: DistanceBucket,
) : GuidanceEvent

/**
 * 経由地または目的地への到着を案内するイベント。
 */
@Stable
data class WaypointGuideEvent(
    override val id: GuidanceEventId,
    override val priority: GuidancePriority,
    override val distanceMeters: Double? = null,
    val kind: DestinationKind,
    val finalDestination: Boolean,
) : GuidanceEvent

/**
 * ナビゲーションセッション状態案内の種類。
 */
enum class SessionGuideKind {
    START,
    STOP,
    PAUSE,
    RESUME,
    TIMEOUT_WARNING,
    TIMEOUT,
}

/**
 * ナビゲーションセッションの開始、終了、再開、タイムアウトを案内するイベント。
 */
@Stable
data class SessionGuideEvent(
    override val id: GuidanceEventId,
    override val priority: GuidancePriority,
    override val distanceMeters: Double? = null,
    val kind: SessionGuideKind,
) : GuidanceEvent
