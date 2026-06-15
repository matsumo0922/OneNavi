package me.matsumo.onenavi.core.navigation.newguidance

import android.os.SystemClock
import io.github.aakira.napier.Napier
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.withTimeout
import me.matsumo.onenavi.core.datasource.location.CurrentLocationDataSource
import me.matsumo.onenavi.core.datasource.location.UserLocation
import me.matsumo.onenavi.core.model.RoadClass
import me.matsumo.onenavi.core.model.RouteDetail
import me.matsumo.onenavi.core.model.RoutePoint
import me.matsumo.onenavi.core.model.RouteResult
import me.matsumo.onenavi.core.model.RouteWaypoint
import me.matsumo.onenavi.core.navigation.extnav.ExtNavGuidanceTracker
import me.matsumo.onenavi.core.navigation.extnav.ExtNavProgressSnapshot
import me.matsumo.onenavi.core.navigation.extnav.ExtNavRerouteDecision
import me.matsumo.onenavi.core.navigation.extnav.ExtNavRerouteDetector
import me.matsumo.onenavi.core.navigation.extnav.ExtNavRouteRegistry
import me.matsumo.onenavi.core.navigation.extnav.ExtNavTunnelSegmentProvider
import me.matsumo.onenavi.core.navigation.extnav.RouteGeometryMath
import me.matsumo.onenavi.core.navigation.extnav.RouteStopProgress
import me.matsumo.onenavi.core.navigation.extnav.TunnelMapStatus
import me.matsumo.onenavi.core.navigation.newguidance.model.GpsSignalState
import me.matsumo.onenavi.core.navigation.newguidance.model.GuidanceEvent
import me.matsumo.onenavi.core.navigation.newguidance.model.GuidanceProgress
import me.matsumo.onenavi.core.navigation.newguidance.model.GuidanceState
import me.matsumo.onenavi.core.navigation.newguidance.model.RouteMatchState
import me.matsumo.onenavi.core.navigation.newguidance.model.VehiclePositionSource
import me.matsumo.onenavi.core.navigation.newguidance.presentation.GuidancePresentation
import me.matsumo.onenavi.core.navigation.voice.debug.VoiceAnnouncementDebugSnapshot
import me.matsumo.onenavi.core.navigation.voice.scheduler.VoiceAnnouncementController
import me.matsumo.onenavi.core.repository.RouteRepository

/**
 * Guidance 期 (案内中) のマネージャ。
 *
 * 地図描画・callout・音声案内はすべて自前で行う。
 * この manager は route payload を tracker に attach し、端末位置 tick を流して [GuidanceState] を更新する。
 * 音声案内は attach 時に [VoiceAnnouncementController] へ同じ payload / 距離変換 context を渡し、tracker snapshot を
 * 流して発話を駆動する。tracker が `OFF_ROUTE_CONFIRMED` を確定したら [ExtNavRerouteDetector] が
 * [ExtNavRerouteDecision.Request] を返し、この manager が [RouteRepository] で再探索して新ルートで案内を貼り直す。
 */
