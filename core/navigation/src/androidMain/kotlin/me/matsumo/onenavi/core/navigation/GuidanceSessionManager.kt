package me.matsumo.onenavi.core.navigation

import androidx.compose.ui.graphics.asImageBitmap
import com.google.android.libraries.mapsplatform.turnbyturn.model.LaneDirection
import com.google.android.libraries.mapsplatform.turnbyturn.model.Maneuver
import com.google.android.libraries.mapsplatform.turnbyturn.model.NavState
import io.github.aakira.napier.Napier
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import me.matsumo.onenavi.core.model.ArrivalInfo
import me.matsumo.onenavi.core.model.DrivingSide
import me.matsumo.onenavi.core.model.GoogleRoute
import me.matsumo.onenavi.core.model.GuidanceEvent
import me.matsumo.onenavi.core.model.GuidancePriority
import me.matsumo.onenavi.core.model.GuidanceUiState
import me.matsumo.onenavi.core.model.LaneInfo
import me.matsumo.onenavi.core.model.ManeuverInfo
import me.matsumo.onenavi.core.model.ManeuverModifier
import me.matsumo.onenavi.core.model.ManeuverType
import me.matsumo.onenavi.core.model.NavigationState
import me.matsumo.onenavi.core.model.TripProgressInfo
import me.matsumo.onenavi.core.navigation.guidance.GuidanceCoordinator
import me.matsumo.onenavi.core.navigation.guidance.GuidancePlanner
import me.matsumo.onenavi.core.navigation.guidance.PhraseComposer
import me.matsumo.onenavi.core.navigation.guidance.SpeechDispatcher
import me.matsumo.onenavi.core.navigation.tts.SpeechOrchestrator
import me.matsumo.onenavi.core.navigation.tts.TtsEngine
import com.google.android.libraries.mapsplatform.turnbyturn.model.DrivingSide as SdkDrivingSide

