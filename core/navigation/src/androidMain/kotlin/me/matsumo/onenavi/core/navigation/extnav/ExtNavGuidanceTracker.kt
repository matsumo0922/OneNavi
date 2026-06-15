package me.matsumo.onenavi.core.navigation.extnav

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.matsumo.onenavi.core.datasource.location.UserLocation
import me.matsumo.onenavi.core.model.RoadClass
import me.matsumo.onenavi.core.model.RouteDetail
import me.matsumo.onenavi.core.model.RoutePoint
import me.matsumo.onenavi.core.navigation.newguidance.model.GuidanceProgress
import me.matsumo.onenavi.core.navigation.newguidance.model.RouteMatchState
import me.matsumo.onenavi.core.navigation.newguidance.model.VehiclePositionSource
import me.matsumo.onenavi.core.navigation.newguidance.presentation.GuidancePresentationProjector
import me.matsumo.onenavi.core.navigation.newguidance.progress.GuidanceRouteSelector
import me.matsumo.onenavi.core.navigation.newguidance.progress.RouteProjectionContext
import me.matsumo.onenavi.core.navigation.newguidance.semantic.GuidanceRoute
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import me.matsumo.drive.supporter.api.guidance.domain.SpeedLimitSegment as ExtNavSpeedLimitSegment

/**
 * GPS tick をルート geometry 上の進捗 snapshot に変換する tracker。
 *
 * このクラスの責務は「現在地を route geometry に投影して [ExtNavProgressSnapshot] を更新する」
 * ところまで。リルート確定、音声発話、再探索、ネットワーク I/O は持たない。
 *
 * 案内イベントの意味づけ (主案内・施設・レーン等) は attach 時に [GuidanceRouteMapper] が
 * 位置非依存の [GuidanceRoute] に射影し、tick ごとに [GuidanceRouteSelector] がカーソルを
 * 選び、[GuidanceProgressAdapter] が UI モデルへ変換する。tracker はその橋渡しと位置投影に
 * 専念する。
 */
@Suppress("unused")
class ExtNavGuidanceTracker {

    private val routeMapper = GuidanceRouteMapper()
    private val distanceContextFactory = RouteDistanceContextFactory()
    private val routeSelector = GuidanceRouteSelector()
    private val presentationProjector = GuidancePresentationProjector()

    private val _snapshot = MutableStateFlow<ExtNavProgressSnapshot?>(null)

    /** 現在の projection snapshot。未 attach または detach 後は null。 */
    val snapshot: StateFlow<ExtNavProgressSnapshot?> = _snapshot.asStateFlow()

    private var attachedRoute: AttachedRoute? = null
    private var lastProjection: RouteProjection? = null
    private var offRouteCandidate: OffRouteCandidate? = null
    private var guidanceStartTimestampMillis: Long? = null
    private var previousRawPoint: RoutePoint? = null
    private var deadReckoningState: DeadReckoningState? = null

    /**
     * 案内対象の route と、Preview 時にキャッシュした外部ナビ API ライブラリ由来 payload を紐付ける。
     *
     * tick ごとの計算を軽くするため、ここで geometry の累積距離を事前計算し、payload を
     * [GuidanceRoute] に射影する。射影済みの案内ルートと source→geometry 距離変換 context を
     * [ExtNavGuidanceAttachment] として返し、音声プランなど後段が同じ成果物を共有できるようにする。
     *
     * @param payload Preview 時に取得した外部ナビ API ライブラリ由来 payload
     * @param route 案内対象の中立 route
     * @param tunnelMapStatus 選択 route に紐づくトンネル区間の準備状態
     * @return tick 非依存の attach 時成果物
     */
    @Suppress("unused")
    internal fun attach(
        payload: ExtNavRoutePayload,
        route: RouteDetail,
        tunnelMapStatus: TunnelMapStatus = emptyTunnelMapStatus(),
    ): ExtNavGuidanceAttachment {
        val attached = buildAttachedRoute(
            payload = payload,
            route = route,
            tunnelMapStatus = tunnelMapStatus,
        )
        attachedRoute = attached
        lastProjection = null
        offRouteCandidate = null
        guidanceStartTimestampMillis = null
        previousRawPoint = null
        deadReckoningState = null
        _snapshot.value = null
        return ExtNavGuidanceAttachment(
            guidanceRoute = attached.guidanceRoute,
            distanceContext = attached.distanceContext,
        )
    }

    /**
     * GPS 位置を 1 tick 投入し、ルート上の snappedLocation / 残距離 / 次 GP などを更新する。
     */
    @Suppress("unused")
    fun onLocation(location: UserLocation) {
        val attached = attachedRoute ?: return
        if (guidanceStartTimestampMillis == null) {
            guidanceStartTimestampMillis = location.timestampMillis
        }
        val projection = projectLocation(
            route = attached.route,
            cumulativeMetres = attached.cumulativeMetres,
            location = location,
            previousProjection = lastProjection,
        )

        val snapshot = buildSnapshot(
            attached = attached,
            projection = projection,
            location = location,
        )
        lastProjection = projection
        previousRawPoint = location.toRoutePoint()
        deadReckoningState = snapshot.deadReckoningSeed(location)
        _snapshot.value = snapshot
    }

    /**
     * route geometry と直近の実測速度から DR snapshot を 1 tick 進める。
     *
     * @param nowElapsedRealtimeNanos 現在の monotonic clock
     * @param nowWallClockMillis 現在の wall-clock
     * @return DR snapshot を発行できた場合は true
     */
    fun advanceDeadReckoning(
        nowElapsedRealtimeNanos: Long,
        nowWallClockMillis: Long,
    ): Boolean {
        val attached = attachedRoute ?: return false
        val previousState = deadReckoningState ?: return false
        val previousProjection = lastProjection ?: return false
        val speedMps = previousState.speedMps.takeIf { speed -> speed >= MIN_DEAD_RECKONING_SPEED_MPS }
            ?: return false
        val tunnelSegment = attached.tunnelMapStatus.findReachableSegment(
            currentCumulativeMeters = previousState.currentCumulativeMeters,
            speedMps = speedMps,
        ) ?: return false

        val elapsedSeconds = ((nowElapsedRealtimeNanos - previousState.lastElapsedRealtimeNanos).coerceAtLeast(0L)) /
            NANOS_PER_SECOND.toDouble()
        val totalDurationSeconds = ((nowElapsedRealtimeNanos - previousState.startElapsedRealtimeNanos).coerceAtLeast(0L)) /
            NANOS_PER_SECOND.toDouble()
        val nextDistanceMeters = previousState.currentCumulativeMeters + speedMps * elapsedSeconds
        val cappedByDuration = totalDurationSeconds >= MAX_DEAD_RECKONING_DURATION_SECONDS
        val cappedByDistance = nextDistanceMeters - previousState.startCumulativeMeters >= MAX_DEAD_RECKONING_DISTANCE_METRES
        val cappedByExit = nextDistanceMeters >= tunnelSegment.endGeometryMeters
        val currentCumulativeMeters = when {
            cappedByExit -> tunnelSegment.endGeometryMeters
            cappedByDistance -> previousState.startCumulativeMeters + MAX_DEAD_RECKONING_DISTANCE_METRES
            cappedByDuration -> previousState.currentCumulativeMeters
            else -> nextDistanceMeters
        }.coerceIn(0.0, attached.totalGeometryMetres)
        val projection = projectionAtDistance(
            attached = attached,
            currentCumulativeMeters = currentCumulativeMeters,
            fallback = previousProjection,
        )
        val snapshot = buildDeadReckoningSnapshot(
            attached = attached,
            projection = projection,
            speedMps = speedMps,
            timestampMillis = nowWallClockMillis,
            elapsedRealtimeNanos = nowElapsedRealtimeNanos,
        )

        lastProjection = projection
        deadReckoningState = previousState.copy(
            currentCumulativeMeters = currentCumulativeMeters,
            lastElapsedRealtimeNanos = nowElapsedRealtimeNanos,
        )
        clearOffRouteCandidate()
        _snapshot.value = snapshot
        return true
    }

