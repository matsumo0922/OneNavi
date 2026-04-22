package me.matsumo.onenavi.core.navigation

import android.location.Location
import com.google.android.libraries.navigation.RoadSnappedLocationProvider
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import me.matsumo.drive.supporter.api.guidance.domain.GuidanceCategory
import me.matsumo.drive.supporter.api.guidance.domain.GuidancePoint
import me.matsumo.drive.supporter.api.guidance.domain.Intersection
import me.matsumo.drive.supporter.api.guidance.domain.plainText
import me.matsumo.onenavi.core.model.ArrivalInfo
import me.matsumo.onenavi.core.model.GoogleRoute
import me.matsumo.onenavi.core.model.GuidanceUiState
import me.matsumo.onenavi.core.model.ManeuverInfo
import me.matsumo.onenavi.core.model.ManeuverType
import me.matsumo.onenavi.core.model.NavigationState
import me.matsumo.onenavi.core.model.TripProgressInfo
import me.matsumo.onenavi.core.navigation.extnav.ExtNavAnnouncementScheduler
import me.matsumo.onenavi.core.navigation.extnav.ExtNavGuidanceTracker
import me.matsumo.onenavi.core.navigation.extnav.ExtNavProgressSnapshot
import me.matsumo.onenavi.core.navigation.extnav.ExtNavRerouteDetector
import me.matsumo.onenavi.core.navigation.extnav.ExtNavRouteRegistry
import me.matsumo.onenavi.core.navigation.extnav.ExtNavSsmlSpeaker
import kotlin.math.sqrt

/**
 * drive-supporter-api 由来の案内セッションを制御する。
 *
 * - Navigator (`setDestinations` / `startGuidance`) は呼ばない
 * - `NavigationApi.getNavigator()` は [NavigationSdkManager] 側で初期化済み。
 *   本クラスは [RoadSnappedLocationProvider] からの map-matched 座標を [ExtNavGuidanceTracker]
 *   に流して進捗 / 発話スケジューリング / off-route 判定を回す
 */
