package me.matsumo.onenavi.core.navigation

import android.content.Context
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
import kotlinx.coroutines.launch
import me.matsumo.onenavi.core.model.ArrivalInfo
import me.matsumo.onenavi.core.model.GoogleRoute
import me.matsumo.onenavi.core.model.GuidanceUiState
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
import me.matsumo.onenavi.core.navigation.guidance.SessionGuideEvent
import me.matsumo.onenavi.core.navigation.guidance.SessionGuideKind
import me.matsumo.onenavi.core.navigation.guidance.TurnGuideEvent
import me.matsumo.onenavi.core.navigation.guidance.TurnTiming
import me.matsumo.onenavi.core.navigation.tts.AndroidTtsEngine
import me.matsumo.onenavi.core.navigation.tts.AudioFocusManager
import me.matsumo.onenavi.core.navigation.tts.SpeechOrchestrator

/**
 * Google Routes API のルート情報を使ってナビゲーション UI / TTS 状態を管理する。
 */
class GuidanceSessionManager(
    private val context: Context,
    private val cameraManager: CameraManager,
    private val routeManager: RouteManager,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _navigationState = MutableStateFlow<NavigationState>(NavigationState.Browsing)

    /** 現在のナビゲーション状態。 */
    val navigationState: StateFlow<NavigationState> = _navigationState.asStateFlow()

    private val _guidanceUiState = MutableStateFlow(GuidanceUiState.Initial)

    /** ナビ中の UI 表示データ。 */
    val guidanceUiState: StateFlow<GuidanceUiState> = _guidanceUiState.asStateFlow()

    private val _arrivalInfo = MutableStateFlow<ArrivalInfo?>(null)

    /** 到着時の集計情報。 */
    val arrivalInfo: StateFlow<ArrivalInfo?> = _arrivalInfo.asStateFlow()

    private val _guidanceEvents = MutableSharedFlow<GuidanceEvent>(extraBufferCapacity = 32)

    /** UI / ログが購読できる構造化案内イベント。 */
    val guidanceEvents: SharedFlow<GuidanceEvent> = _guidanceEvents.asSharedFlow()

    private var progressJob: Job? = null
    private var speechOrchestrator: SpeechOrchestrator? = null
    private val phraseComposer = JapaneseGuidancePhraseComposer()
    private val speechHistory = GuidanceSpeechHistory()

    private var sessionStartTimeMillis: Long = 0L
    private var activeRoute: GoogleRoute? = null
    private var lastSpokenStepIndex: Int = -1

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

        speechOrchestrator = SpeechOrchestrator(
            ttsEngine = AndroidTtsEngine(
                context = context,
                audioFocusManager = AudioFocusManager(context),
            ),
            speechHistory = speechHistory,
        )

        _navigationState.value = NavigationState.ActiveGuidance
        _guidanceUiState.value = GuidanceUiState.Initial.copy(isTtsAvailable = false)
        speak(sessionEvent(route.id, SessionGuideKind.START), flush = true)
        cameraManager.requestCameraFollowing(pitch3D = true)

        progressJob?.cancel()
        progressJob = scope.launch {
            while (_navigationState.value == NavigationState.ActiveGuidance) {
                updateProgress(route)
                delay(PROGRESS_INTERVAL_MS)
            }
        }
    }

    fun stopSession() {
        progressJob?.cancel()
        progressJob = null
        activeRoute?.let { speak(sessionEvent(it.id, SessionGuideKind.STOP), flush = true) }
        speechOrchestrator?.shutdown()
        speechOrchestrator = null
        activeRoute = null

        _guidanceUiState.value = GuidanceUiState.Initial
        _arrivalInfo.value = null
        lastSpokenStepIndex = -1
    }

    fun returnToBrowsing() {
        _navigationState.value = NavigationState.Browsing
    }

    fun setTtsMuted(muted: Boolean) {
        speechOrchestrator?.setMuted(muted)
    }

    private fun updateProgress(route: GoogleRoute) {
        val location = cameraManager.currentLocation.value ?: route.origin
        val traveledDistance = route.itemProgressDistance(location)
        val distanceRemaining = (route.distanceMeters - traveledDistance).coerceAtLeast(0.0)
        val durationRemaining = if (route.distanceMeters > 0.0) {
            route.durationSeconds * (distanceRemaining / route.distanceMeters)
        } else {
            0.0
        }

        if (distanceRemaining <= ARRIVAL_THRESHOLD_METERS) {
            onFinalDestinationArrival(route)
            return
        }

        val steps = route.steps
        val currentStepIndex = steps.indexOfFirst { it.cumulativeDistanceMeters >= traveledDistance }
            .takeIf { it >= 0 }
            ?: steps.lastIndex
        val currentStep = steps.getOrNull(currentStepIndex)
        val nextStep = steps.getOrNull(currentStepIndex + 1)

        val currentManeuver = currentStep?.toManeuverInfo(
            distanceMeters = (currentStep.cumulativeDistanceMeters - traveledDistance).coerceAtLeast(0.0),
        )
        val nextManeuver = nextStep?.toManeuverInfo(
            distanceMeters = (nextStep.cumulativeDistanceMeters - traveledDistance).coerceAtLeast(0.0),
        )
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
            currentRoadName = currentStep?.roadName?.takeIf { it.isNotBlank() },
            isOffRoute = false,
            isTtsAvailable = speechOrchestrator != null,
            isLocationStale = false,
        )

        if (currentStep != null && currentStepIndex != lastSpokenStepIndex) {
            lastSpokenStepIndex = currentStepIndex
            speak(currentStep.toTurnEvent(route.id, currentStepIndex, currentManeuver?.distanceMeters ?: 0.0))
        }
    }

    private fun onFinalDestinationArrival(route: GoogleRoute) {
        val elapsedSeconds = (System.currentTimeMillis() - sessionStartTimeMillis) / 1000.0
        _arrivalInfo.value = ArrivalInfo(
            destinationName = "",
            totalDistanceMeters = route.distanceMeters,
            totalDurationSeconds = elapsedSeconds,
        )
        _navigationState.value = NavigationState.Arrival
    }

    private fun GoogleRoute.itemProgressDistance(location: RoutePoint): Double {
        if (distanceMeters <= 0.0 || geometry.isEmpty()) return 0.0

        var distanceToNearest = Double.MAX_VALUE
        var distanceAtNearest = 0.0
        var cumulative = 0.0
        var previous = geometry.first()

        geometry.forEachIndexed { index, point ->
            if (index > 0) {
                cumulative += previous.distanceTo(point)
            }

            val distanceToPoint = location.distanceTo(point)
            if (distanceToPoint < distanceToNearest) {
                distanceToNearest = distanceToPoint
                distanceAtNearest = cumulative
            }
            previous = point
        }

        return distanceAtNearest.coerceAtMost(distanceMeters)
    }

    private fun RouteStepInfo.toManeuverInfo(distanceMeters: Double): ManeuverInfo {
        return ManeuverInfo(
            type = maneuverType,
            modifier = modifier,
            drivingSide = "left",
            distanceMeters = distanceMeters,
            instruction = instruction,
            roadName = roadName.takeIf { it.isNotBlank() },
            destinations = null,
            lanes = persistentListOf(),
        )
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

    companion object {
        private const val TAG = "GuidanceSessionManager"
        private const val PROGRESS_INTERVAL_MS = 1000L
        private const val ARRIVAL_THRESHOLD_METERS = 50.0
    }
}