    /**
     * route origin の初期 snapshot を発行する。
     *
     * @param timestampMillis wall-clock 時刻
     * @param elapsedRealtimeNanos monotonic clock 時刻
     * @return 初期 snapshot。未 attach の場合は null
     */
    fun initializeAtRouteOrigin(
        timestampMillis: Long,
        elapsedRealtimeNanos: Long,
    ): ExtNavProgressSnapshot? {
        val attached = attachedRoute ?: return null
        val projection = projectionAtDistance(
            attached = attached,
            currentCumulativeMeters = 0.0,
            fallback = null,
        )
        val snapshot = buildInitialSnapshot(
            attached = attached,
            projection = projection,
            timestampMillis = timestampMillis,
            elapsedRealtimeNanos = elapsedRealtimeNanos,
        )

        lastProjection = projection
        _snapshot.value = snapshot
        return snapshot
    }

    /** attach 済み route、前回 projection、公開 snapshot を破棄する。 */
    @Suppress("unused")
    fun detach() {
        attachedRoute = null
        lastProjection = null
        offRouteCandidate = null
        guidanceStartTimestampMillis = null
        previousRawPoint = null
        deadReckoningState = null
        _snapshot.value = null
    }

    // ---------------------------------------------------------------------
    // Attach-time preparation
    // ---------------------------------------------------------------------

    /**
     * route ごとの immutable な事前計算結果を作る。
     *
     * payload を [GuidanceRoute] に射影し、tick 時に参照する geometry コンテキストと
     * 主案内イベントの距離一覧 (off-route 判定用) を併せて保持する。
     *
     * @param payload Preview 時に取得した外部ナビ API ライブラリ由来 payload
     * @param route 案内対象の中立 route
     * @return tick 時に参照する attach 済み route 情報
     */
    private fun buildAttachedRoute(
        payload: ExtNavRoutePayload,
        route: RouteDetail,
        tunnelMapStatus: TunnelMapStatus,
    ): AttachedRoute {
        val cumulativeMetres = buildCumulativeGeometryMetres(route.geometry)
        val totalGeometryMetres = cumulativeMetres.lastOrNull() ?: 0.0
        val guidanceRoute = routeMapper.map(payload = payload, route = route)
        val distanceContext = distanceContextFactory.create(
            payload = payload,
            route = route,
            cumulativeMetres = cumulativeMetres,
            totalGeometryMetres = totalGeometryMetres,
        )
        val projectionContext = RouteProjectionContext(
            route = route,
            cumulativeMetres = cumulativeMetres,
            totalGeometryMetres = totalGeometryMetres,
        )
        val primaryEventMetres = guidanceRoute.events
            .filter { event -> event.primary != null }
            .map { event -> event.anchor.geometryDistanceFromStartMeters }
        val speedLimitSegments = buildSpeedLimitSegments(payload, distanceContext)

        return AttachedRoute(
            route = route,
            cumulativeMetres = cumulativeMetres,
            totalGeometryMetres = totalGeometryMetres,
            guidanceRoute = guidanceRoute,
            distanceContext = distanceContext,
            projectionContext = projectionContext,
            primaryEventMetres = primaryEventMetres,
            speedLimitSegments = speedLimitSegments,
            tunnelMapStatus = tunnelMapStatus,
        )
    }

    /**
     * source 距離基準の制限速度区間を geometry 距離基準へ変換する。
     *
     * @param payload attach 対象の route payload
     * @param distanceContext source→geometry 距離変換 context
     * @return geometry 距離基準へ変換済みの制限速度区間
     */
    private fun buildSpeedLimitSegments(payload: ExtNavRoutePayload, distanceContext: ExtNavRouteDistanceContext): List<SpeedLimitGeometrySegment> {
        return payload.routeGuidance.speedLimitSegments
            .mapNotNull { segment -> segment.toGeometrySegment(distanceContext) }
            .sortedWith(
                compareBy<SpeedLimitGeometrySegment> { segment -> segment.startGeometryMetres }
                    .thenBy { segment -> segment.endGeometryMetres },
            )
    }

    /**
     * 外部ナビ API ライブラリ由来の制限速度区間を geometry 距離基準へ変換する。
     *
     * @param distanceContext source→geometry 距離変換 context
     * @return 表示可能な速度区間。異常値や長さのない区間は null
     */
    private fun ExtNavSpeedLimitSegment.toGeometrySegment(distanceContext: ExtNavRouteDistanceContext): SpeedLimitGeometrySegment? {
        val validLimitKmh = limitKmh.takeIf { limit -> limit in MIN_SPEED_LIMIT_KMH..MAX_SPEED_LIMIT_KMH }
            ?: return null

        val startSourceMetres = startDistanceFromRouteStartMetres.toDouble()
        val endSourceMetres = endDistanceFromRouteStartMetres.toDouble()
        if (endSourceMetres <= startSourceMetres) return null

        val startGeometryMetres = distanceContext.geometryMetresFor(startSourceMetres)
        val endGeometryMetres = distanceContext.geometryMetresFor(endSourceMetres)
        if (endGeometryMetres <= startGeometryMetres) return null

        return SpeedLimitGeometrySegment(
            startGeometryMetres = startGeometryMetres,
            endGeometryMetres = endGeometryMetres,
            limitKmh = validLimitKmh,
        )
    }

    /**
     * route geometry の各点までの累積距離を作る。
     *
     * @param geometry route polyline
     * @return geometry index と同じ順序の累積距離配列
     */
    private fun buildCumulativeGeometryMetres(geometry: List<RoutePoint>): DoubleArray =
        RouteGeometryMath.cumulativeMetres(geometry)

    // ---------------------------------------------------------------------
    // Tick-time snapshot building
    // ---------------------------------------------------------------------

    /**
     * projection と GPS tick から周辺コンポーネント向け snapshot を組み立てる。
     *
     * @param attached attach 済み route 情報
     * @param projection 今回 tick の route projection
     * @param location 今回 tick の生 GPS
     * @return UI 用 progress と projection 生データを含む snapshot
     */
    private fun buildSnapshot(
        attached: AttachedRoute,
        projection: RouteProjection,
        location: UserLocation,
    ): ExtNavProgressSnapshot {
        val distanceRemainingMetres = remainingDistanceMetres(
            attached = attached,
            projection = projection,
        )
        val selection = routeSelector.select(
            route = attached.guidanceRoute,
            currentCumulativeMeters = projection.currentCumulativeMeters,
        )
        val vehicleHeadingDegrees = resolveVehicleHeading(location)
        val wrongDirectionTick = isWrongDirectionTick(
            projection = projection,
            vehicleHeadingDegrees = vehicleHeadingDegrees,
        )
        val offRouteCandidate = isOffRouteCandidate(
            projection = projection,
            location = location,
            attached = attached,
            wrongDirectionTick = wrongDirectionTick,
        )
        val routeMatchState = updateRouteMatchState(
            attached = attached,
            projection = projection,
            location = location,
            isOffRouteCandidate = offRouteCandidate,
            wrongDirectionTick = wrongDirectionTick,
        )
        val currentRoadClass = currentRoadClassFor(
            route = attached.route,
            matchedSegmentIndex = projection.matchedSegmentIndex,
        )
        val currentSpeedLimitKmh = currentSpeedLimitKmhFor(attached, projection.currentCumulativeMeters)
        val progress = buildProgress(
            attached = attached,
            projection = projection,
            location = location,
            distanceRemainingMetres = distanceRemainingMetres,
            routeMatchState = routeMatchState,
            currentRoadClass = currentRoadClass,
            currentSpeedLimitKmh = currentSpeedLimitKmh,
        )
        val presentation = presentationProjector.project(
            guidanceRoute = attached.guidanceRoute,
            selection = selection,
            context = attached.projectionContext,
            currentCumulativeMeters = projection.currentCumulativeMeters,
            currentRoadClass = currentRoadClass,
            currentRoadName = progress.currentRoadName,
            timestampMillis = location.timestampMillis,
        )

        return ExtNavProgressSnapshot(
            progress = progress,
            presentation = presentation,
            rawLocation = location,
            currentCumulativeMeters = projection.currentCumulativeMeters,
            distanceRemainingMeters = distanceRemainingMetres,
            matchedSegmentIndex = projection.matchedSegmentIndex,
            projectionErrorMeters = projection.projectionErrorMeters,
            locationTimestampMillis = location.timestampMillis,
            vehicleSpeedMps = location.speedMps,
            routeMatchState = routeMatchState,
            positionSource = VehiclePositionSource.OBSERVED,
            isOffRouteCandidate = offRouteCandidate,
            nextGuidancePointIndex = selection.nextPrimaryEvent?.anchor?.sourceGuidancePointIndex,
            headingDegrees = vehicleHeadingDegrees,
        )
    }

