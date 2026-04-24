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
import me.matsumo.drive.supporter.api.guidance.domain.ManeuverDirection
import me.matsumo.drive.supporter.api.guidance.domain.MergeSide
import me.matsumo.onenavi.core.model.ArrivalInfo
import me.matsumo.onenavi.core.model.GoogleRoute
import me.matsumo.onenavi.core.model.GuidanceUiState
import me.matsumo.onenavi.core.model.ManeuverInfo
import me.matsumo.onenavi.core.model.ManeuverModifier
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
    private val navigationViewReflectionBridge: NavigationViewReflectionBridge,
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

    private var locationUpdateCount: Int = 0

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

        Napier.i(tag = TAG) {
            "[NAVDBG] startSession: route=${route.id} " +
                "intersections=${payload.guidance.intersections.size} " +
                "gps=${payload.guidance.guidancePoints.size} " +
                "polyline=${payload.guidance.polyline.size} " +
                "totalMetres=${payload.guidance.summary.distanceMetres}"
        }

        val sessionTracker = extNavTrackerProvider().also { tracker = it }
        val sessionScheduler = extNavSchedulerProvider().also { scheduler = it }
        val sessionDetector = extNavRerouteDetectorProvider().also { rerouteDetector = it }
        val sessionSpeaker = speakerProvider().also { speaker = it }

        sessionTracker.attach(payload.guidance)
        sessionScheduler.reset()
        sessionDetector.reset()

        _navigationState.value = NavigationState.ActiveGuidance
        _guidanceUiState.value = GuidanceUiState.Initial.copy(isTtsAvailable = true)
        navigationViewReflectionBridge.requestGuidanceSessionStart()
        navigationViewReflectionBridge.setRouteOverlayRoutes(routeManager.routes.value)

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
                navigationViewReflectionBridge.setRouteOverlayRoutes(routeManager.routes.value)
            }
            .launchIn(scope)

        cameraManager.requestCameraFollowing(pitch3D = true)

        locationJob?.cancel()
        locationJob = scope.launch {
            sessionTracker.state.collectLatest { snapshot ->
                if (snapshot == null) return@collectLatest
                Napier.i(tag = TAG) {
                    "[NAVDBG] snapshot: progressed=${snapshot.progressedMetres.toInt()}m " +
                        "remaining=${snapshot.remainingMetres.toInt()}m " +
                        "nearestIdx=${snapshot.nearestIntersectionIndex} " +
                        "nearestDist=${snapshot.nearestIntersectionDistanceMetres.toInt()}m " +
                        "nextGpIdx=${snapshot.nextGuidancePoint?.index} " +
                        "distToGp=${snapshot.distanceToNextGuidancePointMetres?.toInt()}m " +
                        "upcomingGps=${snapshot.upcomingGuidancePoints.size} " +
                        "nextManeuverIdx=${snapshot.nextManeuverPoint?.index} " +
                        "distToManeuver=${snapshot.distanceToNextManeuverPointMetres?.toInt()}m " +
                        "upcomingManeuvers=${snapshot.upcomingManeuverPoints.size}"
                }
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
        navigationViewReflectionBridge.requestGuidanceSessionStop()
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
                Napier.i(tag = TAG) {
                    "[NAVDBG] attachLocationListener: provider=${provider != null}"
                }
            }
        }
    }

    private fun detachLocationListener() {
        attachedProvider?.removeLocationListener(locationListener)
        attachedProvider = null
    }

    private fun onLocationUpdated(location: Location) {
        locationUpdateCount++
        if (locationUpdateCount <= 5 || locationUpdateCount % 20 == 0) {
            Napier.i(tag = TAG) {
                "[NAVDBG] onLocationUpdated #$locationUpdateCount: " +
                    "lat=${location.latitude} lng=${location.longitude} " +
                    "acc=${location.accuracy} speed=${location.speed}"
            }
        }
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
        val maneuverPoint = snapshot.nextManeuverPoint ?: return null
        val distance = snapshot.distanceToNextManeuverPointMetres ?: return null
        return toManeuverInfo(
            guidancePoint = maneuverPoint,
            intersection = snapshot.nextManeuverIntersection,
            distanceMeters = distance,
        )
    }

    private fun buildNextManeuver(snapshot: ExtNavProgressSnapshot): ManeuverInfo? {
        val nextNextManeuver = snapshot.upcomingManeuverPoints.getOrNull(1) ?: return null
        return toManeuverInfo(
            guidancePoint = nextNextManeuver,
            intersection = null,
            distanceMeters = 0.0,
        )
    }

    private fun toManeuverInfo(
        guidancePoint: GuidancePoint,
        intersection: Intersection?,
        distanceMeters: Double,
    ): ManeuverInfo {
        // UI の instruction には常に「地点名」を使う。phrase.plainText にフォールバック
        // すると "ポーン" / "およそ500m先" などの発話フラグメントが露出してしまうため
        // 意図的に落とす。intersection が紐付かない場合は空文字で空欄にする
        // (アイコン + 距離だけ見せる)。
        val category = guidancePoint.phrases
            .firstOrNull { it.category in ExtNavGuidanceTracker.MANEUVER_CATEGORIES }
            ?.category
            ?: guidancePoint.phrases.firstOrNull()?.category
            ?: GuidanceCategory.Unspecified
        val instruction = intersection?.name?.takeIf { it.isNotBlank() }.orEmpty()
        return ManeuverInfo(
            type = category.toManeuverType(),
            modifier = resolveManeuverModifier(category, guidancePoint, intersection),
            distanceMeters = distanceMeters,
            instruction = instruction,
            roadName = intersection?.roadName?.takeIf { it.isNotBlank() },
            destinations = intersection?.directionSignA?.takeIf { it.isNotBlank() },
        )
    }

    /**
     * マニューバアイコン用の方向修飾子を決定する。
     *
     * - 合流系 (`Merge` / `MergeAttention`) は `ManeuverHint.mergeSide` (GuidePointFlags.f3.c 由来)
     *   を左右の正本にする。合流 GP では angle_in/out がほぼ等しく direction は Straight になるため。
     * - それ以外は `ManeuverHint.direction` (angle_in/angle_out 差から算出) を優先。
     * - `ManeuverHint` が null の場合は `Intersection.direction` にフォールバック (同じ計算結果)。
     * - 両方とも `Unknown` / null なら null を返し、呼び出し側で従来どおり「直進」フォールバックへ。
     *
     * 詳細は `docs/spec/21_ext_nav_guide_proto_and_announcement.md` §4 参照。
     */
    private fun resolveManeuverModifier(
        category: GuidanceCategory,
        guidancePoint: GuidancePoint,
        intersection: Intersection?,
    ): ManeuverModifier? {
        val hint = guidancePoint.maneuver
        if (category == GuidanceCategory.Merge || category == GuidanceCategory.MergeAttention) {
            hint?.mergeSide?.let { return it.toManeuverModifier() }
        }
        val direction = hint?.direction ?: intersection?.direction
        return direction?.toManeuverModifier()
    }

    private fun ManeuverDirection.toManeuverModifier(): ManeuverModifier? = when (this) {
        ManeuverDirection.Straight -> ManeuverModifier.STRAIGHT
        ManeuverDirection.UTurn -> ManeuverModifier.UTURN
        ManeuverDirection.Left -> ManeuverModifier.LEFT
        ManeuverDirection.SlantLeft -> ManeuverModifier.SLIGHT_LEFT
        ManeuverDirection.ThisSideLeft -> ManeuverModifier.SHARP_LEFT
        ManeuverDirection.Right -> ManeuverModifier.RIGHT
        ManeuverDirection.SlantRight -> ManeuverModifier.SLIGHT_RIGHT
        ManeuverDirection.ThisSideRight -> ManeuverModifier.SHARP_RIGHT
        ManeuverDirection.Unknown -> null
    }

    private fun MergeSide.toManeuverModifier(): ManeuverModifier = when (this) {
        MergeSide.LEFT -> ManeuverModifier.LEFT
        MergeSide.RIGHT -> ManeuverModifier.RIGHT
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
