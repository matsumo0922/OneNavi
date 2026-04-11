package me.matsumo.onenavi.core.navigation.guidance

data class GuidanceEventId(
    val routeId: String,
    val category: GuideCategory,
    val legIndex: Int,
    val stepIndex: Int,
    val geometryIndex: Int?,
    val distanceBucket: DistanceBucket?,
    val variant: String,
)

sealed interface GuidanceEvent {
    val id: GuidanceEventId
    val priority: GuidancePriority
    val distanceMeters: Double?
}

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

enum class TurnTiming {
    FAR,
    MIDDLE,
    SOON,
}

data class TurnGuideEvent(
    override val id: GuidanceEventId,
    override val priority: GuidancePriority,
    override val distanceMeters: Double,
    val direction: Direction,
    val timing: TurnTiming,
    val roadName: String?,
) : GuidanceEvent

data class LinkedTurnGuideEvent(
    override val id: GuidanceEventId,
    override val priority: GuidancePriority,
    override val distanceMeters: Double,
    val firstDirection: Direction,
    val nextDirection: Direction,
) : GuidanceEvent

enum class HighwayGuideKind {
    ENTER,
    EXIT,
    FORK,
    MERGE,
    TOLL_GATE,
    ROAD_KIND_CHANGED,
}

data class HighwayGuideEvent(
    override val id: GuidanceEventId,
    override val priority: GuidancePriority,
    override val distanceMeters: Double?,
    val kind: HighwayGuideKind,
    val direction: Direction,
    val name: String?,
) : GuidanceEvent

data class LaneGuideEvent(
    override val id: GuidanceEventId,
    override val priority: GuidancePriority,
    override val distanceMeters: Double,
    val laneCount: Int,
    val validLaneIndices: List<Int>,
) : GuidanceEvent

data class RerouteGuideEvent(
    override val id: GuidanceEventId,
    override val priority: GuidancePriority,
    override val distanceMeters: Double? = null,
    val offRoute: Boolean,
) : GuidanceEvent

enum class SafetyGuideKind {
    RAILWAY_CROSSING,
    STOP_SIGN,
    CROSSWALK,
}

data class SafetyGuideEvent(
    override val id: GuidanceEventId,
    override val priority: GuidancePriority,
    override val distanceMeters: Double,
    val kind: SafetyGuideKind,
) : GuidanceEvent

data class AlongRoadGuideEvent(
    override val id: GuidanceEventId,
    override val priority: GuidancePriority,
    override val distanceMeters: Double,
    val bucket: DistanceBucket,
) : GuidanceEvent

data class WaypointGuideEvent(
    override val id: GuidanceEventId,
    override val priority: GuidancePriority,
    override val distanceMeters: Double? = null,
    val kind: DestinationKind,
    val finalDestination: Boolean,
) : GuidanceEvent

enum class SessionGuideKind {
    START,
    STOP,
    PAUSE,
    RESUME,
    TIMEOUT_WARNING,
    TIMEOUT,
}

data class SessionGuideEvent(
    override val id: GuidanceEventId,
    override val priority: GuidancePriority,
    override val distanceMeters: Double? = null,
    val kind: SessionGuideKind,
) : GuidanceEvent
