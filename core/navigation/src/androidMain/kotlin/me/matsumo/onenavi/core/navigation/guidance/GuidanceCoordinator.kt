package me.matsumo.onenavi.core.navigation.guidance

import androidx.compose.runtime.Stable
import com.mapbox.api.directions.v5.models.BannerComponents
import com.mapbox.api.directions.v5.models.LegStep
import com.mapbox.navigation.base.trip.model.RouteProgress

/**
 * GuidanceContext から発話すべき構造化案内イベントを抽出するクラス。
 */
class GuidanceCoordinator(
    private val speechHistory: GuidanceSpeechHistory,
) {

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
        return event
    }

    fun reset() {
        lastRouteId = null
        lastStepKey = null
        speechHistory.clear()
    }

    private fun extractTurnEvents(context: GuidanceContext): List<GuidanceEvent> {
        val stepContext = context.nextManeuverStepContext() ?: return emptyList()
        val step = stepContext.step
        val maneuverType = step.maneuver().type().orEmpty()
        if (maneuverType in HIGHWAY_MANEUVER_TYPES || maneuverType in IGNORED_MANEUVER_TYPES) {
            return emptyList()
        }

        val distance = stepContext.distanceFromCurrentMeters
        val bucket = triggerBucketForTurn(distance, context.currentRoadKind) ?: return emptyList()
        val direction = step.direction()
        val targetPhrase = context.turnTargetPhrase(stepContext, bucket)
        val linkedEvent = buildLinkedTurnEvent(context, stepContext, direction, bucket, targetPhrase)
        if (linkedEvent != null) {
            return listOf(linkedEvent)
        }

        return listOf(
            TurnGuideEvent(
                id = context.eventId(
                    category = GuideCategory.TURN,
                    bucket = bucket,
                    variant = "turn",
                    stepIndex = stepContext.stepIndex,
                    legIndex = stepContext.legIndex,
                ),
                priority = if (bucket == DistanceBucket.M50) GuidancePriority.HIGH else GuidancePriority.NORMAL,
                distanceMeters = distance,
                direction = direction,
                timing = timingFor(bucket),
                targetPhrase = targetPhrase,
                roadName = step.roadDisplayName(context),
            ),
        )
    }

    private fun buildLinkedTurnEvent(
        context: GuidanceContext,
        currentStepContext: UpcomingStepContext,
        currentDirection: Direction,
        bucket: DistanceBucket,
        currentTargetPhrase: String?,
    ): LinkedTurnGuideEvent? {
        if (bucket != DistanceBucket.M50 && bucket != DistanceBucket.M100) return null

        val nextStepContext = context.upcomingSteps.firstOrNull { it.isAfter(currentStepContext) }
            ?: return null
        val distanceAfterFirstManeuver = nextStepContext.distanceFromCurrentMeters - currentStepContext.distanceFromCurrentMeters
        if (distanceAfterFirstManeuver > LINKED_TURN_MAX_DISTANCE_METERS) return null

        val nextManeuverType = nextStepContext.step.maneuver().type().orEmpty()
        if (nextManeuverType in IGNORED_MANEUVER_TYPES) return null

        val nextTargetPhrase = context.turnTargetPhrase(nextStepContext, null)
        return LinkedTurnGuideEvent(
            id = context.eventId(
                category = GuideCategory.TURN,
                bucket = bucket,
                variant = "linked-${nextStepContext.legIndex}-${nextStepContext.stepIndex}",
                stepIndex = currentStepContext.stepIndex,
                legIndex = currentStepContext.legIndex,
            ),
            priority = GuidancePriority.HIGH,
            distanceMeters = currentStepContext.distanceFromCurrentMeters,
            firstDirection = currentDirection,
            firstTargetPhrase = currentTargetPhrase,
            nextDirection = nextStepContext.step.direction(),
            nextManeuverType = nextManeuverType,
            nextTargetPhrase = nextTargetPhrase,
        )
    }

    private fun extractHighwayEvents(context: GuidanceContext): List<GuidanceEvent> {
        val stepContext = context.nextManeuverStepContext()
        val step = stepContext?.step ?: return emptyList()
        val maneuverType = step.maneuver().type().orEmpty()
        val distance = stepContext.distanceFromCurrentMeters
        val maneuverBucket = triggerBucketForTurn(distance, RoadKind.HIGHWAY)

        val highwayEvent = maneuverBucket?.let { bucket ->
            when (maneuverType) {
                "on ramp" -> HighwayGuideEvent(
                    id = context.eventId(
                        category = GuideCategory.HIGHWAY,
                        bucket = bucket,
                        variant = "enter",
                        stepIndex = stepContext.stepIndex,
                        legIndex = stepContext.legIndex,
                    ),
                    priority = GuidancePriority.NORMAL,
                    distanceMeters = distance,
                    kind = HighwayGuideKind.ENTER,
                    direction = step.direction(),
                    name = step.highwayDisplayName(context, stepContext),
                )
                "off ramp" -> HighwayGuideEvent(
                    id = context.eventId(
                        category = GuideCategory.HIGHWAY,
                        bucket = bucket,
                        variant = "exit",
                        stepIndex = stepContext.stepIndex,
                        legIndex = stepContext.legIndex,
                    ),
                    priority = if (distance <= 300.0) GuidancePriority.HIGH else GuidancePriority.NORMAL,
                    distanceMeters = distance,
                    kind = HighwayGuideKind.EXIT,
                    direction = step.direction(),
                    name = step.highwayDisplayName(context, stepContext),
                )
                "fork" -> HighwayGuideEvent(
                    id = context.eventId(
                        category = GuideCategory.HIGHWAY,
                        bucket = bucket,
                        variant = "fork",
                        stepIndex = stepContext.stepIndex,
                        legIndex = stepContext.legIndex,
                    ),
                    priority = if (distance <= 300.0) GuidancePriority.HIGH else GuidancePriority.NORMAL,
                    distanceMeters = distance,
                    kind = HighwayGuideKind.FORK,
                    direction = step.direction(),
                    name = step.highwayDisplayName(context, stepContext),
                )
                "merge" -> HighwayGuideEvent(
                    id = context.eventId(
                        category = GuideCategory.HIGHWAY,
                        bucket = bucket,
                        variant = "merge",
                        stepIndex = stepContext.stepIndex,
                        legIndex = stepContext.legIndex,
                    ),
                    priority = GuidancePriority.NORMAL,
                    distanceMeters = distance,
                    kind = HighwayGuideKind.MERGE,
                    direction = step.direction(),
                    name = step.highwayDisplayName(context, stepContext),
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
        val stepKey = StepKey(context.currentLegIndex, context.currentStepIndex)
        if (stepKey == lastStepKey) return emptyList()

        val guideStepContext = context.nextAlongRoadGuideStepContext() ?: return emptyList()
        val distance = guideStepContext.distanceFromCurrentMeters
        if (distance < ALONG_ROAD_MIN_DISTANCE_METERS) return emptyList()

        val bucket = when {
            distance >= 5_000.0 -> DistanceBucket.KM5
            distance >= 3_000.0 -> DistanceBucket.KM3
            else -> DistanceBucket.KM2
        }

        return listOf(
            AlongRoadGuideEvent(
                id = context.eventId(
                    category = GuideCategory.ALONG_ROAD,
                    bucket = bucket,
                    variant = "along-road",
                    stepIndex = guideStepContext.stepIndex,
                    legIndex = guideStepContext.legIndex,
                ),
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

    private fun GuidanceContext.nextManeuverStepContext(): UpcomingStepContext? {
        return upcomingSteps.firstOrNull { stepContext ->
            stepContext.isAfterCurrent(this) &&
                stepContext.step.maneuver().type().orEmpty() != "depart"
        }
    }

    private fun GuidanceContext.nextAlongRoadGuideStepContext(): UpcomingStepContext? {
        return upcomingSteps.firstOrNull { stepContext ->
            stepContext.isAfterCurrent(this) &&
                stepContext.step.maneuver().type().orEmpty() in ALONG_ROAD_GUIDE_MANEUVER_TYPES
        }
    }

    private fun UpcomingStepContext.isAfterCurrent(context: GuidanceContext): Boolean {
        return legIndex > context.currentLegIndex ||
            legIndex == context.currentLegIndex && stepIndex > context.currentStepIndex
    }

    private fun UpcomingStepContext.isAfter(other: UpcomingStepContext): Boolean {
        return legIndex > other.legIndex ||
            legIndex == other.legIndex && stepIndex > other.stepIndex
    }

    private fun GuidanceContext.turnTargetPhrase(
        stepContext: UpcomingStepContext,
        bucket: DistanceBucket?,
    ): String? {
        val trafficSignals = upcomingIntersections
            .filter { intersectionContext ->
                intersectionContext.distanceFromCurrentMeters <= stepContext.distanceFromCurrentMeters + INTERSECTION_DISTANCE_TOLERANCE_METERS &&
                    intersectionContext.intersection.trafficSignal() == true
            }
        if (trafficSignals.isNotEmpty()) {
            return when (trafficSignals.size.coerceAtMost(3)) {
                1 -> if (bucket == DistanceBucket.M50) "次の信号を" else "この信号を"
                2 -> "2つ目の信号を"
                else -> "3つ目の信号を"
            }
        }

        return stepContext.step.maneuverTargetName(this)
            ?.let { "${it}を" }
    }

    private fun LegStep.maneuverTargetName(context: GuidanceContext): String? {
        return context.bannerDisplayName()
            ?: roadDisplayName(context)
    }

    private fun LegStep.roadDisplayName(context: GuidanceContext): String? {
        return listOfNotNull(
            name(),
            ref(),
            destinations(),
            context.lastBannerInstruction?.secondary()?.text(),
        ).firstNotNullOfOrNull { it.cleanGuideName() }
    }

    private fun LegStep.highwayDisplayName(
        context: GuidanceContext,
        stepContext: UpcomingStepContext,
    ): String? {
        return context.upcomingIntersections
            .asSequence()
            .filter { it.legIndex == stepContext.legIndex && it.stepIndex == stepContext.stepIndex }
            .mapNotNull { intersectionContext ->
                intersectionContext.intersection.junction()?.name()
                    ?: intersectionContext.intersection.interchange()?.name()
            }
            .firstNotNullOfOrNull { it.cleanGuideName() }
            ?: context.bannerFacilityName()
            ?: destinations().cleanGuideName()
            ?: name().cleanGuideName()
            ?: maneuver().instruction().cleanGuideName()
    }

    private fun GuidanceContext.bannerDisplayName(): String? {
        return sequenceOf(
            lastBannerInstruction?.primary(),
            lastBannerInstruction?.secondary(),
            lastBannerInstruction?.sub(),
        ).flatMap { bannerText ->
            bannerText?.components().orEmpty().asSequence()
        }.filter { component ->
            component.type() in NAME_COMPONENT_TYPES
        }.mapNotNull { component ->
            component.text().cleanGuideName()
        }.firstOrNull()
    }

    private fun GuidanceContext.bannerFacilityName(): String? {
        return sequenceOf(
            lastBannerInstruction?.primary(),
            lastBannerInstruction?.secondary(),
            lastBannerInstruction?.sub(),
        ).flatMap { bannerText ->
            bannerText?.components().orEmpty().asSequence()
        }.filter { component ->
            component.type() in FACILITY_COMPONENT_TYPES
        }.mapNotNull { component ->
            component.text().cleanGuideName()
        }.firstOrNull()
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

    private fun String?.cleanGuideName(): String? {
        val value = this
            ?.replace("、", " ")
            ?.replace("。", " ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return null

        if (value.any { it in 'ぁ'..'ん' || it in 'ァ'..'ン' || it in '一'..'龯' || it.isLetterOrDigit() }) {
            if (value.isInstructionLike()) return null
            return value
        }

        return null
    }

    private fun String.isInstructionLike(): Boolean {
        return INSTRUCTION_WORDS.any { it in this }
    }

    /**
     * 安全案内と高速道路案内で共用するイベント生成処理。
     */
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

    /**
     * 現在処理中のステップを一意に識別するキー。
     */
    @Stable
    private data class StepKey(
        val legIndex: Int,
        val stepIndex: Int,
    )

    /**
     * 案内抽出で使う Mapbox maneuver 種別と距離しきい値。
     */
    private companion object {
        private val HIGHWAY_MANEUVER_TYPES = setOf("on ramp", "off ramp", "fork", "merge")
        private val IGNORED_MANEUVER_TYPES = setOf("depart", "arrive", "notification")
        private val ALONG_ROAD_GUIDE_MANEUVER_TYPES = HIGHWAY_MANEUVER_TYPES + setOf(
            "turn",
            "end of road",
            "roundabout",
            "rotary",
            "arrive",
        )
        private val FACILITY_COMPONENT_TYPES = setOf(
            BannerComponents.EXPRESSWAY_ENTRANCE,
            BannerComponents.EXPRESSWAY_EXIT,
            BannerComponents.JCT,
            BannerComponents.SAPA,
            BannerComponents.TOLLBRANCH,
            BannerComponents.SIGNBOARD,
            BannerComponents.EXIT,
            BannerComponents.EXIT_NUMBER,
        )
        private val NAME_COMPONENT_TYPES = FACILITY_COMPONENT_TYPES + setOf(
            BannerComponents.TEXT,
            BannerComponents.CITYREAL,
        )
        private val INSTRUCTION_WORDS = setOf(
            "方向です",
            "直進です",
            "右折",
            "左折",
            "進みます",
            "進んで",
            "曲が",
            "まもなく",
            "およそ",
        )
        private const val LINKED_TURN_MAX_DISTANCE_METERS = 300.0
        private const val INTERSECTION_DISTANCE_TOLERANCE_METERS = 40.0
        private const val LANE_GUIDE_MAX_DISTANCE_METERS = 500.0
        private const val SAFETY_GUIDE_MAX_DISTANCE_METERS = 400.0
        private const val TOLL_GUIDE_MAX_DISTANCE_METERS = 500.0
        private const val ALONG_ROAD_MIN_DISTANCE_METERS = 2_000.0
    }
}