    /**
     * DR tick 用の snapshot を組み立てる。
     *
     * @param attached attach 済み route 情報
     * @param projection DR の route projection
     * @param speedMps DR に使う保持速度
     * @param timestampMillis wall-clock 時刻
     * @param elapsedRealtimeNanos monotonic clock 時刻
     * @return DR snapshot
     */
    private fun buildDeadReckoningSnapshot(
        attached: AttachedRoute,
        projection: RouteProjection,
        speedMps: Float,
        timestampMillis: Long,
        elapsedRealtimeNanos: Long,
    ): ExtNavProgressSnapshot {
        val distanceRemainingMetres = remainingDistanceMetres(
            attached = attached,
            projection = projection,
        )
        val selection = routeSelector.select(
            route = attached.guidanceRoute,
            currentCumulativeMeters = projection.currentCumulativeMeters,
        )
        val currentRoadClass = currentRoadClassFor(
            route = attached.route,
            matchedSegmentIndex = projection.matchedSegmentIndex,
        )
        val currentSpeedLimitKmh = currentSpeedLimitKmhFor(attached, projection.currentCumulativeMeters)
        val progress = buildProgressFromProjection(
            attached = attached,
            projection = projection,
            distanceRemainingMetres = distanceRemainingMetres,
            routeMatchState = RouteMatchState.ON_ROUTE,
            currentRoadClass = currentRoadClass,
            currentSpeedLimitKmh = currentSpeedLimitKmh,
            timestampMillis = timestampMillis,
            elapsedRealtimeNanos = elapsedRealtimeNanos,
            vehicleSpeedMps = speedMps,
            positionSource = VehiclePositionSource.DEAD_RECKONING,
        )
        val presentation = presentationProjector.project(
            guidanceRoute = attached.guidanceRoute,
            selection = selection,
            context = attached.projectionContext,
            currentCumulativeMeters = projection.currentCumulativeMeters,
            currentRoadClass = currentRoadClass,
            currentRoadName = progress.currentRoadName,
            timestampMillis = timestampMillis,
        )

        return ExtNavProgressSnapshot(
            progress = progress,
            presentation = presentation,
            rawLocation = null,
            currentCumulativeMeters = projection.currentCumulativeMeters,
            distanceRemainingMeters = distanceRemainingMetres,
            matchedSegmentIndex = projection.matchedSegmentIndex,
            projectionErrorMeters = null,
            locationTimestampMillis = timestampMillis,
            vehicleSpeedMps = speedMps,
            routeMatchState = RouteMatchState.ON_ROUTE,
            positionSource = VehiclePositionSource.DEAD_RECKONING,
            isOffRouteCandidate = false,
            nextGuidancePointIndex = selection.nextPrimaryEvent?.anchor?.sourceGuidancePointIndex,
            headingDegrees = projection.segmentBearingDegrees,
        )
    }

    /**
     * 初期表示用の snapshot を組み立てる。
     *
     * @param attached attach 済み route 情報
     * @param projection route origin の projection
     * @param timestampMillis wall-clock 時刻
     * @param elapsedRealtimeNanos monotonic clock 時刻
     * @return 初期 snapshot
     */
    private fun buildInitialSnapshot(
        attached: AttachedRoute,
        projection: RouteProjection,
        timestampMillis: Long,
        elapsedRealtimeNanos: Long,
    ): ExtNavProgressSnapshot {
        val distanceRemainingMetres = remainingDistanceMetres(
            attached = attached,
            projection = projection,
        )
        val selection = routeSelector.select(
            route = attached.guidanceRoute,
            currentCumulativeMeters = projection.currentCumulativeMeters,
        )
        val currentRoadClass = currentRoadClassFor(
            route = attached.route,
            matchedSegmentIndex = projection.matchedSegmentIndex,
        )
        val currentSpeedLimitKmh = currentSpeedLimitKmhFor(attached, projection.currentCumulativeMeters)
        val progress = buildProgressFromProjection(
            attached = attached,
            projection = projection,
            distanceRemainingMetres = distanceRemainingMetres,
            routeMatchState = RouteMatchState.ON_ROUTE,
            currentRoadClass = currentRoadClass,
            currentSpeedLimitKmh = currentSpeedLimitKmh,
            timestampMillis = timestampMillis,
            elapsedRealtimeNanos = elapsedRealtimeNanos,
            vehicleSpeedMps = null,
            positionSource = VehiclePositionSource.INITIAL,
        )
        val presentation = presentationProjector.project(
            guidanceRoute = attached.guidanceRoute,
            selection = selection,
            context = attached.projectionContext,
            currentCumulativeMeters = projection.currentCumulativeMeters,
            currentRoadClass = currentRoadClass,
            currentRoadName = progress.currentRoadName,
            timestampMillis = timestampMillis,
        )

        return ExtNavProgressSnapshot(
            progress = progress,
            presentation = presentation,
            rawLocation = null,
            currentCumulativeMeters = projection.currentCumulativeMeters,
            distanceRemainingMeters = distanceRemainingMetres,
            matchedSegmentIndex = projection.matchedSegmentIndex,
            projectionErrorMeters = null,
            locationTimestampMillis = timestampMillis,
            vehicleSpeedMps = null,
            routeMatchState = RouteMatchState.ON_ROUTE,
            positionSource = VehiclePositionSource.INITIAL,
            isOffRouteCandidate = false,
            nextGuidancePointIndex = selection.nextPrimaryEvent?.anchor?.sourceGuidancePointIndex,
            headingDegrees = projection.segmentBearingDegrees,
        )
    }

    /**
     * 現在 projection から目的地までの残距離を返す。
     *
     * @param attached route 総距離を持つ attach 済み情報
     * @param projection 現在 projection
     * @return geometry 上の残距離。負値は 0 に丸める
     */
    private fun remainingDistanceMetres(
        attached: AttachedRoute,
        projection: RouteProjection,
    ): Double = (attached.totalGeometryMetres - projection.currentCumulativeMeters)
        .coerceAtLeast(0.0)

    /**
     * UI が直接読む位置スカラ [GuidanceProgress] を組み立てる。
     *
     * 案内イベント由来の表示 (次案内・リスト行・レーン) は presentation 層の projector が別途
     * 射影するため、ここでは位置スカラと走行状態だけを詰める。
     *
     * @param attached attach 済み route 情報
     * @param projection 今回 tick の projection
     * @param location 今回 tick の生 GPS
     * @param distanceRemainingMetres 事前算出済みの残距離
     * @param routeMatchState 現在位置と案内 route の一致状態
     * @param currentRoadClass 現在走行中の道路種別
     * @param currentSpeedLimitKmh 現在区間の制限速度
     * @return UI 表示用の案内進捗
     */
    private fun buildProgress(
        attached: AttachedRoute,
        projection: RouteProjection,
        location: UserLocation,
        distanceRemainingMetres: Double,
        routeMatchState: RouteMatchState,
        currentRoadClass: RoadClass,
        currentSpeedLimitKmh: Int?,
    ): GuidanceProgress {
        val durationRemainingSeconds = remainingDurationSeconds(
            route = attached.route,
            totalGeometryMetres = attached.totalGeometryMetres,
            currentCumulativeMeters = projection.currentCumulativeMeters,
        ).roundToInt()

        return GuidanceProgress(
            distanceRemainingMeters = distanceRemainingMetres.roundToInt(),
            durationRemainingSeconds = durationRemainingSeconds,
            etaEpochMillis = location.timestampMillis + durationRemainingSeconds.toLong() * MILLIS_PER_SECOND,
            traveledMeters = projection.currentCumulativeMeters.roundToInt(),
            elapsedSeconds = elapsedSeconds(location.timestampMillis),
            currentCumulativeMeters = projection.currentCumulativeMeters,
            snappedLocation = projection.snappedLocation,
            bearingDegrees = location.bearingDegrees ?: projection.segmentBearingDegrees,
            observedLocation = location.toRoutePoint(),
            observedBearingDegrees = location.bearingDegrees,
            observedAccuracyMeters = location.accuracyMeters,
            locationTimestampMillis = location.timestampMillis,
            locationElapsedRealtimeNanos = location.elapsedRealtimeNanos,
            vehicleSpeedMps = location.speedMps,
            currentRoadName = currentRoadNameFor(
                attached = attached,
                currentCumulativeMeters = projection.currentCumulativeMeters,
            ),
            currentRoadClass = currentRoadClass,
            currentSpeedLimitKmh = currentSpeedLimitKmh,
            routeMatchState = routeMatchState,
            positionSource = VehiclePositionSource.OBSERVED,
            projectionErrorMeters = projection.projectionErrorMeters,
        )
    }

