package me.matsumo.onenavi.core.navigation

import android.content.Context
import com.google.android.libraries.mapsplatform.turnbyturn.model.DrivingSide
import com.google.android.libraries.mapsplatform.turnbyturn.model.LaneDirection
import com.google.android.libraries.mapsplatform.turnbyturn.model.Maneuver
import com.google.android.libraries.mapsplatform.turnbyturn.model.NavState
import io.github.aakira.napier.Napier
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import me.matsumo.onenavi.core.model.ArrivalInfo
import me.matsumo.onenavi.core.model.GuidanceUiState
import me.matsumo.onenavi.core.model.LaneInfo
import me.matsumo.onenavi.core.model.ManeuverInfo
import me.matsumo.onenavi.core.model.NavigationState
import me.matsumo.onenavi.core.model.RouteStepInfo
import me.matsumo.onenavi.core.model.TripProgressInfo
import me.matsumo.onenavi.core.navigation.guidance.DestinationKind
import me.matsumo.onenavi.core.navigation.guidance.Direction
import me.matsumo.onenavi.core.navigation.guidance.DistanceBucket
import me.matsumo.onenavi.core.navigation.guidance.GuidanceEvent
import me.matsumo.onenavi.core.navigation.guidance.GuidanceEventId
import me.matsumo.onenavi.core.navigation.guidance.GuidancePriority
import me.matsumo.onenavi.core.navigation.guidance.GuidanceSpeechHistory
import me.matsumo.onenavi.core.navigation.guidance.GuideCategory
import me.matsumo.onenavi.core.navigation.guidance.HighwayGuideEvent
import me.matsumo.onenavi.core.navigation.guidance.HighwayGuideKind
import me.matsumo.onenavi.core.navigation.guidance.JapaneseGuidancePhraseComposer
import me.matsumo.onenavi.core.navigation.guidance.LaneGuideEvent
import me.matsumo.onenavi.core.navigation.guidance.RerouteGuideEvent
import me.matsumo.onenavi.core.navigation.guidance.SessionGuideEvent
import me.matsumo.onenavi.core.navigation.guidance.SessionGuideKind
import me.matsumo.onenavi.core.navigation.guidance.TurnGuideEvent
import me.matsumo.onenavi.core.navigation.guidance.TurnTiming
import me.matsumo.onenavi.core.navigation.guidance.WaypointGuideEvent
import me.matsumo.onenavi.core.navigation.tts.AndroidTtsEngine
import me.matsumo.onenavi.core.navigation.tts.AudioFocusManager
import me.matsumo.onenavi.core.navigation.tts.SpeechOrchestrator