class GuidanceSessionManager(
    private val cameraManager: CameraManager,
    private val routeManager: RouteManager,
    private val navigationSdkManager: NavigationSdkManager,
    private val phraseComposer: PhraseComposer,
    private val guidancePlanner: GuidancePlanner,
    private val ttsEngineFactory: () -> TtsEngine,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _navigationState = MutableStateFlow<NavigationState>(NavigationState.Browsing)
    val navigationState: StateFlow<NavigationState> = _navigationState.asStateFlow()

    private val _guidanceUiState = MutableStateFlow(GuidanceUiState.Initial)
    val guidanceUiState: StateFlow<GuidanceUiState> = _guidanceUiState.asStateFlow()

    private val _arrivalInfo = MutableStateFlow<ArrivalInfo?>(null)
    val arrivalInfo: StateFlow<ArrivalInfo?> = _arrivalInfo.asStateFlow()

    private var guidanceJob: Job? = null
    private var arrivalJob: Job? = null
    private var rerouteJob: Job? = null
    private var ttsReadyJob: Job? = null
    private var speechOrchestrator: SpeechOrchestrator? = null
    private var ttsEngine: TtsEngine? = null
    private var dispatcher: SpeechDispatcher? = null
    private var coordinator: GuidanceCoordinator? = null

    private var sessionStartTimeMillis: Long = 0L
    private var activeRoute: GoogleRoute? = null

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

        val engine = ttsEngineFactory()
        ttsEngine = engine
        val orchestrator = SpeechOrchestrator(ttsEngine = engine)
        speechOrchestrator = orchestrator

        val sessionDispatcher = SpeechDispatcher(
            orchestrator = orchestrator,
            composer = phraseComposer,
            scope = scope,
        )
        val sessionCoordinator = GuidanceCoordinator(
            planner = guidancePlanner,
            dispatcher = sessionDispatcher,
        )
        dispatcher = sessionDispatcher
        coordinator = sessionCoordinator

        sessionDispatcher.setEnabled(true)
        sessionDispatcher.start()
        sessionCoordinator.reset()
        sessionCoordinator.emit(GuidanceEvent.SessionStarted(priority = GuidancePriority.CRITICAL))

        _navigationState.value = NavigationState.ActiveGuidance
        _guidanceUiState.value = GuidanceUiState.Initial.copy(isTtsAvailable = engine.isReady.value)

        ttsReadyJob?.cancel()
        ttsReadyJob = scope.launch {
            engine.isReady.collect { ready ->
                _guidanceUiState.value = _guidanceUiState.value.copy(isTtsAvailable = ready)
            }
        }

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
                    coordinator?.emit(
                        GuidanceEvent.DestinationApproach(priority = GuidancePriority.CRITICAL),
                    )
                    onFinalDestinationArrival(activeRoute)
                } else {
                    coordinator?.emit(
                        GuidanceEvent.ViaWaypointApproach(priority = GuidancePriority.CRITICAL),
                    )
                    navigationSdkManager.continueToNextDestination()
                }
            }
        }

        rerouteJob?.cancel()
        rerouteJob = routeManager.routes
            .map { routes -> routes.firstOrNull() }
            .distinctUntilChanged { old, new -> isSameRoute(old, new) }
            .drop(1)
            .onEach { coordinator?.onRerouted() }
            .launchIn(scope)

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
        rerouteJob?.cancel()
        rerouteJob = null
        ttsReadyJob?.cancel()
        ttsReadyJob = null
        navigationSdkManager.stopNavigation()
        dispatcher?.shutdown()
        dispatcher = null
        coordinator?.reset()
        coordinator = null
        speechOrchestrator?.shutdown()
        speechOrchestrator = null
        ttsEngine = null
        activeRoute = null

        _guidanceUiState.value = GuidanceUiState.Initial
        _arrivalInfo.value = null
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

        // OffRoute は navState に依らず常に監視する（REROUTING 中でも発火させる）。
        coordinator?.onOffRouteChanged(isOffRoute)

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
            tripProgress = TripProgressInfo(
                distanceRemainingMeters = distanceRemaining,
                durationRemainingSeconds = durationRemaining,
                estimatedArrivalTimeMillis = System.currentTimeMillis() + (durationRemaining * 1000).toLong(),
            ),
            isOffRoute = false,
            isTtsAvailable = ttsEngine?.isReady?.value == true,
        )

        coordinator?.onNavigationUpdate(navInfo)
    }

    /**
     * リルート検出のためのルート同一性判定。
     * routeToken が両方ある場合はそれで比較し、ない場合は id / geometry.size / 距離 / 所要時間の複合キーで代替する。
     */
    private fun isSameRoute(old: GoogleRoute?, new: GoogleRoute?): Boolean {
        if (old == null || new == null) {
            val result = old === new
            Napier.d(tag = TAG) { "[P4] ROUTE_CMP old=$old new=$new -> same=$result (nullcheck)" }
            return result
        }
        if (old.routeToken != null && new.routeToken != null) {
            val result = old.routeToken == new.routeToken
            Napier.d(tag = TAG) {
                "[P4] ROUTE_CMP oldToken=${old.routeToken} newToken=${new.routeToken} -> same=$result (token)"
            }
            return result
        }
        val result = old.id == new.id &&
            old.geometry.size == new.geometry.size &&
            old.distanceMeters == new.distanceMeters &&
            old.durationSeconds == new.durationSeconds
        Napier.d(tag = TAG) {
            "[P4] ROUTE_CMP oldId=${old.id} newId=${new.id} " +
                "geomEq=${old.geometry.size == new.geometry.size} " +
                "distEq=${old.distanceMeters == new.distanceMeters} " +
                "durEq=${old.durationSeconds == new.durationSeconds} -> same=$result (composite)"
        }
        return result
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

    private fun NavigationStepSnapshot.toManeuverInfo(distanceMeters: Double): ManeuverInfo {
        return ManeuverInfo(
            type = maneuver.toManeuverType(),
            modifier = maneuver.toManeuverModifier(),
            degrees = null,
            drivingSide = drivingSide.toDrivingSide(),
            distanceMeters = distanceMeters,
            instruction = instruction,
            roadName = roadName?.takeIf { it.isNotBlank() },
            simpleRoadName = simpleRoadName?.takeIf { it.isNotBlank() },
            destinations = null,
            iconBitmap = maneuverBitmap?.asImageBitmap(),
            lanesBitmap = lanesBitmap?.asImageBitmap(),
            lanes = toLaneInfos(),
        )
    }

    private fun NavigationStepSnapshot.toLaneInfos() = lanes.map { lane ->
        LaneInfo(
            directions = lane.directions.mapNotNull { it.toLaneManeuverModifier() }.toImmutableList(),
            activeDirection = lane.activeDirection?.toLaneManeuverModifier(),
            isRecommended = lane.isRecommended,
        )
    }.toImmutableList()

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
    }
}

internal fun Int.toManeuverType(): ManeuverType {
    return when (this) {
        Maneuver.DEPART -> ManeuverType.DEPART
        Maneuver.DESTINATION,
        Maneuver.DESTINATION_LEFT,
        Maneuver.DESTINATION_RIGHT,
        -> ManeuverType.ARRIVE
        Maneuver.FORK_LEFT,
        Maneuver.FORK_RIGHT,
        -> ManeuverType.FORK
        Maneuver.MERGE_UNSPECIFIED,
        Maneuver.MERGE_LEFT,
        Maneuver.MERGE_RIGHT,
        -> ManeuverType.MERGE
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
        -> ManeuverType.ON_RAMP
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
        -> ManeuverType.OFF_RAMP
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
        -> ManeuverType.ROUNDABOUT
        Maneuver.NAME_CHANGE -> ManeuverType.NAME_CHANGE
        Maneuver.TURN_U_TURN_CLOCKWISE,
        Maneuver.TURN_U_TURN_COUNTERCLOCKWISE,
        -> ManeuverType.UTURN
        Maneuver.STRAIGHT -> ManeuverType.CONTINUE
        else -> ManeuverType.TURN
    }
}