    /**
     * 観測値を持たない projection から UI progress を組み立てる。
     *
     * @param attached attach 済み route 情報
     * @param projection 今回 tick の projection
     * @param distanceRemainingMetres 事前算出済みの残距離
     * @param routeMatchState 現在位置と案内 route の一致状態
     * @param currentRoadClass 現在走行中の道路種別
     * @param currentSpeedLimitKmh 現在区間の制限速度
     * @param timestampMillis wall-clock 時刻
     * @param elapsedRealtimeNanos monotonic clock 時刻
     * @param vehicleSpeedMps 自車速度
     * @param positionSource 位置 source
     * @return UI 表示用の案内進捗
     */
    private fun buildProgressFromProjection(
        attached: AttachedRoute,
        projection: RouteProjection,
        distanceRemainingMetres: Double,
        routeMatchState: RouteMatchState,
        currentRoadClass: RoadClass,
        currentSpeedLimitKmh: Int?,
        timestampMillis: Long,
        elapsedRealtimeNanos: Long,
        vehicleSpeedMps: Float?,
        positionSource: VehiclePositionSource,
    ): GuidanceProgress {
        val durationRemainingSeconds = remainingDurationSeconds(
            route = attached.route,
            totalGeometryMetres = attached.totalGeometryMetres,
            currentCumulativeMeters = projection.currentCumulativeMeters,
        ).roundToInt()

        return GuidanceProgress(
            distanceRemainingMeters = distanceRemainingMetres.roundToInt(),
            durationRemainingSeconds = durationRemainingSeconds,
            etaEpochMillis = timestampMillis + durationRemainingSeconds.toLong() * MILLIS_PER_SECOND,
            traveledMeters = projection.currentCumulativeMeters.roundToInt(),
            elapsedSeconds = elapsedSeconds(timestampMillis),
            currentCumulativeMeters = projection.currentCumulativeMeters,
            snappedLocation = projection.snappedLocation,
            bearingDegrees = projection.segmentBearingDegrees,
            observedLocation = null,
            observedBearingDegrees = null,
            observedAccuracyMeters = null,
            locationTimestampMillis = timestampMillis,
            locationElapsedRealtimeNanos = elapsedRealtimeNanos,
            vehicleSpeedMps = vehicleSpeedMps,
            currentRoadName = currentRoadNameFor(
                attached = attached,
                currentCumulativeMeters = projection.currentCumulativeMeters,
            ),
            currentRoadClass = currentRoadClass,
            currentSpeedLimitKmh = currentSpeedLimitKmh,
            routeMatchState = routeMatchState,
            positionSource = positionSource,
            projectionErrorMeters = null,
        )
    }

    /**
     * 現在地が含まれる制限速度区間の速度を返す。
     *
     * @param attached attach 済み route 情報
     * @param currentCumulativeMeters 現在地の geometry 累積距離
     * @return 現在地が区間内なら制限速度。gap や異常値なら null
     */
    private fun currentSpeedLimitKmhFor(attached: AttachedRoute, currentCumulativeMeters: Double): Int? {
        val speedLimitSegments = attached.speedLimitSegments
        var lowIndex = 0
        var highIndex = speedLimitSegments.lastIndex

        while (lowIndex <= highIndex) {
            val middleIndex = (lowIndex + highIndex) ushr 1
            val segment = speedLimitSegments[middleIndex]
            val isBeforeSegment = currentCumulativeMeters < segment.startGeometryMetres
            val isAfterSegment = currentCumulativeMeters >= segment.endGeometryMetres

            when {
                isBeforeSegment -> highIndex = middleIndex - 1
                isAfterSegment -> lowIndex = middleIndex + 1
                else -> return segment.limitKmh
            }
        }

        return null
    }

    /**
     * 案内開始 (最初の位置 tick) からの経過秒を返す。負値は 0 に丸める。
     *
     * @param timestampMillis 今回 tick の時刻
     * @return 案内開始からの経過秒
     */
    private fun elapsedSeconds(timestampMillis: Long): Int {
        val startTimestampMillis = guidanceStartTimestampMillis ?: timestampMillis
        val elapsedMillis = (timestampMillis - startTimestampMillis).coerceAtLeast(0L)
        return (elapsedMillis / MILLIS_PER_SECOND).toInt()
    }

    /**
     * route 全体の所要時間を走行距離比率で按分し、残所要時間を算出する。
     *
     * @param route durationSeconds を持つ route
     * @param totalGeometryMetres geometry 上の総距離
     * @param currentCumulativeMeters 現在地の geometry 累積距離
     * @return 推定残所要時間秒
     */
    private fun remainingDurationSeconds(
        route: RouteDetail,
        totalGeometryMetres: Double,
        currentCumulativeMeters: Double,
    ): Double {
        if (route.durationSeconds <= 0.0) return 0.0
        if (totalGeometryMetres <= 0.0) return route.durationSeconds

        val remainingRatio = (1.0 - currentCumulativeMeters / totalGeometryMetres)
            .coerceIn(0.0, 1.0)
        return route.durationSeconds * remainingRatio
    }

    // ---------------------------------------------------------------------
    // Route projection
    // ---------------------------------------------------------------------

    /**
     * 生 GPS 位置を route geometry の最近接 segment に投影する。
     *
     * @param route 投影対象 route
     * @param cumulativeMetres geometry index ごとの累積距離
     * @param location 今回 tick の生 GPS
     * @param previousProjection 前回 tick の projection
     * @return 今回 tick の projection
     */
    private fun projectLocation(
        route: RouteDetail,
        cumulativeMetres: DoubleArray,
        location: UserLocation,
        previousProjection: RouteProjection?,
    ): RouteProjection {
        val geometry = route.geometry
        if (geometry.isEmpty()) return projectEmptyGeometry(location)
        if (geometry.size == 1 || cumulativeMetres.size <= 1) {
            return projectSinglePointGeometry(
                routePoint = geometry.first(),
                location = location,
            )
        }

        val searchWindow = projectionSearchWindow(
            route = route,
            previousProjection = previousProjection,
        )
        val projection = findBestProjectionInWindow(
            route = route,
            cumulativeMetres = cumulativeMetres,
            location = location,
            searchWindow = searchWindow,
        )

        return applyBackwardHysteresis(
            projection = projection,
            previousProjection = previousProjection,
        )
    }

    /**
     * geometry が空の route 用 projection を作る。
     *
     * @param location 今回 tick の生 GPS
     * @return 生 GPS を snappedLocation とみなす projection
     */
    private fun projectEmptyGeometry(location: UserLocation): RouteProjection {
        val point = RoutePoint(latitude = location.latitude, longitude = location.longitude)
        return RouteProjection(
            snappedLocation = point,
            currentCumulativeMeters = 0.0,
            matchedSegmentIndex = 0,
            projectionErrorMeters = 0.0,
            segmentBearingDegrees = location.bearingDegrees ?: 0f,
        )
    }

    /**
     * geometry が 1 点だけの route 用 projection を作る。
     *
     * @param routePoint 唯一の geometry 点
     * @param location 今回 tick の生 GPS
     * @return 唯一の geometry 点に snap した projection
     */
    private fun projectSinglePointGeometry(
        routePoint: RoutePoint,
        location: UserLocation,
    ): RouteProjection = RouteProjection(
        snappedLocation = routePoint,
        currentCumulativeMeters = 0.0,
        matchedSegmentIndex = 0,
        projectionErrorMeters = haversineMetres(routePoint, location.toRoutePoint()),
        segmentBearingDegrees = location.bearingDegrees ?: 0f,
    )

