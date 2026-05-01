package me.matsumo.onenavi.core.navigation.newguidance

import android.location.Location
import com.google.android.libraries.navigation.CustomRoutesOptions
import com.google.android.libraries.navigation.ListenableResultFuture
import com.google.android.libraries.navigation.Navigator
import com.google.android.libraries.navigation.RoadSnappedLocationProvider
import com.google.android.libraries.navigation.Waypoint
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import me.matsumo.onenavi.core.model.RoutePoint
import me.matsumo.onenavi.core.navigation.newguidance.model.GuidanceState
import me.matsumo.onenavi.core.navigation.newguidance.model.RefinedChunk
import me.matsumo.onenavi.core.navigation.newguidance.model.RefinedRoute
import me.matsumo.onenavi.core.repository.RouteRepository
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.milliseconds

/**
 * spec/24 §5-§7 の新案内マネージャ。
 *
 * 責務:
 * - Preview から渡された [RefinedRoute] を `Navigator.setDestinations` 経由で SDK に投入
 * - 走行進捗を [RemainingTimeOrDistanceChangedListener] で監視し、現 chunk 80% で次 chunk
 *   の route_token に切替
 * - SDK の [ReroutingListener] で逸脱検知 → [RouteRepository] で再探索 → [ExtNavRouteRefiner]
 *   で refine → setDestinations 上書き
 * - SDK の [ArrivalListener] で到達検知
 *
 * 実装方針:
 * - 並走方式 (spec/24 §0.1 B 案) のため旧 [me.matsumo.onenavi.core.navigation.GuidanceSessionManager]
 *   には触らない
 * - SDK の自動リルートは抑制不可なので「一瞬だけ独自リルート結果が描画される」のは許容
 *   (spec/24 §6.3)
 * - chunk 切替は [Navigator.setDestinations] のみ。Routes API 自体は Preview 時に終わって
 *   いるので新規 HTTP は走らない
 *
 * ライフサイクル:
 * 1. UI 側で [startGuidance] を呼ぶ。Navigator / RefinedRoute / RoadSnappedLocationProvider
 *    を受け取って listener を attach し、Guidance を開始する
 * 2. 内部で chunk 切替 / リルート / 到達を自動処理する
 * 3. UI 離脱や手動停止で [stopGuidance] を呼ぶ。listener detach + state = Idle
 */