@Suppress("CyclomaticComplexMethod")
internal fun Int.toManeuverModifier(): ManeuverModifier? {
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
        -> ManeuverModifier.LEFT
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
        -> ManeuverModifier.RIGHT
        Maneuver.TURN_SLIGHT_LEFT,
        Maneuver.ON_RAMP_SLIGHT_LEFT,
        Maneuver.OFF_RAMP_SLIGHT_LEFT,
        Maneuver.ROUNDABOUT_SLIGHT_LEFT_CLOCKWISE,
        Maneuver.ROUNDABOUT_SLIGHT_LEFT_COUNTERCLOCKWISE,
        -> ManeuverModifier.SLIGHT_LEFT
        Maneuver.TURN_SLIGHT_RIGHT,
        Maneuver.ON_RAMP_SLIGHT_RIGHT,
        Maneuver.OFF_RAMP_SLIGHT_RIGHT,
        Maneuver.ROUNDABOUT_SLIGHT_RIGHT_CLOCKWISE,
        Maneuver.ROUNDABOUT_SLIGHT_RIGHT_COUNTERCLOCKWISE,
        -> ManeuverModifier.SLIGHT_RIGHT
        Maneuver.TURN_SHARP_LEFT,
        Maneuver.ON_RAMP_SHARP_LEFT,
        Maneuver.OFF_RAMP_SHARP_LEFT,
        Maneuver.ROUNDABOUT_SHARP_LEFT_CLOCKWISE,
        Maneuver.ROUNDABOUT_SHARP_LEFT_COUNTERCLOCKWISE,
        -> ManeuverModifier.SHARP_LEFT
        Maneuver.TURN_SHARP_RIGHT,
        Maneuver.ON_RAMP_SHARP_RIGHT,
        Maneuver.OFF_RAMP_SHARP_RIGHT,
        Maneuver.ROUNDABOUT_SHARP_RIGHT_CLOCKWISE,
        Maneuver.ROUNDABOUT_SHARP_RIGHT_COUNTERCLOCKWISE,
        -> ManeuverModifier.SHARP_RIGHT
        Maneuver.STRAIGHT,
        Maneuver.MERGE_UNSPECIFIED,
        Maneuver.ON_RAMP_UNSPECIFIED,
        Maneuver.OFF_RAMP_UNSPECIFIED,
        Maneuver.ROUNDABOUT_STRAIGHT_CLOCKWISE,
        Maneuver.ROUNDABOUT_STRAIGHT_COUNTERCLOCKWISE,
        Maneuver.NAME_CHANGE,
        -> ManeuverModifier.STRAIGHT
        Maneuver.TURN_U_TURN_CLOCKWISE,
        Maneuver.TURN_U_TURN_COUNTERCLOCKWISE,
        Maneuver.ON_RAMP_U_TURN_CLOCKWISE,
        Maneuver.ON_RAMP_U_TURN_COUNTERCLOCKWISE,
        Maneuver.OFF_RAMP_U_TURN_CLOCKWISE,
        Maneuver.OFF_RAMP_U_TURN_COUNTERCLOCKWISE,
        Maneuver.ROUNDABOUT_U_TURN_CLOCKWISE,
        Maneuver.ROUNDABOUT_U_TURN_COUNTERCLOCKWISE,
        -> ManeuverModifier.UTURN
        else -> null
    }
}

internal fun Int.toDrivingSide(): DrivingSide? {
    return when (this) {
        SdkDrivingSide.LEFT -> DrivingSide.LEFT
        SdkDrivingSide.RIGHT -> DrivingSide.RIGHT
        else -> null
    }
}

// LaneDirection.LaneShape の Int 値を ManeuverModifier に射影する。
// Maneuver の Int 値とは enum 値空間が異なるため、別関数として用意している。
private fun Int.toLaneManeuverModifier(): ManeuverModifier? {
    return when (this) {
        LaneDirection.LaneShape.STRAIGHT -> ManeuverModifier.STRAIGHT
        LaneDirection.LaneShape.SLIGHT_LEFT -> ManeuverModifier.SLIGHT_LEFT
        LaneDirection.LaneShape.SLIGHT_RIGHT -> ManeuverModifier.SLIGHT_RIGHT
        LaneDirection.LaneShape.NORMAL_LEFT -> ManeuverModifier.LEFT
        LaneDirection.LaneShape.NORMAL_RIGHT -> ManeuverModifier.RIGHT
        LaneDirection.LaneShape.SHARP_LEFT -> ManeuverModifier.SHARP_LEFT
        LaneDirection.LaneShape.SHARP_RIGHT -> ManeuverModifier.SHARP_RIGHT
        LaneDirection.LaneShape.U_TURN_LEFT,
        LaneDirection.LaneShape.U_TURN_RIGHT,
        -> ManeuverModifier.UTURN
        else -> null
    }
}