    /**
     * segment 探索範囲を決める。
     *
     * @param route 投影対象 route
     * @param previousProjection 前回 projection。null の場合は全 segment を探索する
     * @return 今回 tick で探索する segment index の閉区間
     */
    private fun projectionSearchWindow(
        route: RouteDetail,
        previousProjection: RouteProjection?,
    ): ProjectionSearchWindow {
        val lastSegmentIndex = route.geometry.lastIndex - 1
        val startIndex = previousProjection?.matchedSegmentIndex?.coerceIn(0, lastSegmentIndex) ?: 0

        val endIndex = if (previousProjection == null) {
            lastSegmentIndex
        } else {
            min(lastSegmentIndex, startIndex + MAX_SEGMENT_LOOKAHEAD)
        }

        return ProjectionSearchWindow(
            startIndex = startIndex,
            endIndex = endIndex,
        )
    }

    /**
     * 探索窓内の各 segment に投影し、誤差が最小の projection を選ぶ。
     *
     * @param route 投影対象 route
     * @param cumulativeMetres geometry index ごとの累積距離
     * @param location 今回 tick の生 GPS
     * @param searchWindow 探索する segment index 範囲
     * @return 誤差が最小の projection
     */
    private fun findBestProjectionInWindow(
        route: RouteDetail,
        cumulativeMetres: DoubleArray,
        location: UserLocation,
        searchWindow: ProjectionSearchWindow,
    ): RouteProjection {
        var bestProjection: RouteProjection? = null

        for (segmentIndex in searchWindow.startIndex..searchWindow.endIndex) {
            val candidate = projectToSegment(
                start = route.geometry[segmentIndex],
                end = route.geometry[segmentIndex + 1],
                cumulativeMetres = cumulativeMetres,
                segmentIndex = segmentIndex,
                location = location,
            )
            if (bestProjection == null || candidate.projectionErrorMeters < bestProjection.projectionErrorMeters) {
                bestProjection = candidate
            }
        }

        return bestProjection ?: projectToSegment(
            start = route.geometry[0],
            end = route.geometry[1],
            cumulativeMetres = cumulativeMetres,
            segmentIndex = 0,
            location = location,
        )
    }

    /**
     * 5m 以下の小さな後退は GPS jitter とみなし、前回 projection を維持する。
     *
     * @param projection 今回計算した projection
     * @param previousProjection 前回 tick の projection
     * @return ヒステリシス適用後の projection
     */
    private fun applyBackwardHysteresis(
        projection: RouteProjection,
        previousProjection: RouteProjection?,
    ): RouteProjection {
        val previousCumulativeMeters = previousProjection?.currentCumulativeMeters
            ?: return projection
        val backwardMetres = previousCumulativeMeters - projection.currentCumulativeMeters

        return if (backwardMetres > 0.0 && backwardMetres <= BACKWARD_HYSTERESIS_METRES) {
            previousProjection
        } else {
            projection
        }
    }

    /**
     * GPS 点を 1 segment 上の最近接点へ投影する。
     *
     * @param start segment 始点
     * @param end segment 終点
     * @param cumulativeMetres geometry index ごとの累積距離
     * @param segmentIndex 投影対象 segment index
     * @param location 今回 tick の生 GPS
     * @return segment 上の projection
     */
    private fun projectToSegment(
        start: RoutePoint,
        end: RoutePoint,
        cumulativeMetres: DoubleArray,
        segmentIndex: Int,
        location: UserLocation,
    ): RouteProjection {
        val point = location.toRoutePoint()
        val scale = meterScaleAt((start.latitude + end.latitude) / 2.0)
        val segmentX = (end.longitude - start.longitude) * scale.longitudeMetresPerDegree
        val segmentY = (end.latitude - start.latitude) * scale.latitudeMetresPerDegree
        val pointX = (point.longitude - start.longitude) * scale.longitudeMetresPerDegree
        val pointY = (point.latitude - start.latitude) * scale.latitudeMetresPerDegree
        val segmentLengthSquared = segmentX * segmentX + segmentY * segmentY
        val ratio = projectionRatio(
            pointX = pointX,
            pointY = pointY,
            segmentX = segmentX,
            segmentY = segmentY,
            segmentLengthSquared = segmentLengthSquared,
        )
        val snappedLocation = interpolateRoutePoint(
            start = start,
            end = end,
            ratio = ratio,
        )
        val segmentMetres = cumulativeMetres[segmentIndex + 1] - cumulativeMetres[segmentIndex]

        return RouteProjection(
            snappedLocation = snappedLocation,
            currentCumulativeMeters = cumulativeMetres[segmentIndex] + segmentMetres * ratio,
            matchedSegmentIndex = segmentIndex,
            projectionErrorMeters = haversineMetres(point, snappedLocation),
            segmentBearingDegrees = bearingDegrees(start, end),
        )
    }

    /**
     * route geometry 累積距離から projection を作る。
     *
     * @param attached attach 済み route 情報
     * @param currentCumulativeMeters route geometry 累積距離
     * @param fallback geometry が不十分な場合に使う前回 projection
     * @return 距離に対応する projection
     */
    private fun projectionAtDistance(
        attached: AttachedRoute,
        currentCumulativeMeters: Double,
        fallback: RouteProjection?,
    ): RouteProjection {
        val geometry = attached.route.geometry
        if (geometry.isEmpty()) {
            return fallback ?: RouteProjection(
                snappedLocation = attached.route.origin,
                currentCumulativeMeters = 0.0,
                matchedSegmentIndex = 0,
                projectionErrorMeters = 0.0,
                segmentBearingDegrees = 0f,
            )
        }
        if (geometry.size == 1 || attached.cumulativeMetres.size <= 1) {
            return RouteProjection(
                snappedLocation = geometry.first(),
                currentCumulativeMeters = 0.0,
                matchedSegmentIndex = 0,
                projectionErrorMeters = 0.0,
                segmentBearingDegrees = fallback?.segmentBearingDegrees ?: 0f,
            )
        }

        val cappedCumulativeMeters = currentCumulativeMeters.coerceIn(0.0, attached.totalGeometryMetres)
        var segmentIndex = 0
        while (segmentIndex < attached.cumulativeMetres.lastIndex - 1 &&
            attached.cumulativeMetres[segmentIndex + 1] < cappedCumulativeMeters
        ) {
            segmentIndex += 1
        }

        val startDistance = attached.cumulativeMetres[segmentIndex]
        val endDistance = attached.cumulativeMetres[segmentIndex + 1]
        val segmentDistance = endDistance - startDistance
        val ratio = if (segmentDistance <= 0.0) {
            0.0
        } else {
            ((cappedCumulativeMeters - startDistance) / segmentDistance).coerceIn(0.0, 1.0)
        }
        val start = geometry[segmentIndex]
        val end = geometry[segmentIndex + 1]

        return RouteProjection(
            snappedLocation = interpolateRoutePoint(
                start = start,
                end = end,
                ratio = ratio,
            ),
            currentCumulativeMeters = cappedCumulativeMeters,
            matchedSegmentIndex = segmentIndex,
            projectionErrorMeters = 0.0,
            segmentBearingDegrees = bearingDegrees(start, end),
        )
    }

    /**
     * 点を segment ベクトルへ射影したときの segment 内比率を計算する。
     *
     * @param pointX segment 始点基準の点 x 座標
     * @param pointY segment 始点基準の点 y 座標
     * @param segmentX segment ベクトルの x 成分
     * @param segmentY segment ベクトルの y 成分
     * @param segmentLengthSquared segment 長の二乗
     * @return 0.0 から 1.0 に丸めた segment 内比率
     */
    private fun projectionRatio(
        pointX: Double,
        pointY: Double,
        segmentX: Double,
        segmentY: Double,
        segmentLengthSquared: Double,
    ): Double {
        if (segmentLengthSquared <= 0.0) return 0.0
        return ((pointX * segmentX + pointY * segmentY) / segmentLengthSquared).coerceIn(0.0, 1.0)
    }

    /**
     * segment 始点と終点を比率で線形補間する。
     *
     * @param start segment 始点
     * @param end segment 終点
     * @param ratio segment 内比率
     * @return 補間後の route point
     */
    private fun interpolateRoutePoint(
        start: RoutePoint,
        end: RoutePoint,
        ratio: Double,
    ): RoutePoint = RouteGeometryMath.interpolateRoutePoint(start, end, ratio)

    // ---------------------------------------------------------------------
    // Off-route classification
    // ---------------------------------------------------------------------

