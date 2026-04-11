package me.matsumo.onenavi.core.navigation.guidance

import com.mapbox.api.directions.v5.models.LegStep
import com.mapbox.navigation.base.trip.model.RouteProgress
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class GuidanceCoordinator(
    private val speechHistory: GuidanceSpeechHistory,
) {

    private val _events = MutableSharedFlow<GuidanceEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<GuidanceEvent> = _events.asSharedFlow()

    private var lastRouteId: String? = null
    private var lastStepKey: StepKey? = null

    fun onRouteProgress(context: GuidanceContext): List<GuidanceEvent> {
        if (lastRouteId != context.routeId) {
            lastRouteId = context.routeId
            lastStepKey = null
            speechHistory.resetForNewRoute(context.routeId)
        }

        val events = buildList {
            addAll(extractRoadKindChange(context))
            addAll(extractHighwayEvents(context))
            addAll(extractLaneEvents(context))
            addAll(extractSafetyEvents(context))
            addAll(extractTurnEvents(context))
            addAll(extractAlongRoadEvents(context))
        }.filterNot { speechHistory.hasSpoken(it.id) }

        events.forEach(_events::tryEmit)
        lastStepKey = StepKey(context.currentLegIndex, context.currentStepIndex)

        return events
    }

    fun onOffRoute(isOffRoute: Boolean): GuidanceEvent? {
        if (!isOffRoute) return null
        val event = RerouteGuideEvent(
            id = GuidanceEventId(
                routeId = lastRouteId.orEmpty(),
                category = GuideCategory.REROUTE,
                legIndex = -1,
                stepIndex = -1,
                geometryIndex = null,
                distanceBucket = null,
                variant = "off-route",
            ),
            priority = GuidancePriority.CRITICAL,
            offRoute = true,
        )
        _events.tryEmit(event)
        return event
    }

    fun onRouteChanged(routeId: String): GuidanceEvent? {
        if (routeId == lastRouteId) return null
        lastRouteId = routeId
        speechHistory.resetForNewRoute(routeId)

        val event = RerouteGuideEvent(
            id = GuidanceEventId(
                routeId = routeId,
                category = GuideCategory.REROUTE,
                legIndex = -1,
                stepIndex = -1,
                geometryIndex = null,
                distanceBucket = null,
                variant = "route-changed",
            ),
            priority = GuidancePriority.CRITICAL,
            offRoute = false,
        )
        _events.tryEmit(event)
        return event
    }

    fun onWaypointArrival(
        routeProgress: RouteProgress,
        kind: DestinationKind,
        finalDestination: Boolean,
    ): GuidanceEvent {
        val currentLegProgress = routeProgress.currentLegProgress
        val currentStepProgress = currentLegProgress?.currentStepProgress
        val event = WaypointGuideEvent(
            id = GuidanceEventId(
                routeId = routeProgress.navigationRoute.id,
                category = GuideCategory.WAYPOINT,
                legIndex = currentLegProgress?.legIndex ?: 0,
                stepIndex = currentStepProgress?.stepIndex ?: 0,
                geometryIndex = null,
                distanceBucket = null,
                variant = if (finalDestination) "final" else "waypoint",
            ),
            priority = GuidancePriority.CRITICAL,
            kind = kind,
            finalDestination = finalDestination,
        )
        _events.tryEmit(event)
        return event
    }

    fun onSessionEvent(
        routeId: String,
        kind: SessionGuideKind,
    ): GuidanceEvent {
        val event = SessionGuideEvent(
            id = GuidanceEventId(
                routeId = routeId,
                category = GuideCategory.SESSION,
                legIndex = -1,
                stepIndex = -1,
                geometryIndex = null,
                distanceBucket = null,
                variant = kind.name,
            ),
            priority = if (kind == SessionGuideKind.START || kind == SessionGuideKind.RESUME) {
                GuidancePriority.NORMAL
            } else {
                GuidancePriority.CRITICAL
            },
            kind = kind,
        )
        _events.tryEmit(event)
        return event
    }

    fun reset() {
        lastRouteId = null
        lastStepKey = null
        speechHistory.clear()
    }

    private fun extractTurnEvents(context: GuidanceContext): List<GuidanceEvent> {
        val step = context.currentStep ?: return emptyList()
        val maneuverType = step.maneuver().type().orEmpty()
        if (maneuverType in HIGHWAY_MANEUVER_TYPES || maneuverType in IGNORED_MANEUVER_TYPES) {
            return emptyList()
        }

        val distance = context.routeProgress.currentLegProgress
            ?.currentStepProgress
            ?.distanceRemaining
            ?.toDouble() ?: return emptyList()
        val bucket = triggerBucketForTurn(distance, context.currentRoadKind) ?: return emptyList()
        val direction = step.direction()
        val linkedEvent = buildLinkedTurnEvent(context, direction, bucket)
        if (linkedEvent != null) {
            return listOf(linkedEvent)
        }

        return listOf(
            TurnGuideEvent(
                id = context.eventId(GuideCategory.TURN, bucket, "turn"),
                priority = if (bucket == DistanceBucket.M50) GuidancePriority.HIGH else GuidancePriority.NORMAL,
                distanceMeters = distance,
                direction = direction,
                timing = timingFor(bucket),
                roadName = step.name(),
            ),
        )
    }

    private fun buildLinkedTurnEvent(
        context: GuidanceContext,
        currentDirection: Direction,
        bucket: DistanceBucket,
    ): LinkedTurnGuideEvent? {
        if (bucket != DistanceBucket.M50 && bucket != DistanceBucket.M100) return null

        val nextStepContext = context.upcomingSteps
            .firstOrNull { it.stepIndex != context.currentStepIndex || it.legIndex != context.currentLegIndex }
            ?: return null
        if (nextStepContext.distanceFromCurrentMeters > LINKED_TURN_MAX_DISTANCE_METERS) return null

        val nextManeuverType = nextStepContext.step.maneuver().type().orEmpty()
        if (nextManeuverType in IGNORED_MANEUVER_TYPES) return null

        return LinkedTurnGuideEvent(
            id = context.eventId(GuideCategory.TURN, bucket, "linked-${nextStepContext.legIndex}-${nextStepContext.stepIndex}"),
            priority = GuidancePriority.HIGH,
            distanceMeters = context.routeProgress.currentLegProgress
                ?.currentStepProgress
                ?.distanceRemaining
                ?.toDouble() ?: return null,
            firstDirection = currentDirection,
            nextDirection = nextStepContext.step.direction(),
        )
    }

    private fun extractHighwayEvents(context: GuidanceContext): List<GuidanceEvent> {
        val step = context.currentStep ?: return emptyList()
        val maneuverType = step.maneuver().type().orEmpty()
        val distance = context.routeProgress.currentLegProgress
            ?.currentStepProgress
            ?.distanceRemaining
            ?.toDouble()
        val maneuverBucket = distance?.let { triggerBucketForTurn(it, RoadKind.HIGHWAY) }

        val highwayEvent = maneuverBucket?.let { bucket ->
            when (maneuverType) {
                "on ramp" -> HighwayGuideEvent(
                    id = context.eventId(GuideCategory.HIGHWAY, bucket, "enter"),
                    priority = GuidancePriority.NORMAL,
                    distanceMeters = distance,
                    kind = HighwayGuideKind.ENTER,
                    direction = step.direction(),
                    name = step.destinationOrName(),
                )
                "off ramp" -> HighwayGuideEvent(
                    id = context.eventId(GuideCategory.HIGHWAY, bucket, "exit"),
                    priority = if (distance <= 300.0) GuidancePriority.HIGH else GuidancePriority.NORMAL,
                    distanceMeters = distance,
                    kind = HighwayGuideKind.EXIT,
                    direction = step.direction(),
                    name = step.destinationOrName(),
                )
                "fork" -> HighwayGuideEvent(
                    id = context.eventId(GuideCategory.HIGHWAY, bucket, "fork"),
                    priority = if (distance <= 300.0) GuidancePriority.HIGH else GuidancePriority.NORMAL,
                    distanceMeters = distance,
                    kind = HighwayGuideKind.FORK,
                    direction = step.direction(),
                    name = step.destinationOrName(),
                )
                "merge" -> HighwayGuideEvent(
                    id = context.eventId(GuideCategory.HIGHWAY, bucket, "merge"),
                    priority = GuidancePriority.NORMAL,
                    distanceMeters = distance,
                    kind = HighwayGuideKind.MERGE,
                    direction = step.direction(),
                    name = step.destinationOrName(),
                )
                else -> null
            }
        }

        val tollEvent = context.upcomingIntersections
            .firstOrNull {
                it.distanceFromCurrentMeters <= TOLL_GUIDE_MAX_DISTANCE_METERS && it.intersection.tollCollection() != null
            }
            ?.let { intersectionContext ->
                SafetyOrHighwayEventFactory.tollGate(
                    context = context,
                    intersectionContext = intersectionContext,
                )
            }

        return listOfNotNull(highwayEvent, tollEvent)
    }

    private fun extractRoadKindChange(context: GuidanceContext): List<GuidanceEvent> {
        val previous = context.previousRoadKind ?: return emptyList()
        if (previous == context.currentRoadKind) return emptyList()
        if (context.currentRoadKind == RoadKind.GENERAL) return emptyList()

        return listOf(
            HighwayGuideEvent(
                id = context.eventId(GuideCategory.HIGHWAY, null, "road-kind-${context.currentRoadKind}"),
                priority = GuidancePriority.LOW,
                distanceMeters = null,
                kind = HighwayGuideKind.ROAD_KIND_CHANGED,
                direction = Direction.UNKNOWN,
                name = null,
            ),
        )
    }

    private fun extractLaneEvents(context: GuidanceContext): List<GuidanceEvent> {
        val intersectionContext = context.upcomingIntersections
            .firstOrNull { intersection ->
                intersection.distanceFromCurrentMeters <= LANE_GUIDE_MAX_DISTANCE_METERS &&
                    intersection.intersection.lanes().orEmpty().any { it.valid() == true }
            } ?: return emptyList()
        val lanes = intersectionContext.intersection.lanes().orEmpty()
        val validLaneIndices = lanes.mapIndexedNotNull { index, lane ->
            index.takeIf { lane.valid() == true || lane.active() == true }
        }
        if (lanes.isEmpty() || validLaneIndices.isEmpty()) return emptyList()

        return listOf(
            LaneGuideEvent(
                id = context.eventId(
                    category = GuideCategory.LANE,
                    bucket = null,
                    variant = "lane-${intersectionContext.geometryIndex ?: intersectionContext.intersectionIndex}",
                    geometryIndex = intersectionContext.geometryIndex,
                    stepIndex = intersectionContext.stepIndex,
                    legIndex = intersectionContext.legIndex,
                ),
                priority = GuidancePriority.NORMAL,
                distanceMeters = intersectionContext.distanceFromCurrentMeters,
                laneCount = lanes.size,
                validLaneIndices = validLaneIndices,
            ),
        )
    }

    private fun extractSafetyEvents(context: GuidanceContext): List<GuidanceEvent> {
        return context.upcomingIntersections
            .filter { it.distanceFromCurrentMeters <= SAFETY_GUIDE_MAX_DISTANCE_METERS }
            .mapNotNull { intersectionContext ->
                val kind = when {
                    intersectionContext.intersection.railwayCrossing() == true -> SafetyGuideKind.RAILWAY_CROSSING
                    intersectionContext.intersection.stopSign() == true -> SafetyGuideKind.STOP_SIGN
                    else -> null
                } ?: return@mapNotNull null

                SafetyGuideEvent(
                    id = context.eventId(
                        category = GuideCategory.SAFETY,
                        bucket = null,
                        variant = kind.name,
                        geometryIndex = intersectionContext.geometryIndex,
                        stepIndex = intersectionContext.stepIndex,
                        legIndex = intersectionContext.legIndex,
                    ),
                    priority = if (intersectionContext.distanceFromCurrentMeters <= 120.0) {
                        GuidancePriority.NORMAL
                    } else {
                        GuidancePriority.LOW
                    },
                    distanceMeters = intersectionContext.distanceFromCurrentMeters,
                    kind = kind,
                )
            }
    }

    private fun extractAlongRoadEvents(context: GuidanceContext): List<GuidanceEvent> {
        val step = context.currentStep ?: return emptyList()
        val stepKey = StepKey(context.currentLegIndex, context.currentStepIndex)
        if (stepKey == lastStepKey) return emptyList()

        val distance = context.routeProgress.currentLegProgress
            ?.currentStepProgress
            ?.distanceRemaining
            ?.toDouble() ?: return emptyList()
        if (distance < ALONG_ROAD_MIN_DISTANCE_METERS) return emptyList()
        if (step.maneuver().type().orEmpty() in IGNORED_MANEUVER_TYPES) return emptyList()

        val bucket = when {
            distance >= 5_000.0 -> DistanceBucket.KM5
            distance >= 3_000.0 -> DistanceBucket.KM3
            else -> DistanceBucket.KM2
        }

        return listOf(
            AlongRoadGuideEvent(
                id = context.eventId(GuideCategory.ALONG_ROAD, bucket, "along-road"),
                priority = GuidancePriority.LOW,
                distanceMeters = distance,
                bucket = bucket,
            ),
        )
    }

    private fun GuidanceContext.eventId(
        category: GuideCategory,
        bucket: DistanceBucket?,
        variant: String,
        geometryIndex: Int? = null,
        stepIndex: Int = currentStepIndex,
        legIndex: Int = currentLegIndex,
    ): GuidanceEventId {
        return GuidanceEventId(
            routeId = routeId,
            category = category,
            legIndex = legIndex,
            stepIndex = stepIndex,
            geometryIndex = geometryIndex,
            distanceBucket = bucket,
            variant = variant,
        )
    }

    private fun triggerBucketForTurn(
        distanceMeters: Double,
        roadKind: RoadKind,
    ): DistanceBucket? {
        return when (roadKind) {
            RoadKind.HIGHWAY,
            RoadKind.TOLL,
            -> when {
                distanceMeters <= 50.0 -> DistanceBucket.M50
                distanceMeters <= 300.0 -> DistanceBucket.M300
                distanceMeters <= 1_000.0 -> DistanceBucket.KM1
                distanceMeters <= 2_000.0 -> DistanceBucket.KM2
                else -> null
            }
            RoadKind.GENERAL -> when {
                distanceMeters <= 50.0 -> DistanceBucket.M50
                distanceMeters <= 100.0 -> DistanceBucket.M100
                distanceMeters <= 300.0 -> DistanceBucket.M300
                else -> null
            }
        }
    }

    private fun timingFor(bucket: DistanceBucket): TurnTiming {
        return when (bucket) {
            DistanceBucket.M50 -> TurnTiming.SOON
            DistanceBucket.M100,
            DistanceBucket.M200,
            DistanceBucket.M300,
            -> TurnTiming.MIDDLE
            else -> TurnTiming.FAR
        }
    }

    private fun LegStep.direction(): Direction {
        return when (maneuver().modifier()) {
            "left" -> Direction.LEFT
            "right" -> Direction.RIGHT
            "slight left" -> Direction.SLIGHT_LEFT
            "slight right" -> Direction.SLIGHT_RIGHT
            "sharp left" -> Direction.SHARP_LEFT
            "sharp right" -> Direction.SHARP_RIGHT
            "uturn" -> Direction.UTURN
            "straight" -> Direction.STRAIGHT
            else -> Direction.UNKNOWN
        }
    }

    private fun LegStep.destinationOrName(): String? {
        return destinations()
            ?.takeIf { it.isNotBlank() }
            ?: maneuver().instruction()
                ?.takeIf { it.isNotBlank() }
            ?: name()
                ?.takeIf { it.isNotBlank() }
    }

    private object SafetyOrHighwayEventFactory {
        fun tollGate(
            context: GuidanceContext,
            intersectionContext: UpcomingIntersectionContext,
        ): HighwayGuideEvent {
            return HighwayGuideEvent(
                id = GuidanceEventId(
                    routeId = context.routeId,
                    category = GuideCategory.HIGHWAY,
                    legIndex = intersectionContext.legIndex,
                    stepIndex = intersectionContext.stepIndex,
                    geometryIndex = intersectionContext.geometryIndex,
                    distanceBucket = null,
                    variant = "toll-gate",
                ),
                priority = GuidancePriority.NORMAL,
                distanceMeters = intersectionContext.distanceFromCurrentMeters,
                kind = HighwayGuideKind.TOLL_GATE,
                direction = Direction.UNKNOWN,
                name = null,
            )
        }
    }

    private data class StepKey(
        val legIndex: Int,
        val stepIndex: Int,
    )

    private companion object {
        private val HIGHWAY_MANEUVER_TYPES = setOf("on ramp", "off ramp", "fork", "merge")
        private val IGNORED_MANEUVER_TYPES = setOf("depart", "arrive", "notification")
        private const val LINKED_TURN_MAX_DISTANCE_METERS = 300.0
        private const val LANE_GUIDE_MAX_DISTANCE_METERS = 500.0
        private const val SAFETY_GUIDE_MAX_DISTANCE_METERS = 400.0
        private const val TOLL_GUIDE_MAX_DISTANCE_METERS = 500.0
        private const val ALONG_ROAD_MIN_DISTANCE_METERS = 2_000.0
    }
}
