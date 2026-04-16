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
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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

class GuidanceSessionManager(
    private val context: Context,
    private val cameraManager: CameraManager,
    private val routeManager: RouteManager,
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
    private var speechOrchestrator: SpeechOrchestrator? = null
    private var ttsEngine: AndroidTtsEngine? = null
    private val phraseComposer = JapaneseGuidancePhraseComposer()
    private val speechHistory = GuidanceSpeechHistory()

    private var sessionStartTimeMillis: Long = 0L
    private var activeRoute: GoogleRoute? = null
    private var lastSpokenStepIndex: Int = -1
    private var lastTraveledDistanceMeters: Double = 0.0

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
            val isReady = withTimeoutOrNull(TTS_READY_TIMEOUT_MS) {
                engine.isReady.filter { it }.first()
            } != null
            if (isReady && activeRoute?.id == route.id) {
                speak(sessionEvent(route.id, SessionGuideKind.START), flush = true)
            }
        }
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
        ttsEngine = null
        activeRoute = null

        _guidanceUiState.value = GuidanceUiState.Initial
        _arrivalInfo.value = null
        lastSpokenStepIndex = -1
        lastTraveledDistanceMeters = 0.0
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
            .coerceAtLeast(lastTraveledDistanceMeters)
            .coerceAtMost(route.distanceMeters)
        lastTraveledDistanceMeters = traveledDistance
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
        val currentStepIndex = steps.indexOfLast { it.cumulativeDistanceMeters <= traveledDistance }
            .takeIf { it >= 0 }
            ?: 0
        val currentStep = steps.getOrNull(currentStepIndex)
        val visibleUpcomingSteps = steps
            .withIndex()
            .drop(currentStepIndex.coerceAtLeast(0))
            .filter { it.value.isDisplayableManeuver() }
        val currentVisibleStep = visibleUpcomingSteps.getOrNull(0)
        val nextVisibleStep = visibleUpcomingSteps.getOrNull(1)
        val currentManeuver = when {
            currentVisibleStep != null -> currentVisibleStep.value.toManeuverInfo(
                distanceMeters = currentVisibleStep.value.remainingDistanceFrom(traveledDistance),
            )
            else -> null
        }
        val nextManeuver = when {
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
            currentRoadName = currentStep?.roadName?.takeIf { it.isNotBlank() },
            isOffRoute = false,
            isTtsAvailable = ttsEngine?.isReady?.value == true,
            isLocationStale = false,
        )

        if (currentVisibleStep != null && currentVisibleStep.index != lastSpokenStepIndex) {
            lastSpokenStepIndex = currentVisibleStep.index
            speak(
                currentVisibleStep.value.toTurnEvent(
                    routeId = route.id,
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
            drivingSide = "left",
            distanceMeters = distanceMeters,
            instruction = instruction,
            roadName = roadName.takeIf { it.isNotBlank() },
            destinations = null,
            lanes = persistentListOf(),
        )
    }

    private fun RouteStepInfo.isDisplayableManeuver(): Boolean {
        return maneuverType != "depart"
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
