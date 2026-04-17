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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.matsumo.onenavi.core.model.ArrivalInfo
import me.matsumo.onenavi.core.model.GoogleRoute
import me.matsumo.onenavi.core.model.GuidanceUiState
import me.matsumo.onenavi.core.model.LaneInfo
import me.matsumo.onenavi.core.model.ManeuverInfo
import me.matsumo.onenavi.core.model.NavigationState
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

    private var guidanceJob: Job? = null
    private var arrivalJob: Job? = null
    private var speechOrchestrator: SpeechOrchestrator? = null
    private var ttsEngine: AndroidTtsEngine? = null
    private val phraseComposer = JapaneseGuidancePhraseComposer()
    private val speechHistory = GuidanceSpeechHistory()

    private var sessionStartTimeMillis: Long = 0L
    private var activeRoute: GoogleRoute? = null
    private var lastSpokenStep: NavigationStepSnapshot? = null
    private var spokenStepCounter: Int = 0
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
        lastSpokenStep = null
        spokenStepCounter = 0
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
                        "Navigation SDK guidance failed to start."
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

        guidanceJob?.cancel()
        guidanceJob = combine(
            navigationSdkManager.navInfo,
            navigationSdkManager.tripProgress,
            navigationSdkManager.isOffRoute,
        ) { navInfo, tripProgress, isOffRouteRaw ->
            GuidanceUpdate(
                navInfo = navInfo,
                tripProgress = tripProgress,
                isOffRouteRaw = isOffRouteRaw,
            )
        }
            .onEach { update -> applyGuidanceUpdate(route.id, update) }
            .launchIn(scope)
    }

    fun stopSession() {
        guidanceJob?.cancel()
        guidanceJob = null
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
        lastSpokenStep = null
        spokenStepCounter = 0
        lastOffRoute = false
    }

    fun returnToBrowsing() {
        _navigationState.value = NavigationState.Browsing
    }

    fun setTtsMuted(muted: Boolean) {
        speechOrchestrator?.setMuted(muted)
    }

    private fun applyGuidanceUpdate(
        routeId: String,
        update: GuidanceUpdate,
    ) {
        if (activeRoute?.id != routeId) return

        val navInfo = update.navInfo
        val tripProgress = update.tripProgress
        val navState = navInfo?.navState
        val isOffRoute = update.isOffRouteRaw || navState == NavState.REROUTING
        maybeSpeakReroute(routeId, isOffRoute)

        // NavInfo のマニューバ関連フィールドは navState == ENROUTE の時だけ有効。
        // REROUTING / STOPPED / UNKNOWN では前回値を維持し、補助フラグのみ更新する。
        if (navState != NavState.ENROUTE) {
            _guidanceUiState.value = _guidanceUiState.value.copy(
                isOffRoute = isOffRoute,
                isTtsAvailable = ttsEngine?.isReady?.value == true,
            )
            return
        }

        val currentStep = navInfo.currentStep
        val nextStep = navInfo.remainingSteps.firstOrNull()

        val currentStepDistance = navInfo.distanceToCurrentStepMeters?.toDouble() ?: 0.0
        val nextStepDistance = currentStepDistance +
            (nextStep?.distanceFromPreviousMeters?.toDouble() ?: 0.0)

        val currentManeuver = currentStep?.toManeuverInfo(distanceMeters = currentStepDistance)
        val nextManeuver = nextStep?.toManeuverInfo(distanceMeters = nextStepDistance)

        val distanceRemaining = tripProgress?.distanceRemainingMeters?.toDouble() ?: 0.0
        val durationRemaining = tripProgress?.timeRemainingSeconds?.toDouble() ?: 0.0

        _guidanceUiState.value = _guidanceUiState.value.copy(
            currentManeuver = currentManeuver,
            nextManeuver = nextManeuver,
            upcomingSteps = persistentListOf(),
            tripProgress = TripProgressInfo(
                distanceRemainingMeters = distanceRemaining,
                durationRemainingSeconds = durationRemaining,
                estimatedArrivalTimeMillis = System.currentTimeMillis() + (durationRemaining * 1000).toLong(),
            ),
            currentRoadName = currentManeuver?.roadName
                ?: currentStep?.roadName?.takeIf { it.isNotBlank() },
            isOffRoute = false,
            isTtsAvailable = ttsEngine?.isReady?.value == true,
            isLocationStale = false,
        )

        if (currentStep != null && currentStep != lastSpokenStep) {
            lastSpokenStep = currentStep
            spokenStepCounter += 1
            speak(
                currentStep.toTurnEvent(
                    routeId = routeId,
                    stepIndex = spokenStepCounter,
                    distanceMeters = currentStepDistance,
                ),
            )
        }
    }

    private fun onFinalDestinationArrival(route: GoogleRoute?) {
        val arrivedRoute = route ?: return
        val elapsedSeconds = (System.currentTimeMillis() - sessionStartTimeMillis) / 1000.0
        _arrivalInfo.value = ArrivalInfo(
            destinationName = "",
            totalDistanceMeters = arrivedRoute.distanceMeters,
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

    private fun NavigationStepSnapshot.toManeuverInfo(distanceMeters: Double): ManeuverInfo {
        return ManeuverInfo(
            type = maneuver.toManeuverType(),
            modifier = maneuver.toModifier(),
            degrees = null,
            drivingSide = drivingSide.toDrivingSideString(),
            distanceMeters = distanceMeters,
            instruction = instruction,
            roadName = roadName?.takeIf { it.isNotBlank() },
            simpleRoadName = simpleRoadName?.takeIf { it.isNotBlank() },
            destinations = null,
            lanes = toLaneInfos(),
        )
    }

    private fun NavigationStepSnapshot.toLaneInfos() = lanes.map { lane ->
        LaneInfo(
            directions = lane.directions.mapNotNull { it.toLaneDirectionString() }.toImmutableList(),
            activeDirection = lane.activeDirection?.toLaneDirectionString(),
            isRecommended = lane.isRecommended,
        )
    }.toImmutableList()

    private fun NavigationStepSnapshot.toTurnEvent(
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
            direction = maneuver.toModifier().toDirection(),
            timing = when {
                distanceMeters <= 120.0 -> TurnTiming.SOON
                distanceMeters <= 500.0 -> TurnTiming.MIDDLE
                else -> TurnTiming.FAR
            },
            roadName = roadName?.takeIf { it.isNotBlank() },
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

    /**
     * Navigation SDK からの 3 系統の Flow を一度に受け取るための中間ホルダー。
     *
     * @param navInfo turn-by-turn feed の最新スナップショット
     * @param tripProgress SDK が算出する残距離・残時間
     * @param isOffRouteRaw 経路逸脱フラグ（REROUTING 状態はここで合成される前の生値）
     */
    private data class GuidanceUpdate(
        val navInfo: NavigationFeedSnapshot?,
        val tripProgress: NavigationTripProgressSnapshot?,
        val isOffRouteRaw: Boolean,
    )

    companion object {
        private const val TAG = "GuidanceSessionManager"
        private const val TTS_READY_TIMEOUT_MS = 3_000L
    }
}
