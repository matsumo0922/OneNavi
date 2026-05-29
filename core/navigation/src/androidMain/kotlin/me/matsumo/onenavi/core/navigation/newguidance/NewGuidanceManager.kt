package me.matsumo.onenavi.core.navigation.newguidance

import android.os.SystemClock
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.matsumo.onenavi.core.datasource.location.CurrentLocationDataSource
import me.matsumo.onenavi.core.datasource.location.UserLocation
import me.matsumo.onenavi.core.model.RoadClass
import me.matsumo.onenavi.core.model.RouteDetail
import me.matsumo.onenavi.core.navigation.extnav.ExtNavGuidanceTracker
import me.matsumo.onenavi.core.navigation.extnav.ExtNavProgressSnapshot
import me.matsumo.onenavi.core.navigation.extnav.ExtNavRouteRegistry
import me.matsumo.onenavi.core.navigation.newguidance.model.GuidanceProgress
import me.matsumo.onenavi.core.navigation.newguidance.model.GuidanceState
import me.matsumo.onenavi.core.navigation.newguidance.model.RouteMatchState
import me.matsumo.onenavi.core.navigation.newguidance.presentation.GuidancePresentation
import me.matsumo.onenavi.core.navigation.voice.scheduler.VoiceAnnouncementController

/**
 * Guidance 期 (案内中) のマネージャ。
 *
 * 地図描画・callout・音声案内はすべて自前で行い、Google Navigation SDK の Navigator は使わない。
 * この manager は route payload を tracker に attach し、端末位置 tick を流して [GuidanceState] を更新する。
 * 音声案内は attach 時に [VoiceAnnouncementController] へ同じ payload / 距離変換 context を渡し、tracker snapshot を
 * 流して発話を駆動する。リルート判定・到着判定は [me.matsumo.onenavi.core.navigation.extnav] 配下の周辺コンポーネントへ
 * 順次 fan-out する。
 */
class NewGuidanceManager internal constructor(
    private val routeRegistry: ExtNavRouteRegistry? = null,
    private val guidanceTracker: ExtNavGuidanceTracker? = null,
    private val locationDataSource: CurrentLocationDataSource? = null,
    private val voiceController: VoiceAnnouncementController? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {

    private val _state = MutableStateFlow<GuidanceState>(GuidanceState.Idle)

    private var sessionJobs: List<Job> = emptyList()
    private var nextSessionId: Long = FIRST_SESSION_ID

    @Volatile
    private var activeSessionId: Long? = null

    private var isSessionActive: Boolean = false

    /** Guidance 期の現在状態。 */
    val state: StateFlow<GuidanceState> = _state.asStateFlow()

    /**
     * 指定ルートで案内を開始する。
     *
     * Preview 時に保存済みの payload を tracker に attach し、まず origin 位置で初期 snapshot を作る。
     * その後、位置情報 data source が注入されている場合は lastKnown と連続 GPS tick を tracker へ流す。
     *
     * @param route 案内対象ルート
     */
    fun startGuidance(route: RouteDetail) {
        stopGuidanceSession(detachTracker = isSessionActive)
        Napier.i(tag = TAG) { "Guidance started: routeId=${route.id}" }
        val sessionId = consumeNextSessionId()
        val trackerSnapshot = startTrackerForRoute(route)
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
        Napier.i(tag = TAG) { "Guidance stopped" }
        stopGuidanceSession(detachTracker = isSessionActive)
        _state.value = GuidanceState.Idle
    }

    /**
     * Manager 破棄時に呼ぶ。
     *
     * Singleton として再利用できるように manager scope は cancel せず、現在の案内 session だけを止める。
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
     * @return tracker が作った初期 snapshot。作れない場合は null
     */
    private fun startTrackerForRoute(route: RouteDetail): ExtNavProgressSnapshot? {
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
        voiceController?.start(payload = payload, distanceContext = attachment.distanceContext)
        tracker.onLocation(route.toOriginUserLocation())

        val snapshot = tracker.snapshot.value
        if (snapshot == null) {
            Napier.w(tag = TAG) { "Tracker snapshot was not produced. routeId=${route.id}" }
            return null
        }

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
                publishGuidance(route = route, snapshot = snapshot)
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
     *
     * @param route 案内対象ルート
     * @param snapshot tracker が発行した進捗 snapshot
     */
    private fun publishGuidance(
        route: RouteDetail,
        snapshot: ExtNavProgressSnapshot,
    ) {
        _state.value = GuidanceState.Guiding(
            route = route,
            progress = snapshot.progress,
            presentation = snapshot.presentation,
        )
        voiceController?.onSnapshot(snapshot)
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