class NewGuidanceManager(
    private val routeRepository: RouteRepository,
    private val extNavRouteRefiner: ExtNavRouteRefiner,
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) {

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val _state = MutableStateFlow<GuidanceState>(GuidanceState.Idle)
    val state: StateFlow<GuidanceState> = _state.asStateFlow()

    private var navigator: Navigator? = null
    private var route: RefinedRoute? = null
    private var locationProvider: RoadSnappedLocationProvider? = null
    private var activeChunkIndex: Int = 0
    private var advanceJob: Job? = null
    private var rerouteJob: Job? = null
    private var lastLocation: Location? = null

    private val remainingListener = Navigator.RemainingTimeOrDistanceChangedListener {
        onRemainingDistanceChanged()
    }

    private val reroutingListener = Navigator.ReroutingListener {
        onReroutingRequested()
    }

    private val arrivalListener = Navigator.ArrivalListener {
        onArrival()
    }

    private val locationListener = RoadSnappedLocationProvider.LocationListener { location ->
        lastLocation = location
    }

    /**
     * 案内を開始する。Preview で確定した [route] と SDK 接続点を受け取る。
     *
     * @param navigator Navigation SDK の Navigator (NewNavigationSdkManager 経由)
     * @param route Preview で refine 済みの選択ルート
     * @param locationProvider RoadSnappedLocationProvider。リルート時の現在地取得に使う
     */
    fun startGuidance(
        navigator: Navigator,
        route: RefinedRoute,
        locationProvider: RoadSnappedLocationProvider,
    ) {
        require(route.chunks.isNotEmpty()) { "RefinedRoute must contain at least one chunk" }

        // 二重起動防止
        if (this.navigator != null) {
            Napier.w(tag = TAG) { "startGuidance called while another session is active; stopping previous" }
            stopGuidance()
        }

        this.navigator = navigator
        this.route = route
        this.locationProvider = locationProvider
        this.activeChunkIndex = 0

        attachListeners(navigator, locationProvider)

        scope.launch {
            applyChunk(navigator, route.chunks[0]).fold(
                onSuccess = {
                    navigator.startGuidance()
                    _state.value = GuidanceState.Guiding(activeChunkIndex = 0)

                    Napier.i(tag = TAG) { "Guidance started: chunks=${route.chunks.size}" }
                },
                onFailure = { error ->
                    Napier.e(tag = TAG, throwable = error) { "Failed to apply chunk 0" }
                    detachListeners()

                    this@NewGuidanceManager.navigator = null
                    this@NewGuidanceManager.route = null
                    this@NewGuidanceManager.locationProvider = null

                    _state.value = GuidanceState.Failed("chunk0 setDestinations failed: ${error.message}")
                },
            )
        }
    }

    /**
     * Preview 期に Navigator へ chunk[0] を投入する。route_token を SDK にロードするだけで
     * `startGuidance()` は呼ばない (spec/24 §4.2)。listener attach もしない。
     *
     * selectedIndex 切替で何度呼んでも安全。`setDestinations` は前回の destinations を
     * 暗黙に上書きするので明示的な clear は不要。
     *
     * Guidance 中に呼ばれた場合は警告ログのみで no-op。
     */
    suspend fun previewRoute(navigator: Navigator, route: RefinedRoute) {
        require(route.chunks.isNotEmpty()) { "RefinedRoute must contain at least one chunk" }

        if (this.navigator != null) {
            Napier.w(tag = TAG) { "previewRoute called during active guidance; ignoring" }
            return
        }

        applyChunk(navigator, route.chunks[0]).onFailure { error ->
            Napier.w(tag = TAG, throwable = error) { "previewRoute applyChunk failed" }
        }
    }

    /** 案内を停止する。listener detach + state = Idle にする。 */
    fun stopGuidance() {
        advanceJob?.cancel()
        advanceJob = null
        rerouteJob?.cancel()
        rerouteJob = null

        navigator?.stopGuidance()
        detachListeners()

        navigator = null
        route = null
        locationProvider = null
        activeChunkIndex = 0
        lastLocation = null
        _state.value = GuidanceState.Idle
    }

    /** Manager 自体を破棄するときに呼ぶ。スコープを cancel する。 */
    fun release() {
        stopGuidance()
        scope.cancel()
    }

    private fun attachListeners(navigator: Navigator, locationProvider: RoadSnappedLocationProvider) {
        navigator.addRemainingTimeOrDistanceChangedListener(
            REMAINING_TIME_THRESHOLD_SECONDS,
            REMAINING_DISTANCE_THRESHOLD_METERS,
            remainingListener,
        )
        navigator.addReroutingListener(reroutingListener)
        navigator.addArrivalListener(arrivalListener)
        locationProvider.addLocationListener(locationListener)
    }

    private fun detachListeners() {
        navigator?.let { nav ->
            nav.removeRemainingTimeOrDistanceChangedListener(remainingListener)
            nav.removeReroutingListener(reroutingListener)
            nav.removeArrivalListener(arrivalListener)
        }
        locationProvider?.removeLocationListener(locationListener)
    }

    private fun onRemainingDistanceChanged() {
        if (advanceJob != null) return
        if (rerouteJob != null) return

        val nav = navigator ?: return
        val rt = route ?: return
        val activeIndex = activeChunkIndex
        if (activeIndex >= rt.chunks.lastIndex) return

        val activeChunk = rt.chunks[activeIndex]
        val remainingMeters = nav.currentTimeAndDistance?.meters ?: return
        val chunkLength = activeChunk.distanceMeters
        if (chunkLength <= 0) return

        val progressFraction = 1.0 - (remainingMeters.toDouble() / chunkLength)
        if (progressFraction >= ADVANCE_THRESHOLD_FRACTION) {
            advanceJob = scope.launch { advanceToNextChunk() }
        }
    }

    private suspend fun advanceToNextChunk() {
        val nav = navigator ?: return
        val rt = route ?: return
        val nextIndex = activeChunkIndex + 1
        val nextChunk = rt.chunks.getOrNull(nextIndex) ?: return

        _state.value = GuidanceState.AdvancingChunk(from = activeChunkIndex)

        val applied = applyChunkWithRetry(
            navigator = nav,
            chunk = nextChunk,
            attempt = 0,
        )

        if (applied) {
            activeChunkIndex = nextIndex
            _state.value = GuidanceState.Guiding(activeChunkIndex = nextIndex)
            Napier.i(tag = TAG) { "Advanced to chunk $nextIndex / ${rt.chunks.lastIndex}" }
        } else {
            _state.value = GuidanceState.Failed("chunk advance failed after $MAX_RETRY attempts")
            Napier.e(tag = TAG) { "Chunk advance failed after retries" }
        }

        advanceJob = null
    }

    private suspend fun applyChunkWithRetry(
        navigator: Navigator,
        chunk: RefinedChunk,
        attempt: Int,
    ): Boolean {
        val result = applyChunk(navigator, chunk)
        val delayMs = RETRY_BASE_DELAY_MS * (attempt + 1)

        if (result.isSuccess) return true
        if (attempt >= MAX_RETRY - 1) return false

        Napier.w(tag = TAG) { "applyChunk failed (attempt=${attempt + 1}); retrying in ${delayMs}ms" }
        delay(delayMs.milliseconds)

        return applyChunkWithRetry(
            navigator = navigator,
            chunk = chunk,
            attempt = attempt + 1,
        )
    }

    private suspend fun applyChunk(navigator: Navigator, chunk: RefinedChunk): Result<Unit> {
        return runCatching {
            val waypoints = buildSdkWaypoints(chunk)
            val customRoutesOptions = CustomRoutesOptions.builder()
                .setRouteToken(chunk.routeToken)
                .setTravelMode(CustomRoutesOptions.TravelMode.DRIVING)
                .build()
            val routeStatus = navigator.setDestinations(waypoints, customRoutesOptions).await()

            check(routeStatus == Navigator.RouteStatus.OK) {
                "setDestinations returned non-OK status: $routeStatus"
            }
        }
    }

    /**
     * RefinedChunk を SDK の Waypoint 列に変換する。
     *
     * Navigator.setDestinations の `destinations` は **目的地と経由地のみ** を渡す。
     * origin (chunk 先頭) は SDK が「現在地」として扱うので含めない。
     */
    private fun buildSdkWaypoints(chunk: RefinedChunk): List<Waypoint> =
        chunk.waypoints
            .drop(1)
            .map { wp -> wp.point.toSdkWaypoint() }

    private fun onReroutingRequested() {
        if (rerouteJob != null) return
        rerouteJob = scope.launch { reroute() }
    }

    private suspend fun reroute() {
        val nav = navigator ?: return
        val rt = route ?: return
        val origin = lastLocation?.toRoutePoint() ?: rt.chunks[activeChunkIndex].waypoints.first().point
        val destination = rt.destination

        _state.value = GuidanceState.Rerouting

        Napier.i(tag = TAG) { "Rerouting from $origin to $destination" }

        val refined = runCatching {
            val results = routeRepository.searchRoutes(
                originLatitude = origin.latitude,
                originLongitude = origin.longitude,
                destinationLatitude = destination.latitude,
                destinationLongitude = destination.longitude,
            ).getOrThrow()

            val primary = results.firstOrNull() ?: error("Reroute returned no candidates")

            extNavRouteRefiner.refine(
                extPolyline = primary.item.geometry,
                origin = origin,
                destination = destination,
            )
        }

        refined.fold(
            onSuccess = { newRoute ->
                route = newRoute
                activeChunkIndex = 0

                val applied = applyChunkWithRetry(
                    navigator = nav,
                    chunk = newRoute.chunks[0],
                    attempt = 0,
                )

                if (applied) {
                    _state.value = GuidanceState.Guiding(activeChunkIndex = 0)
                    Napier.i(tag = TAG) { "Reroute completed: chunks=${newRoute.chunks.size}" }
                } else {
                    _state.value = GuidanceState.Failed("reroute setDestinations failed")
                }
            },
            onFailure = { error ->
                Napier.e(tag = TAG, throwable = error) { "Reroute search/refine failed" }
                _state.value = GuidanceState.Failed("reroute failed: ${error.message}")
            },
        )
        rerouteJob = null
    }

    private fun onArrival() {
        Napier.i(tag = TAG) { "Arrived at destination" }
        navigator?.stopGuidance()
        detachListeners()
        navigator = null
        route = null
        locationProvider = null
        activeChunkIndex = 0
        _state.value = GuidanceState.Arrived
    }

    private fun Location.toRoutePoint(): RoutePoint = RoutePoint(
        latitude = latitude,
        longitude = longitude,
    )

    private fun RoutePoint.toSdkWaypoint(): Waypoint =
        Waypoint.builder()
            .setLatLng(latitude, longitude)
            .setVehicleStopover(false)
            .build()

    /**
     * Navigation SDK の [ListenableResultFuture] を suspend で待つ拡張。
     */
    private suspend fun <T> ListenableResultFuture<T>.await(): T =
        suspendCancellableCoroutine { continuation ->
            setOnResultListener { result ->
                if (continuation.isActive) continuation.resume(result)
            }
        }

    companion object {
        private const val TAG = "NewGuidanceManager"

        /** 現 chunk の走行 80% 到達で次 chunk に切替える (spec/24 §5.2)。 */
        const val ADVANCE_THRESHOLD_FRACTION = 0.8

        /** chunk 切替の retry 上限 (spec/24 §11.3)。3 回失敗で案内続行不可。 */
        private const val MAX_RETRY = 3

        /** retry 1 回目の base delay。N 回目は base * N。 */
        private const val RETRY_BASE_DELAY_MS = 5_000L

        /** RemainingTimeOrDistanceChangedListener の呼び出し閾値 (秒)。 */
        private const val REMAINING_TIME_THRESHOLD_SECONDS = 30

        /** RemainingTimeOrDistanceChangedListener の呼び出し閾値 (m)。 */
        private const val REMAINING_DISTANCE_THRESHOLD_METERS = 100
    }
}
