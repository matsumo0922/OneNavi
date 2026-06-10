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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
import me.matsumo.onenavi.core.navigation.extnav.RouteGeometryMath
import me.matsumo.onenavi.core.navigation.extnav.RouteStopProgress
import me.matsumo.onenavi.core.navigation.newguidance.model.GuidanceEvent
import me.matsumo.onenavi.core.navigation.newguidance.model.GuidanceProgress
import me.matsumo.onenavi.core.navigation.newguidance.model.GuidanceState
import me.matsumo.onenavi.core.navigation.newguidance.model.RouteMatchState
import me.matsumo.onenavi.core.navigation.newguidance.presentation.GuidancePresentation
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
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {

    private val _state = MutableStateFlow<GuidanceState>(GuidanceState.Idle)
    private val _events = MutableSharedFlow<GuidanceEvent>(extraBufferCapacity = EVENT_BUFFER_CAPACITY)

    private var sessionJobs: List<Job> = emptyList()
    private var nextSessionId: Long = FIRST_SESSION_ID

    @Volatile
    private var activeSessionId: Long? = null

    private var isSessionActive: Boolean = false

    private var currentRoute: RouteDetail? = null
    private var rerouteJob: Job? = null

    /** Guidance 期の現在状態。 */
    val state: StateFlow<GuidanceState> = _state.asStateFlow()

    /** Guidance 期の一度きりのイベント。 */
    val events: SharedFlow<GuidanceEvent> = _events.asSharedFlow()

    /**
     * 指定ルートで案内を開始する。
     *
     * Preview 時に保存済みの payload を tracker に attach し、まず origin 位置で初期 snapshot を作る。
     * その後、位置情報 data source が注入されている場合は lastKnown と連続 GPS tick を tracker へ流す。
     *
     * @param route 案内対象ルート
     */
    fun startGuidance(route: RouteDetail) {
        beginGuidance(route = route, resetReroute = true)
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
        stopGuidanceSession(detachTracker = isSessionActive)
        if (resetReroute) rerouteDetector?.detach()
        Napier.i(tag = TAG) { "Guidance started: routeId=${route.id}" }
        currentRoute = route
        val sessionId = consumeNextSessionId()
        val trackerSnapshot = startTrackerForRoute(route, announceOpening = resetReroute)
        activeSessionId = sessionId
        isSessionActive = true
        _state.value = guidingStateFrom(route = route, snapshot = trackerSnapshot)
        startGuidanceSession(
            route = route,
            sessionId = sessionId,
        )
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
        rerouteJob?.cancel()
        rerouteJob = null
        stopGuidanceSession(detachTracker = isSessionActive)
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

        val attachment = tracker.attach(payload = payload, route = route)
        rerouteDetector?.attach(route)
        tracker.onLocation(route.toOriginUserLocation())

        val snapshot = tracker.snapshot.value
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
     * 案内 session 用の coroutine 群を開始する。
     *
     * tracker snapshot の state 反映と、端末位置の tracker 投入を別 job として開始する。
     *
     * @param route 案内対象ルート
     * @param sessionId この案内開始に対応する session id
     */
    private fun startGuidanceSession(
        route: RouteDetail,
        sessionId: Long,
    ) {
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
        if (isDestinationReached(snapshot)) {
            completeDestinationGuidance(route = activeRoute)
            return false
        }

        val updatedRoute = activeRoute.withPassedIntermediateWaypointsRemoved(
            currentCumulativeMeters = snapshot.currentCumulativeMeters,
        )
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
        rerouteJob?.cancel()
        rerouteJob = null
        stopGuidanceSession(detachTracker = isSessionActive)
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
        stopGuidanceSession(detachTracker = true)
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
                    )
                }

                if (!isActiveSession(sessionId)) return@launch

                dataSource.locationUpdates().collect { location ->
                    forwardLocationTick(
                        tracker = tracker,
                        location = location,
                        sessionId = sessionId,
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
    ) {
        if (!isActiveSession(sessionId)) return

        tracker.onLocation(location)
    }

    /**
     * 案内 session を停止する。
     *
     * @param detachTracker true の場合は tracker の attach 状態も破棄する
     */
    private fun stopGuidanceSession(detachTracker: Boolean) {
        activeSessionId = null
        sessionJobs.forEach { job -> job.cancel() }
        sessionJobs = emptyList()
        isSessionActive = false

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
    }
}

/**
 * route origin を初期 tick 用の [UserLocation] に変換する。
 *
 * @return route 始点を表す仮の現在地
 */
private fun RouteDetail.toOriginUserLocation(): UserLocation = UserLocation(
    latitude = origin.latitude,
    longitude = origin.longitude,
    bearingDegrees = null,
    speedMps = null,
    accuracyMeters = 0f,
    timestampMillis = System.currentTimeMillis(),
    elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
)

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
    projectionErrorMeters = 0.0,
)