    /**
     * 今回 tick が off-route 候補かどうかを 1 tick 単位で判定する。
     *
     * @param projection 今回 tick の projection
     * @param location 今回 tick の生 GPS
     * @param attached attach 済み route 情報
     * @param wrongDirectionTick 今回 tick が逆走方向か
     * @return debounce 前の off-route 候補なら true
     */
    private fun isOffRouteCandidate(
        projection: RouteProjection,
        location: UserLocation,
        attached: AttachedRoute,
        wrongDirectionTick: Boolean,
    ): Boolean {
        if (remainingDistanceMetres(attached, projection) <= ARRIVAL_SUPPRESSION_METRES) return false
        if (location.hasTooCoarseAccuracyForOffRoute()) return false
        if (location.hasTooSlowSpeedForOffRoute()) return false

        // 逆走中は route polyline 上に乗ったままで横ズレが小さく、距離閾値では候補に上がらない。
        // 進行方位が route 方向とほぼ真逆なら横ズレに関係なく候補とする。
        if (wrongDirectionTick) return true

        return projection.projectionErrorMeters >= offRouteCandidateThreshold(location)
    }

    /**
     * off-route 候補の継続状態を更新し、今回 tick の route match 状態を返す。
     *
     * candidate 閾値を超えた時点で表示は実位置側へ戻せるようにし、confirmed 閾値を超える状態が
     * 複数 tick / 一定時間続いた場合だけリルート対象として確定する。
     *
     * @param attached attach 済み route 情報
     * @param projection 今回 tick の projection
     * @param location 今回 tick の生 GPS
     * @param isOffRouteCandidate 今回 tick が off-route 候補か
     * @param wrongDirectionTick 今回 tick が逆走方向か
     * @return debounce 後の route match 状態
     */
    private fun updateRouteMatchState(
        attached: AttachedRoute,
        projection: RouteProjection,
        location: UserLocation,
        isOffRouteCandidate: Boolean,
        wrongDirectionTick: Boolean,
    ): RouteMatchState {
        if (!isOffRouteCandidate) {
            clearOffRouteCandidate()
            return RouteMatchState.ON_ROUTE
        }

        val previous = offRouteCandidate
        val candidate = previous
            ?.advance(timestampMillis = location.timestampMillis)
            ?: OffRouteCandidate(
                firstTimestampMillis = location.timestampMillis,
                latestTimestampMillis = location.timestampMillis,
                sampleCount = 1,
            )

        offRouteCandidate = candidate

        return if (isOffRouteConfirmedSample(
                attached = attached,
                projection = projection,
                location = location,
                candidate = candidate,
                wrongDirectionTick = wrongDirectionTick,
            )
        ) {
            RouteMatchState.OFF_ROUTE_CONFIRMED
        } else {
            RouteMatchState.OFF_ROUTE_CANDIDATE
        }
    }

    /**
     * 確定 off-route として扱うかを返す。
     *
     * 通常区間では 40m または accuracy x2.5 を超える逸脱が 1.5 秒以上 / 2 tick 以上続いた場合に確定する。
     * 案内地点や交差点付近では、間違えて曲がったケースを早く拾うため、方位差が十分大きければ 2 tick で確定する。
     *
     * @param attached attach 済み route 情報
     * @param projection 今回 tick の projection
     * @param location 今回 tick の生 GPS
     * @param candidate 継続中の off-route 候補
     * @param wrongDirectionTick 今回 tick が逆走方向か
     * @return リルート対象として確定できる場合 true
     */
    private fun isOffRouteConfirmedSample(
        attached: AttachedRoute,
        projection: RouteProjection,
        location: UserLocation,
        candidate: OffRouteCandidate,
        wrongDirectionTick: Boolean,
    ): Boolean {
        val exceedsConfirmedDistance = projection.projectionErrorMeters >= offRouteConfirmedThreshold(location)
        val hasEnoughSamples = candidate.sampleCount >= OFF_ROUTE_CONFIRMATION_SAMPLE_COUNT
        val hasEnoughDuration = candidate.sampleCount >= OFF_ROUTE_MIN_DURATION_SAMPLE_COUNT &&
            candidate.durationMillis >= OFF_ROUTE_CONFIRMATION_DURATION_MILLIS

        if (exceedsConfirmedDistance && (hasEnoughSamples || hasEnoughDuration)) {
            return true
        }

        // 逆走が一定 tick 継続したら、横ズレが小さくてもリルート対象として確定する。
        if (wrongDirectionTick && candidate.sampleCount >= WRONG_DIRECTION_CONFIRMATION_SAMPLE_COUNT) {
            return true
        }

        return isNearDecisionPoint(
            attached = attached,
            projection = projection,
        ) &&
            hasWrongDirection(projection = projection, location = location) &&
            candidate.sampleCount >= OFF_ROUTE_DECISION_POINT_CONFIRMATION_SAMPLE_COUNT
    }

    /**
     * off-route candidate とみなす横ズレ閾値を返す。
     *
     * @param location 今回 tick の生 GPS
     * @return 表示を実位置側へ戻し始める projection error 閾値
     */
    private fun offRouteCandidateThreshold(location: UserLocation): Double = max(
        MIN_OFF_ROUTE_CANDIDATE_ERROR_METRES,
        location.usableAccuracyMeters() * OFF_ROUTE_CANDIDATE_ACCURACY_MULTIPLIER,
    )

    /**
     * off-route confirmed とみなす横ズレ閾値を返す。
     *
     * @param location 今回 tick の生 GPS
     * @return リルート対象として確定する projection error 閾値
     */
    private fun offRouteConfirmedThreshold(location: UserLocation): Double = max(
        MIN_OFF_ROUTE_CONFIRMED_ERROR_METRES,
        location.usableAccuracyMeters() * OFF_ROUTE_CONFIRMED_ACCURACY_MULTIPLIER,
    )

    /**
     * off-route 判定に使うには精度値が粗すぎるかを返す。
     *
     * @return 精度値が有効かつ上限を超える場合 true
     */
    private fun UserLocation.hasTooCoarseAccuracyForOffRoute(): Boolean =
        accuracyMeters.isFinite() && accuracyMeters > MAX_OFF_ROUTE_ACCURACY_METRES

    /**
     * off-route 判定に使うには速度が遅すぎるかを返す。
     *
     * speed が取れない provider でも Fake GPS / 一部端末で逸脱検知できるよう、無効値や null は
     * 速度 gate では落とさない。
     *
     * @return 有効な速度が下限を下回る場合 true
     */
    private fun UserLocation.hasTooSlowSpeedForOffRoute(): Boolean =
        speedMps?.takeIf { speed -> speed.isFinite() }?.let { speed -> speed < MIN_OFF_ROUTE_SPEED_MPS } == true

    /**
     * off-route 閾値計算に使う水平精度を返す。
     *
     * @return 有効な水平精度。無効値の場合は保守的な既定値
     */
    private fun UserLocation.usableAccuracyMeters(): Double =
        accuracyMeters
            .takeIf { accuracy -> accuracy.isFinite() && accuracy >= 0f }
            ?.toDouble()
            ?: DEFAULT_OFF_ROUTE_ACCURACY_METRES

    /**
     * 現在 projection が案内判断点の近くにあるかを返す。
     *
     * @param attached attach 済み route 情報
     * @param projection 今回 tick の projection
     * @return 実際の案内判断点の近傍なら true
     */
    private fun isNearDecisionPoint(
        attached: AttachedRoute,
        projection: RouteProjection,
    ): Boolean = attached.primaryEventMetres.any { metres ->
        abs(metres - projection.currentCumulativeMeters) <= DECISION_POINT_RADIUS_METRES
    }

    /**
     * GPS bearing と route segment bearing が大きく異なるかを返す。
     *
     * @param projection 今回 tick の projection
     * @param location 今回 tick の生 GPS
     * @return 方位差から誤進行方向とみなせる場合 true
     */
    private fun hasWrongDirection(
        projection: RouteProjection,
        location: UserLocation,
    ): Boolean {
        val bearingDegrees = location.bearingDegrees ?: return false
        val bearingDiffDegrees = abs(normalizeDegrees(bearingDegrees - projection.segmentBearingDegrees))

        return bearingDiffDegrees >= OFF_ROUTE_DECISION_POINT_BEARING_DIFF_DEGREES
    }