class GuidanceSessionManager(
    private val cameraManager: CameraManager,
    private val routeManager: RouteManager,
    private val navigationSdkManager: NavigationSdkManager,
    private val extNavRouteRegistry: ExtNavRouteRegistry,
    private val extNavTrackerProvider: () -> ExtNavGuidanceTracker,
    private val extNavSchedulerProvider: () -> ExtNavAnnouncementScheduler,
    private val extNavRerouteDetectorProvider: () -> ExtNavRerouteDetector,
    private val speakerProvider: () -> ExtNavSsmlSpeaker,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _navigationState = MutableStateFlow<NavigationState>(NavigationState.Browsing)
    val navigationState: StateFlow<NavigationState> = _navigationState.asStateFlow()

    private val _guidanceUiState = MutableStateFlow(GuidanceUiState.Initial)
    val guidanceUiState: StateFlow<GuidanceUiState> = _guidanceUiState.asStateFlow()

    private val _arrivalInfo = MutableStateFlow<ArrivalInfo?>(null)
    val arrivalInfo: StateFlow<ArrivalInfo?> = _arrivalInfo.asStateFlow()

    private var locationJob: Job? = null
    private var rerouteJob: Job? = null

    private var tracker: ExtNavGuidanceTracker? = null
    private var scheduler: ExtNavAnnouncementScheduler? = null
    private var rerouteDetector: ExtNavRerouteDetector? = null
    private var speaker: ExtNavSsmlSpeaker? = null
    private var attachedProvider: RoadSnappedLocationProvider? = null

    private var sessionStartTimeMillis: Long = 0L
    private var activeRoute: GoogleRoute? = null

    private val locationListener = RoadSnappedLocationProvider.LocationListener { location ->
        onLocationUpdated(location)
    }

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
        val payload = extNavRouteRegistry.get(route.id)
        if (payload == null) {
            Napier.w(tag = TAG) { "No ExtNavRoutePayload for route=${route.id}; session not started" }
            return
        }

        activeRoute = route
        sessionStartTimeMillis = System.currentTimeMillis()

        val sessionTracker = extNavTrackerProvider().also { tracker = it }
        val sessionScheduler = extNavSchedulerProvider().also { scheduler = it }
        val sessionDetector = extNavRerouteDetectorProvider().also { rerouteDetector = it }
        val sessionSpeaker = speakerProvider().also { speaker = it }

        sessionTracker.attach(payload.guidance)
        sessionScheduler.reset()
        sessionDetector.reset()

        _navigationState.value = NavigationState.ActiveGuidance
        _guidanceUiState.value = GuidanceUiState.Initial.copy(isTtsAvailable = true)

        sessionSpeaker.speakPlain(
            text = "ルート案内を開始します",
            utteranceId = "session-start-${System.currentTimeMillis()}",
        )

        attachLocationListener()

        rerouteJob?.cancel()
        rerouteJob = routeManager.routes
            .map { routes -> routes.firstOrNull() }
            .distinctUntilChanged { old, new -> isSameRoute(old, new) }
            .drop(1)
            .onEach { newRoute ->
                if (newRoute == null) return@onEach
                val newPayload = extNavRouteRegistry.get(newRoute.id) ?: return@onEach
                activeRoute = newRoute
                tracker?.attach(newPayload.guidance)
                scheduler?.reset()
                rerouteDetector?.reset()
                _guidanceUiState.value = _guidanceUiState.value.copy(isOffRoute = false)
            }
            .launchIn(scope)

        cameraManager.requestCameraFollowing(pitch3D = true)

        locationJob?.cancel()
        locationJob = scope.launch {
            sessionTracker.state.collectLatest { snapshot ->
                if (snapshot == null) return@collectLatest
                applyProgress(snapshot)
                scheduler?.onProgress(snapshot)
                rerouteDetector?.onProgress(snapshot) {
                    Napier.w(tag = TAG) { "Off-route detected; awaiting external rerouter." }
                    _guidanceUiState.value = _guidanceUiState.value.copy(isOffRoute = true)
                    sessionSpeaker.speakPlain(
                        text = "ルートから外れました",
                        utteranceId = "off-route-${System.currentTimeMillis()}",
                    )
                }
            }
        }
    }

    fun stopSession() {
        locationJob?.cancel()
        locationJob = null
        rerouteJob?.cancel()
        rerouteJob = null
        detachLocationListener()

        tracker?.detach()
        tracker = null
        scheduler?.reset()
        scheduler = null
        rerouteDetector?.reset()
        rerouteDetector = null
        speaker?.stop()
        speaker = null

        activeRoute = null
        _guidanceUiState.value = GuidanceUiState.Initial
        _arrivalInfo.value = null
    }

    fun returnToBrowsing() {
        _navigationState.value = NavigationState.Browsing
    }

    fun setTtsMuted(muted: Boolean) {
        if (muted) speaker?.stop()
    }

    private fun attachLocationListener() {
        scope.launch {
            navigationSdkManager.roadSnappedLocationProvider.collectLatest { provider ->
                detachLocationListener()
                attachedProvider = provider
                provider?.addLocationListener(locationListener)
            }
        }
    }

    private fun detachLocationListener() {
        attachedProvider?.removeLocationListener(locationListener)
        attachedProvider = null
    }

    private fun onLocationUpdated(location: Location) {
        tracker?.onLocation(location.latitude, location.longitude)
    }

    private fun applyProgress(snapshot: ExtNavProgressSnapshot) {
        val route = activeRoute ?: return

        val distanceRemaining = snapshot.remainingMetres
        val durationRemaining = snapshot.remainingSeconds

        val currentManeuver = buildCurrentManeuver(snapshot)
        val nextManeuver = buildNextManeuver(snapshot)

        _guidanceUiState.value = _guidanceUiState.value.copy(
            currentManeuver = currentManeuver,
            nextManeuver = nextManeuver,
            tripProgress = TripProgressInfo(
                distanceRemainingMeters = distanceRemaining,
                durationRemainingSeconds = durationRemaining,
                estimatedArrivalTimeMillis = System.currentTimeMillis() + (durationRemaining * 1000).toLong(),
            ),
            isOffRoute = false,
            isTtsAvailable = true,
        )

        if (distanceRemaining <= ARRIVAL_THRESHOLD_METRES) {
            onFinalDestinationArrival(route)
        }
    }

    private fun buildCurrentManeuver(snapshot: ExtNavProgressSnapshot): ManeuverInfo? {
        val guidancePoint = snapshot.nextGuidancePoint ?: return null
        val distance = snapshot.distanceToNextGuidancePointMetres ?: return null
        return toManeuverInfo(
            guidancePoint = guidancePoint,
            intersection = snapshot.nextIntersection,
            distanceMeters = distance,
        )
    }

    private fun buildNextManeuver(snapshot: ExtNavProgressSnapshot): ManeuverInfo? {
        val nextNextGp = snapshot.upcomingGuidancePoints.getOrNull(1) ?: return null
        return toManeuverInfo(
            guidancePoint = nextNextGp,
            intersection = null,
            distanceMeters = 0.0,
        )
    }

    private fun toManeuverInfo(
        guidancePoint: GuidancePoint,
        intersection: Intersection?,
        distanceMeters: Double,
    ): ManeuverInfo {
        val primaryPhrase = guidancePoint.phrases.firstOrNull()
        val category = primaryPhrase?.category ?: GuidanceCategory.Unspecified
        val instruction = intersection?.name
            ?.takeIf { it.isNotBlank() }
            ?: primaryPhrase?.plainText().orEmpty()
        return ManeuverInfo(
            type = category.toManeuverType(),
            modifier = null,
            distanceMeters = distanceMeters,
            instruction = instruction,
            roadName = intersection?.roadName?.takeIf { it.isNotBlank() },
            destinations = intersection?.directionSignA?.takeIf { it.isNotBlank() },
        )
    }

    private fun GuidanceCategory.toManeuverType(): ManeuverType = when (this) {
        GuidanceCategory.Merge,
        GuidanceCategory.MergeAttention,
        -> ManeuverType.MERGE
        GuidanceCategory.AutoExpresswayEntry -> ManeuverType.ON_RAMP
        GuidanceCategory.TunnelBranch -> ManeuverType.FORK
        else -> ManeuverType.TURN
    }

    private fun isSameRoute(old: GoogleRoute?, new: GoogleRoute?): Boolean {
        if (old == null || new == null) return old === new
        return old.id == new.id
    }

    private fun onFinalDestinationArrival(route: GoogleRoute) {
        val elapsedSeconds = (System.currentTimeMillis() - sessionStartTimeMillis) / 1000.0
        _arrivalInfo.value = ArrivalInfo(
            destinationName = "",
            totalDistanceMeters = route.distanceMeters,
            totalDurationSeconds = elapsedSeconds,
        )
        _navigationState.value = NavigationState.Arrival

        speaker?.speakPlain(
            text = "目的地に到着しました",
            utteranceId = "arrival-${System.currentTimeMillis()}",
        )
    }

    @Suppress("unused") // Phase 2 以降で使う
    private fun distanceBetweenMetres(
        lat1: Double,
        lng1: Double,
        lat2: Double,
        lng2: Double,
    ): Double {
        val earthRadius = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
            kotlin.math.cos(Math.toRadians(lat1)) *
            kotlin.math.cos(Math.toRadians(lat2)) *
            kotlin.math.sin(dLng / 2) * kotlin.math.sin(dLng / 2)
        val c = 2 * kotlin.math.atan2(sqrt(a), sqrt(1 - a))
        return earthRadius * c
    }

    companion object {
        private const val TAG = "GuidanceSessionManager"
        private const val ARRIVAL_THRESHOLD_METRES: Double = 20.0
    }
}