class GuidanceSessionManager(
    private val context: Context,
    private val cameraManager: CameraManager,
    private val routeManager: RouteManager,
    private val navigationSdkManager: NavigationSdkManager,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _navigationState = MutableStateFlow<NavigationState>(NavigationState.Browsing)
    val navigationState: StateFlow<NavigationState> = _navigationState.asStateFlow()

    private val _guidanceUiState = MutableStateFlow(GuidanceUiState.Initial)
    val guidanceUiState: StateFlow<GuidanceUiState> = _guidanceUiState.asStateFlow()

    private val _arrivalInfo = MutableStateFlow<ArrivalInfo?>(null)
    val arrivalInfo: StateFlow<ArrivalInfo?> = _arrivalInfo.asStateFlow()

    private val _guidanceEvents = MutableSharedFlow<GuidanceEvent>(extraBufferCapacity = 32)
    val guidanceEvents: SharedFlow<GuidanceEvent> = _guidanceEvents.asSharedFlow()

    private var sessionJob: Job? = null
    private var speechOrchestrator: SpeechOrchestrator? = null
    private val phraseComposer = JapaneseGuidancePhraseComposer()
    private val speechHistory = GuidanceSpeechHistory()

    private var sessionStartTimeMillis: Long = 0L
    private var lastOffRoute = false

    fun register() {
        // no-op
    }

    fun unregister() {
        stopSession()
    }

    fun setNavigationState(state: NavigationState) {
        _navigationState.value = state
    }

    fun startSession() {
        val route = routeManager.routes.value.firstOrNull() ?: return

        sessionJob?.cancel()
        sessionStartTimeMillis = System.currentTimeMillis()
        lastOffRoute = false
        speechHistory.resetForNewRoute(route.id)

        speechOrchestrator = SpeechOrchestrator(
            ttsEngine = AndroidTtsEngine(
                context = context,
                audioFocusManager = AudioFocusManager(context),
            ),
            speechHistory = speechHistory,
        )

        sessionJob = scope.launch {
            val result = navigationSdkManager.startNavigation(route)
            if (result.isFailure) {
                Napier.e(result.exceptionOrNull(), tag = TAG) { "Failed to start navigation session." }
                speechOrchestrator?.shutdown()
                speechOrchestrator = null
                _navigationState.value = NavigationState.RoutePreview
                return@launch
            }

            _navigationState.value = NavigationState.ActiveGuidance
            _guidanceUiState.value = GuidanceUiState.Initial.copy(isTtsAvailable = true)
            speak(sessionEvent(route.id, SessionGuideKind.START), flush = true)
            cameraManager.requestCameraFollowing(pitch3D = true)

            launch { collectGuidanceUpdates() }
            launch { collectArrivalEvents() }
        }
    }

    fun stopSession() {
        sessionJob?.cancel()
        sessionJob = null
        routeManager.routes.value.firstOrNull()?.let { route ->
            speak(sessionEvent(route.id, SessionGuideKind.STOP), flush = true)
        }

        navigationSdkManager.stopNavigation()
        speechOrchestrator?.shutdown()
        speechOrchestrator = null
        speechHistory.clear()
        lastOffRoute = false

        _guidanceUiState.value = GuidanceUiState.Initial
        _arrivalInfo.value = null
    }

    fun returnToBrowsing() {
        _navigationState.value = NavigationState.Browsing
    }

    fun setTtsMuted(muted: Boolean) {
        speechOrchestrator?.setMuted(muted)
    }

    private suspend fun collectGuidanceUpdates() {
        combine(
            navigationSdkManager.navInfo,
            navigationSdkManager.tripProgress,
            navigationSdkManager.isOffRoute,
            routeManager.routes,
        ) { navInfo, tripProgress, isOffRoute, routes ->
            GuidanceSnapshot(
                routeId = routes.firstOrNull()?.id,
                navInfo = navInfo,
                tripProgress = tripProgress,
                isOffRoute = isOffRoute,
            )
        }.collect { snapshot ->
            updateGuidance(snapshot)
        }
    }

    private suspend fun collectArrivalEvents() {
        navigationSdkManager.arrivalEvents.collect { arrival ->
            val routeId = routeManager.routes.value.firstOrNull()?.id ?: return@collect
            speak(
                waypointEvent(
                    routeId = routeId,
                    finalDestination = arrival.isFinalDestination,
                ),
                flush = true,
            )

            if (arrival.isFinalDestination) {
                val progress = navigationSdkManager.tripProgress.value
                val route = routeManager.routes.value.firstOrNull()
                _arrivalInfo.value = ArrivalInfo(
                    destinationName = arrival.waypointTitle.orEmpty(),
                    totalDistanceMeters = route?.distanceMeters ?: progress?.distanceRemainingMeters?.toDouble() ?: 0.0,
                    totalDurationSeconds = (System.currentTimeMillis() - sessionStartTimeMillis) / 1000.0,
                )
                _navigationState.value = NavigationState.Arrival
            } else {
                navigationSdkManager.continueToNextDestination()
            }
        }
    }

    private fun updateGuidance(snapshot: GuidanceSnapshot) {
        val routeId = snapshot.routeId ?: return
        val navInfo = snapshot.navInfo
        val tripProgress = snapshot.tripProgress
        if (navInfo == null || tripProgress == null) return

        val currentManeuver = navInfo.currentStep?.toManeuverInfo(
            distanceMeters = navInfo.distanceToCurrentStepMeters.toDouble(),
        )
        val nextManeuver = navInfo.remainingSteps.firstOrNull()?.toManeuverInfo(
            distanceMeters = 0.0,
        )
        val upcomingSteps = buildUpcomingSteps(navInfo)

        _guidanceUiState.value = GuidanceUiState(
            currentManeuver = currentManeuver,
            nextManeuver = nextManeuver,
            upcomingSteps = upcomingSteps,
            tripProgress = TripProgressInfo(
                distanceRemainingMeters = tripProgress.distanceRemainingMeters.toDouble(),
                durationRemainingSeconds = tripProgress.timeRemainingSeconds.toDouble(),
                estimatedArrivalTimeMillis = System.currentTimeMillis() + tripProgress.timeRemainingSeconds * 1000L,
            ),
            currentRoadName = navInfo.currentStep?.roadName?.takeIf { it.isNotBlank() },
            isOffRoute = snapshot.isOffRoute || navInfo.navState == NavState.REROUTING,
            isTtsAvailable = speechOrchestrator != null,
            isLocationStale = false,
        )

        maybeSpeakReroute(routeId, snapshot.isOffRoute || navInfo.navState == NavState.REROUTING)
        maybeSpeakCurrentStep(routeId, navInfo)
    }

    private fun maybeSpeakReroute(
        routeId: String,
        isOffRoute: Boolean,
    ) {
        if (isOffRoute && !lastOffRoute) {
            speak(rerouteEvent(routeId, offRoute = true), flush = true)
        } else if (!isOffRoute && lastOffRoute) {
            speak(rerouteEvent(routeId, offRoute = false), flush = true)
        }
        lastOffRoute = isOffRoute
    }

    private fun maybeSpeakCurrentStep(
        routeId: String,
        navInfo: NavigationFeedSnapshot,
    ) {
        val currentStep = navInfo.currentStep ?: return
        val distanceMeters = navInfo.distanceToCurrentStepMeters.toDouble()
        val primaryEvent = currentStep.toPrimaryGuidanceEvent(
            routeId = routeId,
            distanceMeters = distanceMeters,
        )
        speak(primaryEvent)

        val laneEvent = currentStep.toLaneEvent(
            routeId = routeId,
            distanceMeters = distanceMeters,
        )
        if (laneEvent != null) {
            speak(laneEvent)
        }
    }

    private fun buildUpcomingSteps(navInfo: NavigationFeedSnapshot) = buildList {
        navInfo.currentStep?.let { step ->
            add(
                step.toRouteStepInfo(
                    distanceFromPreviousMeters = navInfo.distanceToCurrentStepMeters.toDouble(),
                    cumulativeDistanceMeters = navInfo.distanceToCurrentStepMeters.toDouble(),
                ),
            )
        }
        navInfo.remainingSteps.forEachIndexed { index, step ->
            add(
                step.toRouteStepInfo(
                    distanceFromPreviousMeters = 0.0,
                    cumulativeDistanceMeters = navInfo.distanceToCurrentStepMeters + index + 1.0,
                ),
            )
        }
    }.toImmutableList()

    private fun NavigationStepSnapshot.toManeuverInfo(distanceMeters: Double): ManeuverInfo {
        return ManeuverInfo(
            type = maneuver.toManeuverType(),
            modifier = maneuver.toModifier(),
            degrees = roundaboutTurnNumber?.toDouble(),
            drivingSide = when (drivingSide) {
                DrivingSide.LEFT -> "left"
                DrivingSide.RIGHT -> "right"
                else -> null
            },
            distanceMeters = distanceMeters,
            instruction = instruction,
            roadName = roadName?.takeIf { it.isNotBlank() },
            destinations = instruction.takeIf { it.isNotBlank() && it != roadName },
            lanes = lanes.map { lane ->
                LaneInfo(
                    directions = lane.directions.map { shape -> shape.toLaneDirectionString() }.toImmutableList(),
                    activeDirection = lane.activeDirection?.toLaneDirectionString(),
                    isRecommended = lane.isRecommended,
                )
            }.toImmutableList(),
        )
    }

    private fun NavigationStepSnapshot.toRouteStepInfo(
        distanceFromPreviousMeters: Double,
        cumulativeDistanceMeters: Double,
    ): RouteStepInfo {
        return RouteStepInfo(
            maneuverType = maneuver.toManeuverType(),
            modifier = maneuver.toModifier(),
            distanceFromPreviousMeters = distanceFromPreviousMeters,
            cumulativeDistanceMeters = cumulativeDistanceMeters,
            instruction = instruction,
            roadName = roadName.orEmpty(),
            roadRef = null,
            highwayInfo = null,
        )
    }

    private fun NavigationStepSnapshot.toPrimaryGuidanceEvent(
        routeId: String,
        distanceMeters: Double,
    ): GuidanceEvent {
        val maneuverType = maneuver.toManeuverType()
        val direction = maneuver.toDirection()
        val bucket = DistanceBucket.fromMeters(distanceMeters)
        val id = GuidanceEventId(
            routeId = routeId,
            category = if (maneuverType in HIGHWAY_TYPES) GuideCategory.HIGHWAY else GuideCategory.TURN,
            legIndex = 0,
            stepIndex = 0,
            geometryIndex = null,
            distanceBucket = bucket,
            variant = "$maneuver|$instruction|$roadName",
        )

        return if (maneuverType in HIGHWAY_TYPES) {
            HighwayGuideEvent(
                id = id,
                priority = GuidancePriority.NORMAL,
                distanceMeters = distanceMeters,
                kind = maneuverType.toHighwayGuideKind(),
                direction = direction,
                name = roadName ?: instruction,
            )
        } else {
            TurnGuideEvent(
                id = id,
                priority = GuidancePriority.NORMAL,
                distanceMeters = distanceMeters,
                direction = direction,
                timing = when {
                    distanceMeters <= 120.0 -> TurnTiming.SOON
                    distanceMeters <= 500.0 -> TurnTiming.MIDDLE
                    else -> TurnTiming.FAR
                },
                roadName = roadName,
            )
        }
    }

    private fun NavigationStepSnapshot.toLaneEvent(
        routeId: String,
        distanceMeters: Double,
    ): LaneGuideEvent? {
        val recommendedIndices = lanes.mapIndexedNotNull { index, lane ->
            index.takeIf { lane.isRecommended }
        }
        if (recommendedIndices.isEmpty()) return null

        return LaneGuideEvent(
            id = GuidanceEventId(
                routeId = routeId,
                category = GuideCategory.LANE,
                legIndex = 0,
                stepIndex = 0,
                geometryIndex = null,
                distanceBucket = DistanceBucket.fromMeters(distanceMeters),
                variant = "$maneuver|lane|$instruction",
            ),
            priority = GuidancePriority.HIGH,
            distanceMeters = distanceMeters,
            laneCount = lanes.size,
            validLaneIndices = recommendedIndices,
        )
    }

    private fun waypointEvent(
        routeId: String,
        finalDestination: Boolean,
    ): WaypointGuideEvent {
        return WaypointGuideEvent(
            id = GuidanceEventId(
                routeId = routeId,
                category = GuideCategory.WAYPOINT,
                legIndex = 0,
                stepIndex = 0,
                geometryIndex = null,
                distanceBucket = null,
                variant = if (finalDestination) "final" else "waypoint",
            ),
            priority = GuidancePriority.HIGH,
            kind = if (finalDestination) DestinationKind.DESTINATION else DestinationKind.WAYPOINT,
            finalDestination = finalDestination,
        )
    }

    private fun rerouteEvent(
        routeId: String,
        offRoute: Boolean,
    ): RerouteGuideEvent {
        return RerouteGuideEvent(
            id = GuidanceEventId(
                routeId = routeId,
                category = GuideCategory.REROUTE,
                legIndex = 0,
                stepIndex = 0,
                geometryIndex = null,
                distanceBucket = null,
                variant = if (offRoute) "off-route" else "rerouted",
            ),
            priority = GuidancePriority.HIGH,
            offRoute = offRoute,
        )
    }

    private fun sessionEvent(
        routeId: String,
        kind: SessionGuideKind,
    ): SessionGuideEvent {
        return SessionGuideEvent(
            id = GuidanceEventId(
                routeId = routeId,
                category = GuideCategory.SESSION,
                legIndex = 0,
                stepIndex = 0,
                geometryIndex = null,
                distanceBucket = null,
                variant = kind.name,
            ),
            priority = GuidancePriority.HIGH,
            kind = kind,
        )
    }

    private fun speak(
        event: GuidanceEvent,
        flush: Boolean = false,
    ) {
        if (flush) {
            speechOrchestrator?.setMuted(false, stopCurrent = true)
        }
        _guidanceEvents.tryEmit(event)
        val text = phraseComposer.compose(event)
        val spoken = speechOrchestrator?.enqueue(event, text) ?: false
        if (!spoken) {
            Napier.d(tag = TAG) { "TTS skipped: $text" }
        }
    }

    private fun Int.toManeuverType(): String {
        return when (this) {
            Maneuver.DESTINATION,
            Maneuver.DESTINATION_LEFT,
            Maneuver.DESTINATION_RIGHT,
            -> "arrive"
            Maneuver.FORK_LEFT,
            Maneuver.FORK_RIGHT,
            -> "fork"
            Maneuver.MERGE_LEFT,
            Maneuver.MERGE_RIGHT,
            Maneuver.MERGE_UNSPECIFIED,
            -> "merge"
            Maneuver.ON_RAMP_KEEP_LEFT,
            Maneuver.ON_RAMP_KEEP_RIGHT,
            Maneuver.ON_RAMP_LEFT,
            Maneuver.ON_RAMP_RIGHT,
            Maneuver.ON_RAMP_SHARP_LEFT,
            Maneuver.ON_RAMP_SHARP_RIGHT,
            Maneuver.ON_RAMP_SLIGHT_LEFT,
            Maneuver.ON_RAMP_SLIGHT_RIGHT,
            Maneuver.ON_RAMP_UNSPECIFIED,
            Maneuver.ON_RAMP_U_TURN_CLOCKWISE,
            Maneuver.ON_RAMP_U_TURN_COUNTERCLOCKWISE,
            -> "on ramp"
            Maneuver.OFF_RAMP_KEEP_LEFT,
            Maneuver.OFF_RAMP_KEEP_RIGHT,
            Maneuver.OFF_RAMP_LEFT,
            Maneuver.OFF_RAMP_RIGHT,
            Maneuver.OFF_RAMP_SHARP_LEFT,
            Maneuver.OFF_RAMP_SHARP_RIGHT,
            Maneuver.OFF_RAMP_SLIGHT_LEFT,
            Maneuver.OFF_RAMP_SLIGHT_RIGHT,
            Maneuver.OFF_RAMP_UNSPECIFIED,
            Maneuver.OFF_RAMP_U_TURN_CLOCKWISE,
            Maneuver.OFF_RAMP_U_TURN_COUNTERCLOCKWISE,
            -> "off ramp"
            Maneuver.ROUNDABOUT_CLOCKWISE,
            Maneuver.ROUNDABOUT_COUNTERCLOCKWISE,
            Maneuver.ROUNDABOUT_EXIT_CLOCKWISE,
            Maneuver.ROUNDABOUT_EXIT_COUNTERCLOCKWISE,
            Maneuver.ROUNDABOUT_LEFT_CLOCKWISE,
            Maneuver.ROUNDABOUT_LEFT_COUNTERCLOCKWISE,
            Maneuver.ROUNDABOUT_RIGHT_CLOCKWISE,
            Maneuver.ROUNDABOUT_RIGHT_COUNTERCLOCKWISE,
            Maneuver.ROUNDABOUT_SHARP_LEFT_CLOCKWISE,
            Maneuver.ROUNDABOUT_SHARP_LEFT_COUNTERCLOCKWISE,
            Maneuver.ROUNDABOUT_SHARP_RIGHT_CLOCKWISE,
            Maneuver.ROUNDABOUT_SHARP_RIGHT_COUNTERCLOCKWISE,
            Maneuver.ROUNDABOUT_SLIGHT_LEFT_CLOCKWISE,
            Maneuver.ROUNDABOUT_SLIGHT_LEFT_COUNTERCLOCKWISE,
            Maneuver.ROUNDABOUT_SLIGHT_RIGHT_CLOCKWISE,
            Maneuver.ROUNDABOUT_SLIGHT_RIGHT_COUNTERCLOCKWISE,
            Maneuver.ROUNDABOUT_STRAIGHT_CLOCKWISE,
            Maneuver.ROUNDABOUT_STRAIGHT_COUNTERCLOCKWISE,
            Maneuver.ROUNDABOUT_U_TURN_CLOCKWISE,
            Maneuver.ROUNDABOUT_U_TURN_COUNTERCLOCKWISE,
            -> "roundabout"
            Maneuver.STRAIGHT,
            Maneuver.NAME_CHANGE,
            Maneuver.DEPART,
            -> "continue"
            else -> "turn"
        }
    }

    private fun Int.toModifier(): String? {
        return when (this) {
            Maneuver.TURN_LEFT,
            Maneuver.TURN_KEEP_LEFT,
            Maneuver.FORK_LEFT,
            Maneuver.MERGE_LEFT,
            Maneuver.ON_RAMP_LEFT,
            Maneuver.ON_RAMP_KEEP_LEFT,
            Maneuver.OFF_RAMP_LEFT,
            Maneuver.OFF_RAMP_KEEP_LEFT,
            Maneuver.DESTINATION_LEFT,
            -> "left"
            Maneuver.TURN_RIGHT,
            Maneuver.TURN_KEEP_RIGHT,
            Maneuver.FORK_RIGHT,
            Maneuver.MERGE_RIGHT,
            Maneuver.ON_RAMP_RIGHT,
            Maneuver.ON_RAMP_KEEP_RIGHT,
            Maneuver.OFF_RAMP_RIGHT,
            Maneuver.OFF_RAMP_KEEP_RIGHT,
            Maneuver.DESTINATION_RIGHT,
            -> "right"
            Maneuver.TURN_SLIGHT_LEFT,
            Maneuver.ON_RAMP_SLIGHT_LEFT,
            Maneuver.OFF_RAMP_SLIGHT_LEFT,
            Maneuver.ROUNDABOUT_SLIGHT_LEFT_CLOCKWISE,
            Maneuver.ROUNDABOUT_SLIGHT_LEFT_COUNTERCLOCKWISE,
            -> "slight left"
            Maneuver.TURN_SLIGHT_RIGHT,
            Maneuver.ON_RAMP_SLIGHT_RIGHT,
            Maneuver.OFF_RAMP_SLIGHT_RIGHT,
            Maneuver.ROUNDABOUT_SLIGHT_RIGHT_CLOCKWISE,
            Maneuver.ROUNDABOUT_SLIGHT_RIGHT_COUNTERCLOCKWISE,
            -> "slight right"
            Maneuver.TURN_SHARP_LEFT,
            Maneuver.ON_RAMP_SHARP_LEFT,
            Maneuver.OFF_RAMP_SHARP_LEFT,
            Maneuver.ROUNDABOUT_SHARP_LEFT_CLOCKWISE,
            Maneuver.ROUNDABOUT_SHARP_LEFT_COUNTERCLOCKWISE,
            -> "sharp left"
            Maneuver.TURN_SHARP_RIGHT,
            Maneuver.ON_RAMP_SHARP_RIGHT,
            Maneuver.OFF_RAMP_SHARP_RIGHT,
            Maneuver.ROUNDABOUT_SHARP_RIGHT_CLOCKWISE,
            Maneuver.ROUNDABOUT_SHARP_RIGHT_COUNTERCLOCKWISE,
            -> "sharp right"
            Maneuver.TURN_U_TURN_CLOCKWISE,
            Maneuver.TURN_U_TURN_COUNTERCLOCKWISE,
            Maneuver.ON_RAMP_U_TURN_CLOCKWISE,
            Maneuver.ON_RAMP_U_TURN_COUNTERCLOCKWISE,
            Maneuver.OFF_RAMP_U_TURN_CLOCKWISE,
            Maneuver.OFF_RAMP_U_TURN_COUNTERCLOCKWISE,
            Maneuver.ROUNDABOUT_U_TURN_CLOCKWISE,
            Maneuver.ROUNDABOUT_U_TURN_COUNTERCLOCKWISE,
            -> "uturn"
            Maneuver.STRAIGHT,
            Maneuver.NAME_CHANGE,
            Maneuver.DEPART,
            Maneuver.ROUNDABOUT_STRAIGHT_CLOCKWISE,
            Maneuver.ROUNDABOUT_STRAIGHT_COUNTERCLOCKWISE,
            -> "straight"
            else -> null
        }
    }

    private fun Int.toDirection(): Direction {
        return when (toModifier()) {
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

    private fun String.toHighwayGuideKind(): HighwayGuideKind {
        return when (this) {
            "on ramp" -> HighwayGuideKind.ENTER
            "off ramp" -> HighwayGuideKind.EXIT
            "fork" -> HighwayGuideKind.FORK
            "merge" -> HighwayGuideKind.MERGE
            else -> HighwayGuideKind.ROAD_KIND_CHANGED
        }
    }

    private fun Int.toLaneDirectionString(): String {
        return when (this) {
            LaneDirection.LaneShape.NORMAL_LEFT -> "left"
            LaneDirection.LaneShape.NORMAL_RIGHT -> "right"
            LaneDirection.LaneShape.SLIGHT_LEFT -> "slight left"
            LaneDirection.LaneShape.SLIGHT_RIGHT -> "slight right"
            LaneDirection.LaneShape.SHARP_LEFT -> "sharp left"
            LaneDirection.LaneShape.SHARP_RIGHT -> "sharp right"
            LaneDirection.LaneShape.U_TURN_LEFT,
            LaneDirection.LaneShape.U_TURN_RIGHT,
            -> "uturn"
            LaneDirection.LaneShape.STRAIGHT -> "straight"
            else -> "straight"
        }
    }

    companion object {
        private const val TAG = "GuidanceSessionManager"
        private val HIGHWAY_TYPES = setOf("on ramp", "off ramp", "fork", "merge")
    }
}

private data class GuidanceSnapshot(
    val routeId: String?,
    val navInfo: NavigationFeedSnapshot?,
    val tripProgress: NavigationTripProgressSnapshot?,
    val isOffRoute: Boolean,
)