    /**
     * 今回 tick の進行方位が route 方向とほぼ真逆かを返す。
     *
     * 交差点に依存しない一般的な逆走判定。route polyline 上を逆向きに走ると横ズレが小さいまま
     * 投影先 segment が手前に固定され、その segment の進行方位と車両方位がほぼ真逆になる。
     * GPS 方位が無い provider (一部端末 / Fake GPS) では直前位置からの移動方位で代替する。
     *
     * @param projection 今回 tick の projection
     * @param vehicleHeadingDegrees 解決済みの車両進行方位。求められなければ null
     * @return 方位差が逆走閾値以上なら true
     */
    private fun isWrongDirectionTick(
        projection: RouteProjection,
        vehicleHeadingDegrees: Float?,
    ): Boolean {
        val headingDegrees = vehicleHeadingDegrees ?: return false
        val bearingDiffDegrees = abs(normalizeDegrees(headingDegrees - projection.segmentBearingDegrees))

        return bearingDiffDegrees >= WRONG_DIRECTION_BEARING_DIFF_DEGREES
    }

    /**
     * 車両の進行方位を求める。
     *
     * GPS 方位が有効ならそれを使い、無ければ直前 tick の位置からの移動方位で代替する。移動量が
     * 小さいと方位が不安定になるため、一定距離未満の移動では方位を確定しない。
     *
     * @param location 今回 tick の生 GPS
     * @return 進行方位。求められなければ null
     */
    private fun resolveVehicleHeading(location: UserLocation): Float? {
        val gpsBearingDegrees = location.bearingDegrees
        if (gpsBearingDegrees != null && gpsBearingDegrees.isFinite()) return gpsBearingDegrees

        val previousPoint = previousRawPoint ?: return null
        val currentPoint = location.toRoutePoint()
        if (haversineMetres(previousPoint, currentPoint) < MIN_HEADING_MOVE_METRES) return null

        return bearingDegrees(previousPoint, currentPoint)
    }

    /**
     * off-route 候補の継続状態を破棄する。
     */
    private fun clearOffRouteCandidate() {
        offRouteCandidate = null
    }

    /**
     * matched segment index から現在走行中の道路種別を求める。
     *
     * @param route roadClassSegments を持つ route
     * @param matchedSegmentIndex snap 先 segment index
     * @return 該当する道路種別。見つからない場合は一般道
     */
    private fun currentRoadClassFor(
        route: RouteDetail,
        matchedSegmentIndex: Int,
    ): RoadClass = route.roadClassSegments
        .firstOrNull { segment ->
            matchedSegmentIndex >= segment.startPointIndex && matchedSegmentIndex < segment.endPointIndex
        }
        ?.roadClass
        ?: RoadClass.ORDINARY

    /**
     * 現在地までに通過済みのイベントのうち、最も手前で道路名を持つものから現在の道路名を求める。
     *
     * 道路名は IC / JCT / 交差点付近のイベントにしか付かないため近似だが、案内ラベルの
     * フォールバック (交差点名 / 看板が無いときの走行中道路名) には十分。該当が無ければ null。
     *
     * @param attached 案内ルートを持つ attach 済み情報
     * @param currentCumulativeMeters 現在地の geometry 累積距離
     * @return 現在走行中の道路名。求められなければ null
     */
    private fun currentRoadNameFor(
        attached: AttachedRoute,
        currentCumulativeMeters: Double,
    ): String? {
        var bestMetres = Double.NEGATIVE_INFINITY
        var bestName: String? = null
        for (event in attached.guidanceRoute.events) {
            val roadName = event.details.roadName?.text ?: continue
            val metres = event.anchor.geometryDistanceFromStartMeters
            if (metres > currentCumulativeMeters) continue
            if (metres < bestMetres) continue
            bestMetres = metres
            bestName = roadName
        }
        return bestName
    }

    // ---------------------------------------------------------------------
    // Small math helpers
    // ---------------------------------------------------------------------

    /**
     * [UserLocation] を geometry 計算で使う [RoutePoint] に変換する。
     *
     * @return 緯度経度だけを持つ route point
     */
    private fun UserLocation.toRoutePoint(): RoutePoint = RoutePoint(
        latitude = latitude,
        longitude = longitude,
    )

    /**
     * 指定緯度における緯度経度 1 度あたりのメートル換算係数を返す。
     *
     * @param latitude 計算対象緯度
     * @return 平面近似用の meter scale
     */
    private fun meterScaleAt(latitude: Double): MeterScale {
        val latitudeRadians = Math.toRadians(latitude)
        return MeterScale(
            latitudeMetresPerDegree = METRES_PER_DEGREE,
            longitudeMetresPerDegree = METRES_PER_DEGREE * cos(latitudeRadians),
        )
    }

    /**
     * 2 点間の球面距離を haversine で計算する。
     *
     * @param from 始点
     * @param to 終点
     * @return 2 点間距離メートル
     */
    private fun haversineMetres(from: RoutePoint, to: RoutePoint): Double =
        RouteGeometryMath.haversineMetres(from, to)

    /**
     * 2 点を結ぶ進行方位を計算する。
     *
     * @param from 始点
     * @param to 終点
     * @return 0 度以上 360 度未満の方位角
     */
    private fun bearingDegrees(from: RoutePoint, to: RoutePoint): Float =
        RouteGeometryMath.bearingDegrees(from, to)

    /**
     * 方位差を -180 度から 180 度の範囲へ正規化する。
     *
     * @param degrees 正規化前の角度
     * @return 正規化後の角度
     */
    private fun normalizeDegrees(degrees: Float): Float =
        RouteGeometryMath.normalizeDegrees(degrees)

    /**
     * DR を開始または継続できるトンネル区間を返す。
     *
     * @param currentCumulativeMeters 現在の route geometry 累積距離
     * @param speedMps DR に使う速度
     * @return 到達可能なトンネル区間。無ければ null
     */
    private fun TunnelMapStatus.findReachableSegment(
        currentCumulativeMeters: Double,
        speedMps: Float,
    ): ExtNavTunnelSegment? {
        val ready = this as? TunnelMapStatus.Ready ?: return null
        val entryGateMeters = speedMps * LOST_SIGNAL_THRESHOLD_SECONDS +
            TUNNEL_BOUNDARY_ERROR_METRES +
            TUNNEL_ENTRY_TICK_MARGIN_METRES

        return ready.segments.firstOrNull { segment ->
            val isInside = segment.contains(currentCumulativeMeters)
            val isBeforeEntry = currentCumulativeMeters < segment.startGeometryMeters
            val distanceToEntryMeters = segment.startGeometryMeters - currentCumulativeMeters
            val isWithinEntryGate = distanceToEntryMeters <= entryGateMeters
            val canEnterFromBefore = isBeforeEntry && isWithinEntryGate

            isInside || canEnterFromBefore
        }
    }

    /**
     * 実測 snapshot から次回 DR の初期値を作る。
     *
     * @param location 実測 tick
     * @return DR 初期値。速度が無効な場合は null
     */
    private fun ExtNavProgressSnapshot.deadReckoningSeed(location: UserLocation): DeadReckoningState? {
        val speedMps = location.speedMps?.takeIf { speed -> speed.isFinite() } ?: return null
        val elapsedRealtimeNanos = location.elapsedRealtimeNanos ?: return null

        return DeadReckoningState(
            speedMps = speedMps,
            startCumulativeMeters = currentCumulativeMeters,
            currentCumulativeMeters = currentCumulativeMeters,
            startElapsedRealtimeNanos = elapsedRealtimeNanos,
            lastElapsedRealtimeNanos = elapsedRealtimeNanos,
        )
    }

    /**
     * attach 中の route で tick 時に再利用する事前計算済みデータ。
     *
     * @param route 案内対象の中立 route
     * @param cumulativeMetres geometry index ごとの累積距離
     * @param totalGeometryMetres geometry の総距離
     * @param guidanceRoute payload を射影した位置非依存の案内ルート
     * @param distanceContext source→geometry 距離変換 context
     * @param projectionContext 道路種別 / ETA を解決する geometry コンテキスト
     * @param primaryEventMetres 主案内イベントの geometry 距離一覧 (off-route 判定用)
     * @param speedLimitSegments geometry 距離基準へ変換済みの制限速度区間
     * @param tunnelMapStatus route geometry 距離基準のトンネル区間状態
     */
    private class AttachedRoute(
        val route: RouteDetail,
        val cumulativeMetres: DoubleArray,
        val totalGeometryMetres: Double,
        val guidanceRoute: GuidanceRoute,
        val distanceContext: ExtNavRouteDistanceContext,
        val projectionContext: RouteProjectionContext,
        val primaryEventMetres: List<Double>,
        val speedLimitSegments: List<SpeedLimitGeometrySegment>,
        val tunnelMapStatus: TunnelMapStatus,
    )