class NewGuidanceManager internal constructor(
    private val routeRegistry: ExtNavRouteRegistry? = null,
    private val guidanceTracker: ExtNavGuidanceTracker? = null,
    private val locationDataSource: CurrentLocationDataSource? = null,
    private val voiceController: VoiceAnnouncementController? = null,
    private val rerouteDetector: ExtNavRerouteDetector? = null,
    private val routeRepository: RouteRepository? = null,
    private val tunnelSegmentProvider: ExtNavTunnelSegmentProvider? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {

    private val _state = MutableStateFlow<GuidanceState>(GuidanceState.Idle)
    private val _gpsSignalState = MutableStateFlow<GpsSignalState>(GpsSignalState.Available)
    private val _events = MutableSharedFlow<GuidanceEvent>(extraBufferCapacity = EVENT_BUFFER_CAPACITY)
    private val fallbackVoiceDebugSnapshot = MutableStateFlow<VoiceAnnouncementDebugSnapshot?>(null)

    private var sessionJobs: List<Job> = emptyList()
    private var nextSessionId: Long = FIRST_SESSION_ID

    @Volatile
    private var activeSessionId: Long? = null

    private var isSessionActive: Boolean = false

    private var currentRoute: RouteDetail? = null
    private var rerouteJob: Job? = null
    private var prepareJob: Job? = null
    private var guidanceRequestGeneration: Long = INITIAL_GUIDANCE_REQUEST_GENERATION
    private var collectingSessionId: Long? = null
    private var isTrackerAttached: Boolean = false
    private var latestUsableObservedLocation: UserLocation? = null
    private var didFlushUsableObservedLocation: Boolean = false
    private var lastObservedReceivedAtElapsedNanos: Long? = null
    private var lastUsableObservedAtElapsedNanos: Long? = null

    /** Guidance 期の現在状態。 */
    val state: StateFlow<GuidanceState> = _state.asStateFlow()

    /** 案内中に使う測位信号の状態。 */
    val gpsSignalState: StateFlow<GpsSignalState> = _gpsSignalState.asStateFlow()

    /** Guidance 期の一度きりのイベント。 */
    val events: SharedFlow<GuidanceEvent> = _events.asSharedFlow()

    /** TTS 発話予定のデバッグスナップショット。 */
    val voiceDebugSnapshot: StateFlow<VoiceAnnouncementDebugSnapshot?> =
        voiceController?.debugSnapshot ?: fallbackVoiceDebugSnapshot.asStateFlow()

    /**
     * 指定ルートで案内を開始する。
     *
     * Preview 時に保存済みの payload を tracker に attach し、まず origin 位置で初期 snapshot を作る。
     * その後、位置情報 data source が注入されている場合は lastKnown と連続 GPS tick を tracker へ流す。
     *
     * @param route 案内対象ルート
     */
    fun startGuidance(route: RouteDetail) {
        beginGuidance(
            route = route,
            resetReroute = true,
        )
    }

    /**
     * 指定ルートで案内 session を開始する内部処理。
     *
     * 初回の案内開始とリルート後の貼り直しの双方から呼ぶ。前者は [ExtNavRerouteDetector] を完全に
     * リセットし、後者はウォームアップだけ attach 時に張り直して連発を防ぐ。
     *
     * @param route 案内対象ルート
     * @param resetReroute true の場合はリルート判定器を完全リセットする (初回開始時)
     */
    private fun beginGuidance(
        route: RouteDetail,
        resetReroute: Boolean,
    ) {
        val requestGeneration = nextGuidanceRequestGeneration()

        prepareJob?.cancel()
        stopGuidanceSession(detachTracker = isSessionActive)
        if (resetReroute) rerouteDetector?.detach()
        Napier.i(tag = TAG) { "Guidance started: routeId=${route.id}" }
        currentRoute = route
        val sessionId = consumeNextSessionId()
        _state.value = GuidanceState.Preparing(
            route = route,
            initialProgress = route.toInitialProgress(),
        )
        startPreparingSession(
            route = route,
            sessionId = sessionId,
        )

        prepareJob = scope.launch {
            val tunnelMapStatus = prepareTunnelMap(route)
            if (!isCurrentGuidanceRequest(requestGeneration, route)) {
                return@launch
            }

            val trackerSnapshot = startTrackerForRoute(
                route = route,
                tunnelMapStatus = tunnelMapStatus,
                announceOpening = resetReroute,
            )
            activeSessionId = sessionId
            isSessionActive = true
            isTrackerAttached = trackerSnapshot != null
            _state.value = guidingStateFrom(
                route = route,
                snapshot = trackerSnapshot,
            )
            flushLatestUsableObservedLocation()
        }
    }

    /**
     * tracker snapshot から [GuidanceState.Guiding] を作る。snapshot が無い場合は初期値で埋める。
     *
     * @param route 案内対象ルート
     * @param snapshot tracker が作った初期 snapshot。作れない場合は null
     * @return 案内中状態
     */
    private fun guidingStateFrom(
        route: RouteDetail,
        snapshot: ExtNavProgressSnapshot?,
    ): GuidanceState.Guiding {
        if (snapshot == null) {
            return GuidanceState.Guiding(
                route = route,
                progress = route.toInitialProgress(),
                presentation = GuidancePresentation.Empty,
            )
        }
        return GuidanceState.Guiding(
            route = route,
            progress = snapshot.progress,
            presentation = snapshot.presentation,
        )
    }

    /**
     * 案内を停止して Idle に戻す。
     *
     * GPS 購読 job と snapshot 購読 job を止め、tracker の attach 状態も破棄する。
     */
    fun stopGuidance() {
        val shouldEmitStopped = _state.value != GuidanceState.Idle

        Napier.i(tag = TAG) { "Guidance stopped" }
        nextGuidanceRequestGeneration()
        prepareJob?.cancel()
        prepareJob = null
        rerouteJob?.cancel()
        rerouteJob = null
        stopGuidanceSession(detachTracker = isSessionActive)
        resetSignalState()
        rerouteDetector?.detach()
        currentRoute = null
        _state.value = GuidanceState.Idle

        if (shouldEmitStopped) {
            _events.tryEmit(GuidanceEvent.Stopped)
        }
    }

    /**
     * 全表示面が閉じたときなど、画面 owner ではないプロセス側 lifecycle から案内を停止する。
     *
     * ViewModel 破棄では呼ばず、表示面監視や process 終了相当の明示停止だけで使う。
     */
    fun release() {
        stopGuidance()
    }

    /**
     * tracker を route に attach し、origin を 1 tick として流す。
     *
     * 位置情報 data source から初回 tick が来るまで UI を空にしないため、ここで同期的に初期 snapshot を作る。
     *
     * @param route 案内対象ルート
     * @param announceOpening 音声案内に開始アナウンスを付けるか (初回開始時のみ true)
     * @return tracker が作った初期 snapshot。作れない場合は null
     */
    private fun startTrackerForRoute(
        route: RouteDetail,
        tunnelMapStatus: TunnelMapStatus,
        announceOpening: Boolean,
    ): ExtNavProgressSnapshot? {
        val registry = routeRegistry
        val tracker = guidanceTracker
        if (registry == null || tracker == null) {
            Napier.w(tag = TAG) { "Tracker dependencies are not injected. Falling back to initial progress." }
            return null
        }

        val payload = registry.get(route.id)
        if (payload == null) {
            Napier.w(tag = TAG) { "Route payload not found. routeId=${route.id}" }
            return null
        }

        val attachment = tracker.attach(
            payload = payload,
            route = route,
            tunnelMapStatus = tunnelMapStatus,
        )
        rerouteDetector?.attach(route)
        val snapshot = tracker.initializeAtRouteOrigin(
            timestampMillis = System.currentTimeMillis(),
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
        )
        if (snapshot == null) {
            Napier.w(tag = TAG) { "Tracker snapshot was not produced. routeId=${route.id}" }
            return null
        }

        voiceController?.start(
            payload = payload,
            distanceContext = attachment.distanceContext,
            announceOpening = announceOpening,
            initialSnapshot = snapshot,
        )

        return snapshot
    }

    /**
     * Preparing から Guiding へ昇格する案内 session 用 coroutine 群を開始する。
     *
     * tracker snapshot の state 反映、端末位置の先行収集、測位信号 clock を別 job として開始する。
     *
     * @param route 案内対象ルート
     * @param sessionId この案内開始に対応する session id
     */
    private fun startPreparingSession(
        route: RouteDetail,
        sessionId: Long,
    ) {
        collectingSessionId = sessionId

        val tracker = guidanceTracker
        if (tracker == null) {
            Napier.w(tag = TAG) { "Tracker is not injected. Guidance session collection is skipped." }
            return
        }

        sessionJobs = listOfNotNull(
            startSnapshotCollection(
                route = route,
                tracker = tracker,
                sessionId = sessionId,
            ),
            startLocationCollection(
                route = route,
                tracker = tracker,
                sessionId = sessionId,
            ),
            startSignalClock(
                tracker = tracker,
                sessionId = sessionId,
            ),
        )
    }

    /**
     * tracker snapshot を購読して [GuidanceState.Guiding] を更新する。
     *
     * GPS tick で tracker の snapshot が更新されるたび、UI が読む progress を同じ route とともに公開する。
     *
     * @param route 案内対象ルート
     * @param tracker 進捗 snapshot の発行元
     * @param sessionId この案内開始に対応する session id
     * @return snapshot 購読 job
     */
    private fun startSnapshotCollection(
        route: RouteDetail,
        tracker: ExtNavGuidanceTracker,
        sessionId: Long,
    ): Job = scope.launch {
        try {
            tracker.snapshot.collect { snapshot ->
                if (snapshot == null) return@collect
                if (!isActiveSession(sessionId)) return@collect
                val isGuidanceContinuing = publishGuidance(route = route, snapshot = snapshot)
                if (!isGuidanceContinuing) return@collect
                maybeRequestReroute(snapshot = snapshot, sessionId = sessionId)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            handleGuidanceFailure(
                routeId = route.id,
                source = "tracker",
                error = error,
            )
        }
    }

    /**
     * 1 件の snapshot を UI と音声案内へ反映する。
     *
     * UI が読む [GuidanceState.Guiding] を更新し、同じ snapshot を音声案内へ流して発話 tick に変換させる。
     * 目的地到達時は案内 session を完了し、継続しないことを呼び出し元へ返す。
     *
     * @param route session 開始時の案内対象ルート
     * @param snapshot tracker が発行した進捗 snapshot
     * @return 案内を継続する場合は true
     */
    private fun publishGuidance(
        route: RouteDetail,
        snapshot: ExtNavProgressSnapshot,
    ): Boolean {
        val activeRoute = currentRoute ?: route
        val canCommitObservedProgress = snapshot.positionSource == VehiclePositionSource.OBSERVED
        if (canCommitObservedProgress && isDestinationReached(snapshot)) {
            completeDestinationGuidance(route = activeRoute)
            return false
        }

        val updatedRoute = if (canCommitObservedProgress) {
            activeRoute.withPassedIntermediateWaypointsRemoved(
                currentCumulativeMeters = snapshot.currentCumulativeMeters,
            )
        } else {
            activeRoute
        }
        if (updatedRoute != activeRoute) {
            val didPassWaypoint = updatedRoute.intermediateWaypoints.size < activeRoute.intermediateWaypoints.size
            currentRoute = updatedRoute
            rerouteDetector?.updateRoute(updatedRoute)
            if (didPassWaypoint) voiceController?.announceWaypointApproach()
        }

        _state.value = GuidanceState.Guiding(
            route = updatedRoute,
            progress = snapshot.progress,
            presentation = snapshot.presentation,
        )
        voiceController?.onSnapshot(snapshot)
        return true
    }

    /**
     * 目的地へ到達したかを判定する。
     *
     * @param snapshot tracker が発行した進捗 snapshot
     * @return 到達済みなら true
     */
    private fun isDestinationReached(snapshot: ExtNavProgressSnapshot): Boolean = RouteStopProgress.isDestinationReached(
        distanceRemainingMeters = snapshot.distanceRemainingMeters,
        routeMatchState = snapshot.routeMatchState,
        thresholdMeters = DESTINATION_REACHED_THRESHOLD_METRES,
    )

    /**
     * 目的地到達により案内 session を完了する。
     *
     * @param route 到達した案内 route
     */
    private fun completeDestinationGuidance(route: RouteDetail) {
        Napier.i(tag = TAG) { "Destination reached: routeId=${route.id}" }
        voiceController?.announceDestinationReached()
        nextGuidanceRequestGeneration()
        prepareJob?.cancel()
        prepareJob = null
        rerouteJob?.cancel()
        rerouteJob = null
        stopGuidanceSession(detachTracker = isSessionActive)
        resetSignalState()
        rerouteDetector?.detach()
        currentRoute = null
        _state.value = GuidanceState.Idle
        _events.tryEmit(GuidanceEvent.DestinationReached)
    }

    /**
     * 通過済み経由地を取り除いた route を返す。
     *
     * @param currentCumulativeMeters 現在地の route geometry 累積距離
     * @return 経由地リストを更新した route
     */
    private fun RouteDetail.withPassedIntermediateWaypointsRemoved(
        currentCumulativeMeters: Double,
    ): RouteDetail {
        val remainingIntermediateWaypoints = RouteStopProgress.remainingIntermediateWaypoints(
            route = this,
            currentCumulativeMeters = currentCumulativeMeters,
            marginMeters = PASSED_WAYPOINT_MARGIN_METRES,
        )
        if (remainingIntermediateWaypoints == intermediateWaypoints) return this

        return copy(
            intermediateWaypoints = remainingIntermediateWaypoints,
            routeWaypoints = remainingRouteWaypoints(remainingIntermediateWaypoints),
        )
    }

    /**
     * 未通過経由地に対応する表示用地点列を作る。
     *
     * @param remainingIntermediateWaypoints 未通過の経由地
     * @return 出発地、未通過経由地、目的地の表示用地点列
     */
    private fun RouteDetail.remainingRouteWaypoints(
        remainingIntermediateWaypoints: ImmutableList<RoutePoint>,
    ): ImmutableList<RouteWaypoint> {
        if (routeWaypoints.isEmpty()) return persistentListOf()

        val updatedRouteWaypoints = mutableListOf<RouteWaypoint>()
        val originWaypoint = routeWaypoints.firstOrNull()
            ?: RouteWaypoint.CurrentLocation(
                latitude = origin.latitude,
                longitude = origin.longitude,
            )
        updatedRouteWaypoints += originWaypoint
        for (waypoint in remainingIntermediateWaypoints) {
            updatedRouteWaypoints += routeWaypoints.displayPlaceForPoint(point = waypoint)
        }
        updatedRouteWaypoints += routeWaypoints.displayPlaceForPoint(point = destination)
        return updatedRouteWaypoints.toImmutableList()
    }

    /**
     * snapshot を [ExtNavRerouteDetector] にかけ、リルート要求なら再探索 job を起動する。
     *
     * 再探索 job は session job とは別に [scope] 上で起動する。job 内で session を止めても
     * 自分自身がキャンセルされないようにするため。判定器か repository が未注入なら何もしない。
     *
     * @param snapshot tracker が発行した進捗 snapshot
     * @param sessionId この案内 session の id
     */
    private fun maybeRequestReroute(
        snapshot: ExtNavProgressSnapshot,
        sessionId: Long,
    ) {
        val detector = rerouteDetector ?: return
        if (routeRepository == null) return
        if (rerouteJob?.isActive == true) return

        val decision = detector.onSnapshot(snapshot)
        if (decision !is ExtNavRerouteDecision.Request) return

        rerouteJob = scope.launch {
            handleReroute(request = decision, sessionId = sessionId)
        }
    }

    /**
     * リルート要求を受けて再探索し、新ルートで案内を貼り直す。
     *
     * 再探索中は現 session を止めて [GuidanceState.Rerouting] を保持する。成功時は同 priority を
     * 優先して候補を選び新ルートで再案内、失敗 / 候補なしは旧ルートで案内を再開する。
     *
     * @param request リルート要求
     * @param sessionId リルートを要求した案内 session の id
     */
    private suspend fun handleReroute(
        request: ExtNavRerouteDecision.Request,
        sessionId: Long,
    ) {
        if (!isActiveSession(sessionId)) return
        val repository = routeRepository ?: return
        val previousGuidingState = _state.value as? GuidanceState.Guiding
        val previousRoute = previousGuidingState?.route ?: currentRoute
        if (previousRoute == null) {
            _state.value = GuidanceState.Failed("reroute failed")
            return
        }
        val previousProgress = previousGuidingState?.progress ?: previousRoute.toInitialProgress()

        Napier.i(tag = TAG) { "Reroute requested: reason=${request.reason}" }
        nextGuidanceRequestGeneration()
        stopGuidanceSession(detachTracker = true)
        resetSignalState()
        _state.value = GuidanceState.Rerouting(
            previousRoute = previousRoute,
            previousProgress = previousProgress,
        )

        repository.searchRoutes(
            originLatitude = request.origin.latitude,
            originLongitude = request.origin.longitude,
            destinationLatitude = request.destination.latitude,
            destinationLongitude = request.destination.longitude,
            intermediateWaypoints = request.remainingViaPoints.map { viaPoint ->
                viaPoint.latitude to viaPoint.longitude
            },
            originDirectionDegrees = request.originDirectionDegrees,
        )
            .onSuccess { results ->
                val routeWaypoints = previousRoute.rerouteWaypoints(request = request)
                val nextRoute = selectRerouteCandidate(
                    results = results,
                    current = currentRoute,
                    routeWaypoints = routeWaypoints,
                )
                if (nextRoute == null) {
                    Napier.w(tag = TAG) { "Reroute returned no candidate" }
                    resumeAfterFailedReroute()
                } else {
                    Napier.i(tag = TAG) { "Reroute ready: routeId=${nextRoute.id}" }
                    beginGuidance(route = nextRoute, resetReroute = false)
                }
            }
            .onFailure { error ->
                Napier.w(tag = TAG, throwable = error) { "Reroute search failed" }
                resumeAfterFailedReroute()
            }
    }

    /**
     * 再探索結果から再案内するルートを選ぶ。
     *
     * 現在案内中と同じ priority を優先し、無ければ先頭候補を使う。
     *
     * @param results 再探索で得た候補
     * @param current 現在案内中のルート
     * @return 再案内するルート。候補が無い場合は null
     */
    private fun selectRerouteCandidate(
        results: List<RouteResult>,
        current: RouteDetail?,
        routeWaypoints: List<RouteWaypoint>,
    ): RouteDetail? {
        val candidates = results.map { result ->
            result.detail.copy(routeWaypoints = routeWaypoints.toImmutableList())
        }
        if (candidates.isEmpty()) return null

        val priority = current?.priority
        return candidates.firstOrNull { candidate -> candidate.priority == priority }
            ?: candidates.first()
    }

    private fun RouteDetail.rerouteWaypoints(
        request: ExtNavRerouteDecision.Request,
    ): List<RouteWaypoint> {
        val originWaypoint = RouteWaypoint.CurrentLocation(
            latitude = request.origin.latitude,
            longitude = request.origin.longitude,
        )
        val viaWaypoints = request.remainingViaPoints.map { routePoint ->
            routeWaypoints.displayPlaceForPoint(point = routePoint)
        }
        val destinationWaypoint = routeWaypoints.displayPlaceForPoint(point = request.destination)
        return listOf(originWaypoint) + viaWaypoints + destinationWaypoint
    }

    private fun List<RouteWaypoint>.displayPlaceForPoint(point: RoutePoint): RouteWaypoint.Place {
        val matchedWaypoint = findPlaceNear(point = point)
        return RouteWaypoint.Place(
            name = matchedWaypoint?.name.orEmpty(),
            latitude = point.latitude,
            longitude = point.longitude,
        )
    }

    private fun List<RouteWaypoint>.findPlaceNear(point: RoutePoint): RouteWaypoint.Place? {
        for (waypoint in this) {
            val place = waypoint as? RouteWaypoint.Place ?: continue
            val waypointPoint = RoutePoint(
                latitude = place.latitude,
                longitude = place.longitude,
            )
            val distanceMetres = RouteGeometryMath.haversineMetres(waypointPoint, point)
            if (distanceMetres <= WAYPOINT_NAME_MATCH_DISTANCE_METRES) return place
        }
        return null
    }

    /**
     * リルートに失敗したとき旧ルートで案内を再開する。
     *
     * 旧ルートの payload は registry に残っているため再 attach できる。旧ルートも無ければ
     * [GuidanceState.Failed] に落とす。
     */
    private fun resumeAfterFailedReroute() {
        val route = currentRoute
        if (route == null) {
            _state.value = GuidanceState.Failed("reroute failed")
            return
        }
        beginGuidance(route = route, resetReroute = false)
    }

    /**
     * 端末位置を購読して tracker へ投入する。
     *
     * まず lastKnown を 1 tick として試し、その後は連続位置更新をそのまま tracker へ流す。
     *
     * @param route 案内対象ルート
     * @param tracker 位置 tick の投入先
     * @param sessionId この案内開始に対応する session id
     * @return 位置購読 job。位置情報 data source がない場合は null
     */
    private fun startLocationCollection(
        route: RouteDetail,
        tracker: ExtNavGuidanceTracker,
        sessionId: Long,
    ): Job? {
        val dataSource = locationDataSource
        if (dataSource == null) {
            Napier.w(tag = TAG) { "Location data source is not injected. Waiting with origin snapshot." }
            return null
        }

        return scope.launch {
            try {
                val lastKnownLocation = dataSource.lastKnown()
                if (lastKnownLocation != null) {
                    forwardLocationTick(
                        tracker = tracker,
                        location = lastKnownLocation,
                        sessionId = sessionId,
                        canUpdateFreshness = false,
                    )
                }

                dataSource.locationUpdates().collect { location ->
                    forwardLocationTick(
                        tracker = tracker,
                        location = location,
                        sessionId = sessionId,
                        canUpdateFreshness = true,
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                handleGuidanceFailure(
                    routeId = route.id,
                    source = "location",
                    error = error,
                )
            }
        }
    }

    /**
     * 1 件の位置 tick を tracker へ投入する。
     *
     * @param tracker 位置 tick の投入先
     * @param location 端末から得た位置
     * @param sessionId この案内開始に対応する session id
     */
    private fun forwardLocationTick(
        tracker: ExtNavGuidanceTracker,
        location: UserLocation,
        sessionId: Long,
        canUpdateFreshness: Boolean,
    ) {
        if (!isSessionCollecting(sessionId)) return

        if (canUpdateFreshness) {
            recordObservedLocation(location)
        }
        if (!isActiveSession(sessionId) || !isTrackerAttached) return
        if (!shouldForwardObservedLocation(location)) return

        tracker.onLocation(location)
    }

    /**
     * 測位信号 clock を開始する。
     *
     * @param tracker DR tick を投入する tracker
     * @param sessionId この案内開始に対応する session id
     * @return clock job
     */
    private fun startSignalClock(
        tracker: ExtNavGuidanceTracker,
        sessionId: Long,
    ): Job = scope.launch {
        try {
            while (isSessionCollecting(sessionId)) {
                val nowElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                updateGpsSignalState(nowElapsedRealtimeNanos)
                if (isActiveSession(sessionId)) {
                    maybeAdvanceDeadReckoning(
                        tracker = tracker,
                        nowElapsedRealtimeNanos = nowElapsedRealtimeNanos,
                    )
                }
                delay(DR_TICK_INTERVAL_MILLIS)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            handleGuidanceFailure(
                routeId = currentRoute?.id.orEmpty(),
                source = "signal",
                error = error,
            )
        }
    }

    /**
     * 案内 session を停止する。
     *
     * @param detachTracker true の場合は tracker の attach 状態も破棄する
     */
    private fun stopGuidanceSession(detachTracker: Boolean) {
        activeSessionId = null
        collectingSessionId = null
        sessionJobs.forEach { job -> job.cancel() }
        sessionJobs = emptyList()
        isSessionActive = false
        isTrackerAttached = false
        didFlushUsableObservedLocation = false

        if (detachTracker) {
            guidanceTracker?.detach()
            voiceController?.stop()
        }
    }

    /**
     * 次の案内開始に使う session id を払い出す。
     *
     * @return 新しい案内 session id
     */
    private fun consumeNextSessionId(): Long {
        val sessionId = nextSessionId
        nextSessionId += SESSION_ID_INCREMENT
        return sessionId
    }

    /**
     * 指定した session が現在も有効かを判定する。
     *
     * @param sessionId 確認対象の案内 session id
     * @return 現在の案内 session なら true
     */
    private fun isActiveSession(sessionId: Long): Boolean = activeSessionId == sessionId

    /**
     * 指定 session の collector / clock が継続対象かを判定する。
     *
     * @param sessionId 確認対象の案内 session id
     * @return Preparing または Guiding 中の session なら true
     */
    private fun isSessionCollecting(sessionId: Long): Boolean =
        collectingSessionId == sessionId && (activeSessionId == null || activeSessionId == sessionId)

    /**
     * 位置 tick の freshness 情報を記録する。
     *
     * @param location 観測位置 tick
     */
    private fun recordObservedLocation(location: UserLocation) {
        val receivedAtElapsedNanos = SystemClock.elapsedRealtimeNanos()
        lastObservedReceivedAtElapsedNanos = receivedAtElapsedNanos

        if (!location.isUsableObservedLocation()) return

        lastUsableObservedAtElapsedNanos = receivedAtElapsedNanos
        latestUsableObservedLocation = location
        _gpsSignalState.value = GpsSignalState.Available
    }

    /**
     * 測位信号状態を更新する。
     *
     * @param nowElapsedRealtimeNanos 現在の monotonic clock
     */
    private fun updateGpsSignalState(nowElapsedRealtimeNanos: Long) {
        val lastUsableObservedAt = lastUsableObservedAtElapsedNanos ?: return
        val elapsedSeconds = (nowElapsedRealtimeNanos - lastUsableObservedAt).coerceAtLeast(0L) /
            NANOS_PER_SECOND.toFloat()
        if (elapsedSeconds <= LOST_SIGNAL_THRESHOLD_SECONDS) {
            _gpsSignalState.value = GpsSignalState.Available
        } else {
            _gpsSignalState.value = GpsSignalState.Lost(elapsedSeconds = elapsedSeconds)
        }
    }

    /**
     * 測位途絶中なら tracker の DR を 1 tick 進める。
     *
     * @param tracker DR tick の投入先
     * @param nowElapsedRealtimeNanos 現在の monotonic clock
     */
    private fun maybeAdvanceDeadReckoning(
        tracker: ExtNavGuidanceTracker,
        nowElapsedRealtimeNanos: Long,
    ) {
        if (_gpsSignalState.value !is GpsSignalState.Lost) return

        tracker.advanceDeadReckoning(
            nowElapsedRealtimeNanos = nowElapsedRealtimeNanos,
            nowWallClockMillis = System.currentTimeMillis(),
        )
    }

    /**
     * 観測 tick を tracker へ投入してよいかを返す。
     *
     * usable tick が途絶した後は、精度の粗い tick で DR を解除しない。通常案内中は従来どおり粗い tick も
     * tracker へ流す。
     *
     * @param location tracker 投入候補の観測位置
     * @return tracker へ投入してよい場合 true
     */
    private fun shouldForwardObservedLocation(location: UserLocation): Boolean {
        val isSignalLost = _gpsSignalState.value is GpsSignalState.Lost
        if (!isSignalLost) return true

        return location.isUsableObservedLocation()
    }

    /**
     * attach 直後に直近 usable tick を tracker へ 1 回だけ流す。
     */
    private fun flushLatestUsableObservedLocation() {
        val tracker = guidanceTracker ?: return
        val sessionId = activeSessionId ?: return
        if (didFlushUsableObservedLocation) return
        val location = latestUsableObservedLocation ?: return

        didFlushUsableObservedLocation = true
        forwardLocationTick(
            tracker = tracker,
            location = location,
            sessionId = sessionId,
            canUpdateFreshness = false,
        )
    }

    /**
     * guidance request 世代を進める。
     *
     * @return 更新後の世代
     */
    private fun nextGuidanceRequestGeneration(): Long {
        guidanceRequestGeneration += GUIDANCE_REQUEST_GENERATION_INCREMENT
        return guidanceRequestGeneration
    }

    /**
     * prepare 完了結果が現在の開始要求に対応しているかを返す。
     *
     * @param generation prepare 開始時に capture した世代
     * @param route prepare 対象 route
     * @return 現在の route / 世代と一致する場合 true
     */
    private fun isCurrentGuidanceRequest(
        generation: Long,
        route: RouteDetail,
    ): Boolean {
        val currentRoute = currentRoute ?: return false
        return generation == guidanceRequestGeneration && currentRoute.id == route.id
    }

    /**
     * 選択 route のトンネル区間を準備する。
     *
     * @param route 準備対象 route
     * @return トンネル区間状態。失敗時は Unavailable
     */
    private suspend fun prepareTunnelMap(route: RouteDetail): TunnelMapStatus {
        val provider = tunnelSegmentProvider ?: return TunnelMapStatus.Ready(persistentListOf())

        return runCatching {
            withTimeout(TUNNEL_PREPARE_TIMEOUT_MILLIS) {
                provider.prepare(route)
            }
        }.getOrElse { error ->
            Napier.w(tag = TAG, throwable = error) { "Tunnel map preparation failed: routeId=${route.id}" }
            TunnelMapStatus.Unavailable
        }
    }

    /**
     * 測位信号状態と freshness を初期化する。
     */
    private fun resetSignalState() {
        latestUsableObservedLocation = null
        didFlushUsableObservedLocation = false
        lastObservedReceivedAtElapsedNanos = null
        lastUsableObservedAtElapsedNanos = null
        _gpsSignalState.value = GpsSignalState.Available
    }

    /**
     * DR 解除や freshness 判定に使える観測位置かを返す。
     *
     * @return 水平精度が有限で閾値以下なら true
     */
    private fun UserLocation.isUsableObservedLocation(): Boolean =
        accuracyMeters.isFinite() && accuracyMeters <= USABLE_LOCATION_ACCURACY_METRES

    /**
     * 案内中の非キャンセル例外を [GuidanceState.Failed] に変換する。
     *
     * @param routeId 案内中 route id
     * @param source 失敗した内部処理
     * @param error 発生した例外
     */
    private fun handleGuidanceFailure(
        routeId: String,
        source: String,
        error: Throwable,
    ) {
        Napier.w(tag = TAG, throwable = error) { "Guidance $source failed: routeId=$routeId" }
        nextGuidanceRequestGeneration()
        prepareJob?.cancel()
        prepareJob = null
        stopGuidanceSession(detachTracker = isSessionActive)
        resetSignalState()
        _state.value = GuidanceState.Failed("guidance $source failed")
    }

    /**
     * ログタグと固定値をまとめる companion object。
     */
    private companion object {

        /** Logcat で案内 manager のログを絞り込むためのタグ。 */
        const val TAG = "NewGuidanceManager"

        /** 最初の案内 session id。 */
        const val FIRST_SESSION_ID = 1L

        /** 案内 session id を進める加算値。 */
        const val SESSION_ID_INCREMENT = 1L

        /** 経由地名を既存地点から引き継ぐため同一点とみなす距離。 */
        const val WAYPOINT_NAME_MATCH_DISTANCE_METRES = 30.0

        /** 到達済み経由地とみなす余裕距離。 */
        const val PASSED_WAYPOINT_MARGIN_METRES = 30.0

        /** 目的地到達とみなす残距離。 */
        const val DESTINATION_REACHED_THRESHOLD_METRES = 30.0

        /** Guidance event のバッファ容量。 */
        const val EVENT_BUFFER_CAPACITY = 1

        /** guidance request generation の初期値。 */
        const val INITIAL_GUIDANCE_REQUEST_GENERATION = 0L

        /** guidance request generation を進める加算値。 */
        const val GUIDANCE_REQUEST_GENERATION_INCREMENT = 1L

        /** トンネル prepare の待ち時間上限。 */
        const val TUNNEL_PREPARE_TIMEOUT_MILLIS = 3_000L

        /** DR / GPS signal clock の tick 間隔。 */
        const val DR_TICK_INTERVAL_MILLIS = 200L

        /** 秒からナノ秒へ変換する係数。 */
        const val NANOS_PER_SECOND = 1_000_000_000L

        /** usable observed tick とみなす水平精度上限。 */
        const val USABLE_LOCATION_ACCURACY_METRES = 20f

        /** usable tick 途絶とみなす秒数。 */
        const val LOST_SIGNAL_THRESHOLD_SECONDS = 3f
    }
}

/**
 * tracker snapshot が作れない場合の初期 [GuidanceProgress] を作る。
 *
 * @return route summary と始点から作った最低限の進捗
 */
private fun RouteDetail.toInitialProgress(): GuidanceProgress = GuidanceProgress(
    distanceRemainingMeters = distanceMeters.toInt(),
    durationRemainingSeconds = durationSeconds.toInt(),
    etaEpochMillis = System.currentTimeMillis() + durationSeconds.toLong() * 1_000L,
    traveledMeters = 0,
    elapsedSeconds = 0,
    currentCumulativeMeters = 0.0,
    snappedLocation = origin,
    bearingDegrees = 0f,
    observedLocation = null,
    observedBearingDegrees = null,
    observedAccuracyMeters = null,
    locationTimestampMillis = System.currentTimeMillis(),
    locationElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
    vehicleSpeedMps = null,
    currentRoadName = null,
    currentRoadClass = roadClassSegments.firstOrNull()?.roadClass ?: RoadClass.ORDINARY,
    currentSpeedLimitKmh = null,
    routeMatchState = RouteMatchState.ON_ROUTE,
    positionSource = VehiclePositionSource.INITIAL,
    projectionErrorMeters = null,
)
