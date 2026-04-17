package me.matsumo.onenavi.core.navigation

import android.content.Context
import com.google.android.libraries.mapsplatform.turnbyturn.model.DrivingSide
import com.google.android.libraries.mapsplatform.turnbyturn.model.LaneDirection
import com.google.android.libraries.mapsplatform.turnbyturn.model.Maneuver
import com.google.android.libraries.mapsplatform.turnbyturn.model.NavState
import io.github.aakira.napier.Napier
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.matsumo.onenavi.core.model.ArrivalInfo
import me.matsumo.onenavi.core.model.GoogleRoute
import me.matsumo.onenavi.core.model.GuidanceUiState
import me.matsumo.onenavi.core.model.LaneInfo
import me.matsumo.onenavi.core.model.ManeuverInfo
import me.matsumo.onenavi.core.model.NavigationState
import me.matsumo.onenavi.core.model.RoutePoint
import me.matsumo.onenavi.core.model.RouteStepInfo
import me.matsumo.onenavi.core.model.TripProgressInfo
import me.matsumo.onenavi.core.navigation.guidance.Direction
import me.matsumo.onenavi.core.navigation.guidance.DistanceBucket
import me.matsumo.onenavi.core.navigation.guidance.GuidanceEvent
import me.matsumo.onenavi.core.navigation.guidance.GuidanceEventId
import me.matsumo.onenavi.core.navigation.guidance.GuidancePriority
import me.matsumo.onenavi.core.navigation.guidance.GuidanceSpeechHistory
import me.matsumo.onenavi.core.navigation.guidance.GuideCategory
import me.matsumo.onenavi.core.navigation.guidance.JapaneseGuidancePhraseComposer
import me.matsumo.onenavi.core.navigation.guidance.RerouteGuideEvent
import me.matsumo.onenavi.core.navigation.guidance.SessionGuideEvent
import me.matsumo.onenavi.core.navigation.guidance.SessionGuideKind
import me.matsumo.onenavi.core.navigation.guidance.TurnGuideEvent
import me.matsumo.onenavi.core.navigation.guidance.TurnTiming
import me.matsumo.onenavi.core.navigation.tts.AndroidTtsEngine
import me.matsumo.onenavi.core.navigation.tts.AudioFocusManager
import me.matsumo.onenavi.core.navigation.tts.SpeechOrchestrator
import kotlin.time.Duration.Companion.milliseconds

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

    private var progressJob: Job? = null
    private var arrivalJob: Job? = null
    private var speechOrchestrator: SpeechOrchestrator? = null
    private var ttsEngine: AndroidTtsEngine? = null
    private val phraseComposer = JapaneseGuidancePhraseComposer()
    private val speechHistory = GuidanceSpeechHistory()

    private var sessionStartTimeMillis: Long = 0L
    private var activeRoute: GoogleRoute? = null
    private var lastSpokenStepIndex: Int = -1
    private var lastTraveledDistanceMeters: Double = 0.0
    private var lastOffRoute: Boolean = false

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

        activeRoute = route
        sessionStartTimeMillis = System.currentTimeMillis()
        lastSpokenStepIndex = -1
        lastTraveledDistanceMeters = 0.0
        lastOffRoute = false
        speechHistory.resetForNewRoute(route.id)

        val engine = AndroidTtsEngine(
            context = context,
            audioFocusManager = AudioFocusManager(context),
        ).also { createdEngine ->
            createdEngine.onReadyChanged = { ready ->
                _guidanceUiState.value = _guidanceUiState.value.copy(isTtsAvailable = ready)
            }
        }
        ttsEngine = engine
        speechOrchestrator = SpeechOrchestrator(
            ttsEngine = engine,
            speechHistory = speechHistory,
        )

        _navigationState.value = NavigationState.ActiveGuidance
        _guidanceUiState.value = GuidanceUiState.Initial.copy(isTtsAvailable = false)

        scope.launch {
            navigationSdkManager.startNavigation(route)
                .onFailure { throwable ->
                    Napier.e(tag = TAG, throwable = throwable) {
                        "Navigation SDK guidance failed to start. Fallback to route-only guidance."
                    }
                }
        }

        arrivalJob?.cancel()
        arrivalJob = scope.launch {
            navigationSdkManager.arrivalEvents.collect { arrival ->
                if (activeRoute?.id != route.id) return@collect

                if (arrival.isFinalDestination) {
                    onFinalDestinationArrival(activeRoute)
                } else {
                    navigationSdkManager.continueToNextDestination()
                }
            }
        }

        scope.launch {
            val isReady = withTimeoutOrNull(TTS_READY_TIMEOUT_MS.milliseconds) { engine.isReady.first { it } } != null
            if (isReady && activeRoute?.id == route.id) {
                speak(sessionEvent(route.id, SessionGuideKind.START), flush = true)
            }
        }
        cameraManager.requestCameraFollowing(pitch3D = true)

        progressJob?.cancel()
        progressJob = scope.launch {
            while (_navigationState.value == NavigationState.ActiveGuidance) {
                updateProgress(route)
                delay(PROGRESS_INTERVAL_MS.milliseconds)
            }
        }
    }

    fun stopSession() {
        progressJob?.cancel()
        progressJob = null
        arrivalJob?.cancel()
        arrivalJob = null
        activeRoute?.let { speak(sessionEvent(it.id, SessionGuideKind.STOP), flush = true) }
        navigationSdkManager.stopNavigation()
        speechOrchestrator?.shutdown()
        speechOrchestrator = null
        ttsEngine = null
        activeRoute = null

        _guidanceUiState.value = GuidanceUiState.Initial
        _arrivalInfo.value = null
        lastSpokenStepIndex = -1
        lastTraveledDistanceMeters = 0.0
        lastOffRoute = false
    }

    fun returnToBrowsing() {
        _navigationState.value = NavigationState.Browsing
    }

    fun setTtsMuted(muted: Boolean) {
        speechOrchestrator?.setMuted(muted)
    }

    private fun updateProgress(route: GoogleRoute) {
        val resolvedRoute = routeManager.routes.value.firstOrNull()
            ?.takeIf { it.id == route.id }
            ?: activeRoute
            ?: route
        activeRoute = resolvedRoute

        val location = cameraManager.currentLocation.value ?: resolvedRoute.origin
        val traveledDistance = resolvedRoute.itemProgressDistance(location)
            .coerceAtLeast(lastTraveledDistanceMeters)
            .coerceAtMost(resolvedRoute.distanceMeters)
        lastTraveledDistanceMeters = traveledDistance

        val routeDistanceRemaining = (resolvedRoute.distanceMeters - traveledDistance).coerceAtLeast(0.0)
        val routeDurationRemaining = if (resolvedRoute.distanceMeters > 0.0) {
            resolvedRoute.durationSeconds * (routeDistanceRemaining / resolvedRoute.distanceMeters)
        } else {
            0.0
        }

        val navInfo = navigationSdkManager.navInfo.value
        val sdkTripProgress = navigationSdkManager.tripProgress.value
        val isOffRoute = navigationSdkManager.isOffRoute.value || navInfo?.navState == NavState.REROUTING
        maybeSpeakReroute(resolvedRoute.id, isOffRoute)

        if (sdkTripProgress == null && routeDistanceRemaining <= ARRIVAL_THRESHOLD_METERS) {
            onFinalDestinationArrival(resolvedRoute)
            return
        }

        val distanceRemaining = sdkTripProgress?.distanceRemainingMeters?.toDouble() ?: routeDistanceRemaining
        val durationRemaining = sdkTripProgress?.timeRemainingSeconds?.toDouble() ?: routeDurationRemaining

        val steps = resolvedRoute.steps
        val currentStepIndex = steps.indexOfLast { it.cumulativeDistanceMeters <= traveledDistance }
            .takeIf { it >= 0 }
            ?: 0
        val currentStep = steps.getOrNull(currentStepIndex)
        val routeVisibleUpcomingSteps = steps
            .withIndex()
            .drop(currentStepIndex.coerceAtLeast(0))
            .filter { it.value.isDisplayableManeuver() }
        val sdkCurrentStep = navInfo?.currentStep?.takeIf { it.isDisplayableManeuver() }
        val sdkNextStep = navInfo?.remainingSteps
            .orEmpty()
            .firstOrNull { it.isDisplayableManeuver() }

        val currentVisibleStep = routeVisibleUpcomingSteps.getOrNull(0)
        val nextVisibleStep = routeVisibleUpcomingSteps.getOrNull(1)

        val currentManeuver = when {
            sdkCurrentStep != null -> sdkCurrentStep.toManeuverInfo(
                distanceMeters = sdkCurrentStep.distanceFromCurrentLocation(navInfo),
            )
            currentVisibleStep != null -> currentVisibleStep.value.toManeuverInfo(
                distanceMeters = currentVisibleStep.value.remainingDistanceFrom(traveledDistance),
            )
            else -> null
        }
        val nextManeuver = when {
            sdkNextStep != null -> sdkNextStep.toManeuverInfo(
                distanceMeters = sdkNextStep.distanceFromCurrentLocation(navInfo),
            )
            nextVisibleStep != null -> nextVisibleStep.value.toManeuverInfo(
                distanceMeters = nextVisibleStep.value.remainingDistanceFrom(traveledDistance),
            )
            else -> null
        }
        val upcomingSteps = steps.drop(currentStepIndex.coerceAtLeast(0)).toImmutableList()

        _guidanceUiState.value = _guidanceUiState.value.copy(
            currentManeuver = currentManeuver,
            nextManeuver = nextManeuver,
            upcomingSteps = upcomingSteps,
            tripProgress = TripProgressInfo(
                distanceRemainingMeters = distanceRemaining,
                durationRemainingSeconds = durationRemaining,
                estimatedArrivalTimeMillis = System.currentTimeMillis() + (durationRemaining * 1000).toLong(),
            ),
            currentRoadName = currentManeuver?.roadName
                ?: sdkCurrentStep?.roadName?.takeIf { it.isNotBlank() }
                ?: currentStep?.roadName?.takeIf { it.isNotBlank() },
            isOffRoute = isOffRoute,
            isTtsAvailable = ttsEngine?.isReady?.value == true,
            isLocationStale = false,
        )

        if (!isOffRoute && currentVisibleStep != null && currentVisibleStep.index != lastSpokenStepIndex) {
            lastSpokenStepIndex = currentVisibleStep.index
            speak(
                currentVisibleStep.value.toTurnEvent(
                    routeId = resolvedRoute.id,
                    stepIndex = currentVisibleStep.index,
                    distanceMeters = currentManeuver?.distanceMeters ?: 0.0,
                ),
            )
        }
    }

    private fun onFinalDestinationArrival(route: GoogleRoute?) {
        val activeRoute = route ?: return
        val elapsedSeconds = (System.currentTimeMillis() - sessionStartTimeMillis) / 1000.0
        _arrivalInfo.value = ArrivalInfo(
            destinationName = "",
            totalDistanceMeters = activeRoute.distanceMeters,
            totalDurationSeconds = elapsedSeconds,
        )
        _navigationState.value = NavigationState.Arrival
    }

    private fun maybeSpeakReroute(
        routeId: String,
        isOffRoute: Boolean,
    ) {
        if (isOffRoute == lastOffRoute) return

        lastOffRoute = isOffRoute
        speak(
            rerouteEvent(
                routeId = routeId,
                offRoute = isOffRoute,
            ),
            flush = isOffRoute,
        )
    }

    private fun GoogleRoute.itemProgressDistance(location: RoutePoint): Double {
        if (distanceMeters <= 0.0 || geometry.isEmpty()) return 0.0

        if (geometry.size == 1) {
            return if (location.distanceTo(geometry.first()) <= ARRIVAL_THRESHOLD_METERS) {
                distanceMeters
            } else {
                0.0
            }
        }

        var nearestDistance = Double.MAX_VALUE
        var nearestProgress = 0.0
        var cumulativeDistance = 0.0

        geometry.zipWithNext().forEach { (start, end) ->
            val segmentLength = start.distanceTo(end)
            if (segmentLength <= 0.0) return@forEach

            val projection = location.projectOntoSegment(start, end)
            if (projection.distanceMeters < nearestDistance) {
                nearestDistance = projection.distanceMeters
                nearestProgress = cumulativeDistance + segmentLength * projection.fraction
            }
            cumulativeDistance += segmentLength
        }

        return nearestProgress.coerceIn(0.0, distanceMeters)
    }

    private fun RouteStepInfo.remainingDistanceFrom(traveledDistance: Double): Double {
        return (
            cumulativeDistanceMeters +
                distanceFromPreviousMeters -
                traveledDistance
            ).coerceAtLeast(0.0)
    }

    private fun RouteStepInfo.toManeuverInfo(distanceMeters: Double): ManeuverInfo {
        return ManeuverInfo(
            type = maneuverType,
            modifier = modifier,
            degrees = null,
            drivingSide = "left",
            distanceMeters = distanceMeters,
            instruction = instruction,
            roadName = roadName.takeIf { it.isNotBlank() },
            destinations = null,
            lanes = persistentListOf(),
        )
    }

    private fun NavigationStepSnapshot.toManeuverInfo(distanceMeters: Double): ManeuverInfo {
        val laneGuidanceModifier = preferredLaneDirection()
        val isLaneGuidance = lanes.isNotEmpty() && instruction.contains("車線")
        return ManeuverInfo(
            type = if (isLaneGuidance) "continue" else maneuver.toManeuverType(),
            modifier = if (isLaneGuidance) laneGuidanceModifier ?: maneuver.toModifier() else maneuver.toModifier(),
            degrees = null,
            drivingSide = drivingSide.toDrivingSideString(),
            distanceMeters = distanceMeters,
            instruction = instruction,
            roadName = roadName?.takeIf { it.isNotBlank() },
            destinations = null,
            lanes = toLaneInfos(),
        )
    }

    private fun NavigationStepSnapshot.preferredLaneDirection(): String? {
        return lanes.firstOrNull { it.isRecommended }?.activeDirection?.toLaneDirectionString()
            ?: lanes.firstOrNull { it.isRecommended }?.directions?.firstNotNullOfOrNull { it.toLaneDirectionString() }
            ?: lanes.firstOrNull()?.activeDirection?.toLaneDirectionString()
            ?: lanes.firstOrNull()?.directions?.firstNotNullOfOrNull { it.toLaneDirectionString() }
    }

    private fun NavigationStepSnapshot.toLaneInfos() = lanes.map { lane ->
        LaneInfo(
            directions = lane.directions.mapNotNull { it.toLaneDirectionString() }.toImmutableList(),
            activeDirection = lane.activeDirection?.toLaneDirectionString(),
            isRecommended = lane.isRecommended,
        )
    }.toImmutableList()

    private fun RouteStepInfo.isDisplayableManeuver(): Boolean {
        return maneuverType != "depart"
    }

    private fun NavigationFeedSnapshot?.displayableUpcomingSteps(): List<NavigationStepSnapshot> {
        val snapshot = this ?: return emptyList()
        return buildList {
            snapshot.currentStep?.let(::add)
            addAll(snapshot.remainingSteps)
        }.filter { it.isDisplayableManeuver() }
    }

    private fun NavigationStepSnapshot.isDisplayableManeuver(): Boolean {
        return maneuver != Maneuver.DEPART && maneuver != Maneuver.UNKNOWN
    }

    private fun NavigationStepSnapshot.distanceFromCurrentLocation(navInfo: NavigationFeedSnapshot?): Double {
        val snapshot = navInfo ?: return 0.0
        if (snapshot.currentStep == this) {
            return snapshot.distanceToCurrentStepMeters.toDouble()
        }
        return snapshot.distanceToCurrentStepMeters.toDouble() + remainingStepsBeforeThis(snapshot)
    }

    private fun NavigationStepSnapshot.remainingStepsBeforeThis(snapshot: NavigationFeedSnapshot): Double {
        var distance = 0.0
        for (step in snapshot.remainingSteps) {
            if (step == this) break
            distance += step.distanceFromPreviousMeters ?: 0
        }
        return distance
    }

    private fun RouteStepInfo.toTurnEvent(
        routeId: String,
        stepIndex: Int,
        distanceMeters: Double,
    ): TurnGuideEvent {
        return TurnGuideEvent(
            id = GuidanceEventId(
                routeId = routeId,
                category = GuideCategory.TURN,
                legIndex = 0,
                stepIndex = stepIndex,
                geometryIndex = null,
                distanceBucket = DistanceBucket.fromMeters(distanceMeters),
                variant = instruction,
            ),
            priority = GuidancePriority.NORMAL,
            distanceMeters = distanceMeters,
            direction = modifier.toDirection(),
            timing = when {
                distanceMeters <= 120.0 -> TurnTiming.SOON
                distanceMeters <= 500.0 -> TurnTiming.MIDDLE
                else -> TurnTiming.FAR
            },
            roadName = roadName,
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
                variant = if (offRoute) "off_route" else "rerouted",
            ),
            priority = GuidancePriority.HIGH,
            offRoute = offRoute,
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

    private fun String?.toDirection(): Direction {
        return when (this) {
            "left" -> Direction.LEFT
            "right" -> Direction.RIGHT
            "slight left" -> Direction.SLIGHT_LEFT
            "slight right" -> Direction.SLIGHT_RIGHT
            "sharp left" -> Direction.SHARP_LEFT
            "sharp right" -> Direction.SHARP_RIGHT
            "straight" -> Direction.STRAIGHT
            "uturn" -> Direction.UTURN
            else -> Direction.UNKNOWN
        }
    }

    private fun Int.toDrivingSideString(): String? {
        return when (this) {
            DrivingSide.LEFT -> "left"
            DrivingSide.RIGHT -> "right"
            else -> null
        }
    }

    private fun Int.toManeuverType(): String {
        return when (this) {
            Maneuver.DEPART -> "depart"
            Maneuver.DESTINATION,
            Maneuver.DESTINATION_LEFT,
            Maneuver.DESTINATION_RIGHT,
            -> "arrive"
            Maneuver.FORK_LEFT,
            Maneuver.FORK_RIGHT,
            -> "fork"
            Maneuver.MERGE_UNSPECIFIED,
            Maneuver.MERGE_LEFT,
            Maneuver.MERGE_RIGHT,
            -> "merge"
            Maneuver.ON_RAMP_UNSPECIFIED,
            Maneuver.ON_RAMP_LEFT,
            Maneuver.ON_RAMP_RIGHT,
            Maneuver.ON_RAMP_KEEP_LEFT,
            Maneuver.ON_RAMP_KEEP_RIGHT,
            Maneuver.ON_RAMP_SLIGHT_LEFT,
            Maneuver.ON_RAMP_SLIGHT_RIGHT,
            Maneuver.ON_RAMP_SHARP_LEFT,
            Maneuver.ON_RAMP_SHARP_RIGHT,
            Maneuver.ON_RAMP_U_TURN_CLOCKWISE,
            Maneuver.ON_RAMP_U_TURN_COUNTERCLOCKWISE,
            -> "on ramp"
            Maneuver.OFF_RAMP_UNSPECIFIED,
            Maneuver.OFF_RAMP_LEFT,
            Maneuver.OFF_RAMP_RIGHT,
            Maneuver.OFF_RAMP_KEEP_LEFT,
            Maneuver.OFF_RAMP_KEEP_RIGHT,
            Maneuver.OFF_RAMP_SLIGHT_LEFT,
            Maneuver.OFF_RAMP_SLIGHT_RIGHT,
            Maneuver.OFF_RAMP_SHARP_LEFT,
            Maneuver.OFF_RAMP_SHARP_RIGHT,
            Maneuver.OFF_RAMP_U_TURN_CLOCKWISE,
            Maneuver.OFF_RAMP_U_TURN_COUNTERCLOCKWISE,
            -> "off ramp"
            Maneuver.ROUNDABOUT_CLOCKWISE,
            Maneuver.ROUNDABOUT_COUNTERCLOCKWISE,
            Maneuver.ROUNDABOUT_STRAIGHT_CLOCKWISE,
            Maneuver.ROUNDABOUT_STRAIGHT_COUNTERCLOCKWISE,
            Maneuver.ROUNDABOUT_LEFT_CLOCKWISE,
            Maneuver.ROUNDABOUT_LEFT_COUNTERCLOCKWISE,
            Maneuver.ROUNDABOUT_RIGHT_CLOCKWISE,
            Maneuver.ROUNDABOUT_RIGHT_COUNTERCLOCKWISE,
            Maneuver.ROUNDABOUT_SLIGHT_LEFT_CLOCKWISE,
            Maneuver.ROUNDABOUT_SLIGHT_LEFT_COUNTERCLOCKWISE,
            Maneuver.ROUNDABOUT_SLIGHT_RIGHT_CLOCKWISE,
            Maneuver.ROUNDABOUT_SLIGHT_RIGHT_COUNTERCLOCKWISE,
            Maneuver.ROUNDABOUT_SHARP_LEFT_CLOCKWISE,
            Maneuver.ROUNDABOUT_SHARP_LEFT_COUNTERCLOCKWISE,
            Maneuver.ROUNDABOUT_SHARP_RIGHT_CLOCKWISE,
            Maneuver.ROUNDABOUT_SHARP_RIGHT_COUNTERCLOCKWISE,
            Maneuver.ROUNDABOUT_U_TURN_CLOCKWISE,
            Maneuver.ROUNDABOUT_U_TURN_COUNTERCLOCKWISE,
            Maneuver.ROUNDABOUT_EXIT_CLOCKWISE,
            Maneuver.ROUNDABOUT_EXIT_COUNTERCLOCKWISE,
            -> "roundabout"
            Maneuver.NAME_CHANGE -> "new_name"
            else -> "turn"
        }
    }

    private fun Int.toModifier(): String? {
        return when (this) {
            Maneuver.DESTINATION_LEFT,
            Maneuver.TURN_LEFT,
            Maneuver.TURN_KEEP_LEFT,
            Maneuver.MERGE_LEFT,
            Maneuver.FORK_LEFT,
            Maneuver.ON_RAMP_LEFT,
            Maneuver.ON_RAMP_KEEP_LEFT,
            Maneuver.OFF_RAMP_LEFT,
            Maneuver.OFF_RAMP_KEEP_LEFT,
            Maneuver.ROUNDABOUT_LEFT_CLOCKWISE,
            Maneuver.ROUNDABOUT_LEFT_COUNTERCLOCKWISE,
            Maneuver.ROUNDABOUT_EXIT_CLOCKWISE,
            Maneuver.ROUNDABOUT_EXIT_COUNTERCLOCKWISE,
            -> "left"
            Maneuver.DESTINATION_RIGHT,
            Maneuver.TURN_RIGHT,
            Maneuver.TURN_KEEP_RIGHT,
            Maneuver.MERGE_RIGHT,
            Maneuver.FORK_RIGHT,
            Maneuver.ON_RAMP_RIGHT,
            Maneuver.ON_RAMP_KEEP_RIGHT,
            Maneuver.OFF_RAMP_RIGHT,
            Maneuver.OFF_RAMP_KEEP_RIGHT,
            Maneuver.ROUNDABOUT_RIGHT_CLOCKWISE,
            Maneuver.ROUNDABOUT_RIGHT_COUNTERCLOCKWISE,
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
            Maneuver.STRAIGHT,
            Maneuver.MERGE_UNSPECIFIED,
            Maneuver.ON_RAMP_UNSPECIFIED,
            Maneuver.OFF_RAMP_UNSPECIFIED,
            Maneuver.ROUNDABOUT_STRAIGHT_CLOCKWISE,
            Maneuver.ROUNDABOUT_STRAIGHT_COUNTERCLOCKWISE,
            Maneuver.NAME_CHANGE,
            -> "straight"
            Maneuver.TURN_U_TURN_CLOCKWISE,
            Maneuver.TURN_U_TURN_COUNTERCLOCKWISE,
            Maneuver.ON_RAMP_U_TURN_CLOCKWISE,
            Maneuver.ON_RAMP_U_TURN_COUNTERCLOCKWISE,
            Maneuver.OFF_RAMP_U_TURN_CLOCKWISE,
            Maneuver.OFF_RAMP_U_TURN_COUNTERCLOCKWISE,
            Maneuver.ROUNDABOUT_U_TURN_CLOCKWISE,
            Maneuver.ROUNDABOUT_U_TURN_COUNTERCLOCKWISE,
            -> "uturn"
            else -> null
        }
    }

    private fun Int.toLaneDirectionString(): String? {
        return when (this) {
            LaneDirection.LaneShape.STRAIGHT -> "straight"
            LaneDirection.LaneShape.SLIGHT_LEFT -> "slight left"
            LaneDirection.LaneShape.SLIGHT_RIGHT -> "slight right"
            LaneDirection.LaneShape.NORMAL_LEFT -> "left"
            LaneDirection.LaneShape.NORMAL_RIGHT -> "right"
            LaneDirection.LaneShape.SHARP_LEFT -> "sharp left"
            LaneDirection.LaneShape.SHARP_RIGHT -> "sharp right"
            LaneDirection.LaneShape.U_TURN_LEFT,
            LaneDirection.LaneShape.U_TURN_RIGHT,
            -> "uturn"
            else -> null
        }
    }

    private fun RoutePoint.distanceTo(other: RoutePoint): Double {
        val radius = 6_371_000.0
        val dLat = Math.toRadians(other.latitude - latitude)
        val dLng = Math.toRadians(other.longitude - longitude)
        val lat1 = Math.toRadians(latitude)
        val lat2 = Math.toRadians(other.latitude)
        val sinLat = kotlin.math.sin(dLat / 2)
        val sinLng = kotlin.math.sin(dLng / 2)
        val a = sinLat * sinLat + sinLng * sinLng * kotlin.math.cos(lat1) * kotlin.math.cos(lat2)
        val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
        return radius * c
    }

    private fun RoutePoint.projectOntoSegment(
        start: RoutePoint,
        end: RoutePoint,
    ): SegmentProjection {
        val radius = 6_371_000.0
        val originLatitudeRad = Math.toRadians(start.latitude)
        val originLongitudeRad = Math.toRadians(start.longitude)
        val cosLatitude = kotlin.math.cos(
            Math.toRadians((start.latitude + end.latitude + latitude) / 3.0),
        )

        fun RoutePoint.toLocalVector(): Pair<Double, Double> {
            val x = (Math.toRadians(longitude) - originLongitudeRad) * radius * cosLatitude
            val y = (Math.toRadians(latitude) - originLatitudeRad) * radius
            return x to y
        }

        val (endX, endY) = end.toLocalVector()
        val (pointX, pointY) = toLocalVector()
        val segmentLengthSquared = endX * endX + endY * endY
        if (segmentLengthSquared <= 0.0) {
            return SegmentProjection(
                fraction = 0.0,
                distanceMeters = distanceTo(start),
            )
        }

        val fraction = ((pointX * endX) + (pointY * endY)) / segmentLengthSquared
        val clampedFraction = fraction.coerceIn(0.0, 1.0)
        val projectedX = endX * clampedFraction
        val projectedY = endY * clampedFraction
        val dx = pointX - projectedX
        val dy = pointY - projectedY

        return SegmentProjection(
            fraction = clampedFraction,
            distanceMeters = kotlin.math.sqrt(dx * dx + dy * dy),
        )
    }

    private data class SegmentProjection(
        val fraction: Double,
        val distanceMeters: Double,
    )

    companion object {
        private const val TAG = "GuidanceSessionManager"
        private const val PROGRESS_INTERVAL_MS = 1000L
        private const val ARRIVAL_THRESHOLD_METERS = 50.0
        private const val TTS_READY_TIMEOUT_MS = 3_000L
    }
}