    /**
     * DR 中に使う実測由来の保持値。
     *
     * @param speedMps DR に使う速度
     * @param startCumulativeMeters DR 開始時の route geometry 累積距離
     * @param currentCumulativeMeters 現在の DR route geometry 累積距離
     * @param startElapsedRealtimeNanos DR 開始時の monotonic clock
     * @param lastElapsedRealtimeNanos 前回 DR tick の monotonic clock
     */
    private data class DeadReckoningState(
        val speedMps: Float,
        val startCumulativeMeters: Double,
        val currentCumulativeMeters: Double,
        val startElapsedRealtimeNanos: Long,
        val lastElapsedRealtimeNanos: Long,
    )

    /**
     * geometry 距離基準に変換済みの制限速度区間。
     *
     * @param startGeometryMetres 区間開始の geometry 累積距離
     * @param endGeometryMetres 区間終了の geometry 累積距離
     * @param limitKmh 区間の制限速度 (km/h)
     */
    private data class SpeedLimitGeometrySegment(
        val startGeometryMetres: Double,
        val endGeometryMetres: Double,
        val limitKmh: Int,
    )

    /**
     * 1 tick の GPS を route geometry へ投影した結果。
     *
     * @param snappedLocation geometry 上に snap した位置
     * @param currentCumulativeMeters snappedLocation の geometry 累積距離
     * @param matchedSegmentIndex snap 先 segment index
     * @param projectionErrorMeters 生 GPS と snappedLocation の距離
     * @param segmentBearingDegrees snap 先 segment の進行方位
     */
    private data class RouteProjection(
        val snappedLocation: RoutePoint,
        val currentCumulativeMeters: Double,
        val matchedSegmentIndex: Int,
        val projectionErrorMeters: Double,
        val segmentBearingDegrees: Float,
    )

    /**
     * projection 時に探索する segment index 範囲。
     *
     * @param startIndex 探索開始 segment index
     * @param endIndex 探索終了 segment index
     */
    private data class ProjectionSearchWindow(
        val startIndex: Int,
        val endIndex: Int,
    )

    /**
     * off-route 候補が継続している期間を保持する。
     *
     * @param firstTimestampMillis 候補が始まった tick の時刻
     * @param latestTimestampMillis 直近 candidate tick の時刻
     * @param sampleCount candidate tick の連続数
     */
    private data class OffRouteCandidate(
        val firstTimestampMillis: Long,
        val latestTimestampMillis: Long,
        val sampleCount: Int,
    ) {

        /** 候補が継続している時間（ms）。 */
        val durationMillis: Long
            get() = latestTimestampMillis - firstTimestampMillis

        /**
         * 次の candidate tick を反映した状態を返す。
         *
         * @param timestampMillis 新しい tick の時刻
         * @return tick 数と最終時刻を更新した candidate
         */
        fun advance(timestampMillis: Long): OffRouteCandidate = copy(
            latestTimestampMillis = timestampMillis,
            sampleCount = sampleCount + 1,
        )
    }

    /**
     * 緯度経度差分を平面メートル座標へ近似変換する係数。
     *
     * @param latitudeMetresPerDegree 緯度 1 度あたりのメートル
     * @param longitudeMetresPerDegree 経度 1 度あたりのメートル
     */
    private data class MeterScale(
        val latitudeMetresPerDegree: Double,
        val longitudeMetresPerDegree: Double,
    )

    /** Tracker 内の数値閾値定義。 */
    private companion object {
        /** 緯度 1 度をメートルへ近似変換する係数。 */
        private const val METRES_PER_DEGREE: Double = 111_320.0

        /** 秒からミリ秒へ変換する係数。 */
        private const val MILLIS_PER_SECOND: Long = 1_000L

        /** 秒からナノ秒へ変換する係数。 */
        private const val NANOS_PER_SECOND: Long = 1_000_000_000L

        /** 前回 projection 以降に探索する最大 segment 数。 */
        private const val MAX_SEGMENT_LOOKAHEAD: Int = 300

        /** GPS jitter とみなして前回 projection を維持する最大後退距離。 */
        private const val BACKWARD_HYSTERESIS_METRES: Double = 5.0

        /** 目的地直前で off-route 候補を抑制する残距離。 */
        private const val ARRIVAL_SUPPRESSION_METRES: Double = 100.0

        /** off-route 判定を許可する最大 GPS 精度値。 */
        private const val MAX_OFF_ROUTE_ACCURACY_METRES: Float = 50f

        /** accuracy が無効値の場合に off-route 閾値計算へ使う既定精度。 */
        private const val DEFAULT_OFF_ROUTE_ACCURACY_METRES: Double = 10.0

        /** off-route 判定を許可する最小速度。 */
        private const val MIN_OFF_ROUTE_SPEED_MPS: Float = 1.5f

        /** off-route candidate とみなす最小 projection error。 */
        private const val MIN_OFF_ROUTE_CANDIDATE_ERROR_METRES: Double = 25.0

        /** GPS 精度から candidate 閾値を増やす倍率。 */
        private const val OFF_ROUTE_CANDIDATE_ACCURACY_MULTIPLIER: Double = 2.0

        /** off-route confirmed とみなす最小 projection error。 */
        private const val MIN_OFF_ROUTE_CONFIRMED_ERROR_METRES: Double = 40.0

        /** GPS 精度から confirmed 閾値を増やす倍率。 */
        private const val OFF_ROUTE_CONFIRMED_ACCURACY_MULTIPLIER: Double = 2.5

        /** confirmed 判定に必要な off-route candidate の連続 tick 数。 */
        private const val OFF_ROUTE_CONFIRMATION_SAMPLE_COUNT: Int = 2

        /** confirmed 判定で時間条件を許可する最小 tick 数。 */
        private const val OFF_ROUTE_MIN_DURATION_SAMPLE_COUNT: Int = 2

        /** confirmed 判定に必要な off-route candidate 継続時間。 */
        private const val OFF_ROUTE_CONFIRMATION_DURATION_MILLIS: Long = 1_500L

        /** 案内判断点付近で confirmed 判定に必要な連続 tick 数。 */
        private const val OFF_ROUTE_DECISION_POINT_CONFIRMATION_SAMPLE_COUNT: Int = 2

        /** 案内地点・交差点付近として扱う route 上距離。 */
        private const val DECISION_POINT_RADIUS_METRES: Double = 40.0

        /** 案内判断点付近で使う GPS bearing と segment bearing の最小差分。 */
        private const val OFF_ROUTE_DECISION_POINT_BEARING_DIFF_DEGREES: Float = 45f

        /** 交差点に依存せず逆走とみなす進行方位と segment 方位の最小差分。 */
        private const val WRONG_DIRECTION_BEARING_DIFF_DEGREES: Float = 120f

        /** 逆走で confirmed 判定に必要な連続 tick 数。 */
        private const val WRONG_DIRECTION_CONFIRMATION_SAMPLE_COUNT: Int = 3

        /** GPS 方位が無いときに移動方位を確定できる最小移動距離。 */
        private const val MIN_HEADING_MOVE_METRES: Double = 3.0

        /** DR を開始できる最小速度。 */
        private const val MIN_DEAD_RECKONING_SPEED_MPS: Float = 2.2f

        /** DR 途絶判定に使う秒数。 */
        private const val LOST_SIGNAL_THRESHOLD_SECONDS: Double = 3.0

        /** トンネル境界誤差として入口 gate に足す距離。 */
        private const val TUNNEL_BOUNDARY_ERROR_METRES: Double = 20.0

        /** 入口判定で tick 遅延ぶんとして足す距離。 */
        private const val TUNNEL_ENTRY_TICK_MARGIN_METRES: Double = 10.0

        /** DR を継続できる最大秒数。 */
        private const val MAX_DEAD_RECKONING_DURATION_SECONDS: Double = 120.0

        /** DR を継続できる最大距離。 */
        private const val MAX_DEAD_RECKONING_DISTANCE_METRES: Double = 3_000.0

        /** 表示対象にする最小制限速度。 */
        private const val MIN_SPEED_LIMIT_KMH: Int = 20

        /** 表示対象にする最大制限速度。 */
        private const val MAX_SPEED_LIMIT_KMH: Int = 120
    }
}
