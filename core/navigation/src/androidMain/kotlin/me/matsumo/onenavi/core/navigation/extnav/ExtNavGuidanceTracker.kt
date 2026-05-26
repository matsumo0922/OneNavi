package me.matsumo.onenavi.core.navigation.extnav

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.matsumo.drive.supporter.api.guidance.domain.GuidanceCategory
import me.matsumo.onenavi.core.datasource.location.UserLocation
import me.matsumo.onenavi.core.model.ManeuverModifier
import me.matsumo.onenavi.core.model.ManeuverType
import me.matsumo.onenavi.core.model.RoadClass
import me.matsumo.onenavi.core.model.RouteDetail
import me.matsumo.onenavi.core.model.RoutePoint
import me.matsumo.onenavi.core.navigation.newguidance.model.EntrancePanelSubtitle
import me.matsumo.onenavi.core.navigation.newguidance.model.ExitPanelSubtitle
import me.matsumo.onenavi.core.navigation.newguidance.model.FacilityPanelItem
import me.matsumo.onenavi.core.navigation.newguidance.model.GuidanceManeuverInfo
import me.matsumo.onenavi.core.navigation.newguidance.model.GuidancePanelFacility
import me.matsumo.onenavi.core.navigation.newguidance.model.GuidancePanelItem
import me.matsumo.onenavi.core.navigation.newguidance.model.GuidancePanelSubtitle
import me.matsumo.onenavi.core.navigation.newguidance.model.GuidanceProgress
import me.matsumo.onenavi.core.navigation.newguidance.model.GuidanceTextPanelSubtitle
import me.matsumo.onenavi.core.navigation.newguidance.model.Lane
import me.matsumo.onenavi.core.navigation.newguidance.model.LaneGuidance
import me.matsumo.onenavi.core.navigation.newguidance.model.ManeuverPanelItem
import me.matsumo.onenavi.core.navigation.newguidance.model.RecommendedLanesPanelSubtitle
import me.matsumo.onenavi.core.navigation.newguidance.model.RouteMatchState
import me.matsumo.onenavi.core.navigation.newguidance.model.TollPanelSubtitle
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import me.matsumo.drive.supporter.api.guidance.domain.GuidanceFacilityKind as ExtNavGuidanceFacilityKind
import me.matsumo.drive.supporter.api.guidance.domain.GuidancePoint as ExtNavGuidancePoint
import me.matsumo.drive.supporter.api.guidance.domain.Intersection as ExtNavIntersection
import me.matsumo.drive.supporter.api.guidance.domain.LaneInfo as ExtNavLaneInfo
import me.matsumo.drive.supporter.api.guidance.domain.ManeuverDirection as ExtNavManeuverDirection

/**
 * GPS tick をルート geometry 上の進捗 snapshot に変換する tracker。
 *
 * このクラスの責務は「現在地を route geometry に投影して [ExtNavProgressSnapshot] を更新する」
 * ところまで。リルート確定、音声発話、再探索、ネットワーク I/O は持たない。
 */
@Suppress("unused")
class ExtNavGuidanceTracker {

    private val _snapshot = MutableStateFlow<ExtNavProgressSnapshot?>(null)

    /** 現在の projection snapshot。未 attach または detach 後は null。 */
    val snapshot: StateFlow<ExtNavProgressSnapshot?> = _snapshot.asStateFlow()

    private var attachedRoute: AttachedRoute? = null
    private var lastProjection: RouteProjection? = null
    private var offRouteCandidate: OffRouteCandidate? = null

    /**
     * 案内対象の route と、Preview 時にキャッシュした外部ナビ API ライブラリ由来 payload を紐付ける。
     *
     * tick ごとの計算を軽くするため、ここで geometry の累積距離、GP の geometry 距離、
     * intersection の geometry 距離を事前計算する。
     */
    @Suppress("unused")
    fun attach(payload: ExtNavRoutePayload, route: RouteDetail) {
        val attached = buildAttachedRoute(payload, route)
        attachedRoute = attached
        lastProjection = null
        offRouteCandidate = null
        _snapshot.value = null
    }

    /**
     * GPS 位置を 1 tick 投入し、ルート上の snappedLocation / 残距離 / 次 GP などを更新する。
     */
    @Suppress("unused")
    fun onLocation(location: UserLocation) {
        val attached = attachedRoute ?: return
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
        _snapshot.value = snapshot
    }

    /** attach 済み route、前回 projection、公開 snapshot を破棄する。 */
    @Suppress("unused")
    fun detach() {
        attachedRoute = null
        lastProjection = null
        offRouteCandidate = null
        _snapshot.value = null
    }

    // ---------------------------------------------------------------------
    // Attach-time preparation
    // ---------------------------------------------------------------------

    /**
     * route ごとの immutable な事前計算結果を作る。
     *
     * @param payload Preview 時に取得した外部ナビ API ライブラリ由来 payload
     * @param route 案内対象の中立 route
     * @return tick 時に参照する attach 済み route 情報
     */
    private fun buildAttachedRoute(
        payload: ExtNavRoutePayload,
        route: RouteDetail,
    ): AttachedRoute {
        val cumulativeMetres = buildCumulativeGeometryMetres(route.geometry)
        val totalGeometryMetres = cumulativeMetres.lastOrNull() ?: 0.0
        val distanceMapper = buildDistanceMapper(
            payload = payload,
            route = route,
            totalGeometryMetres = totalGeometryMetres,
        )
        val guidancePointMetres = buildGuidancePointMetres(
            payload = payload,
            distanceMapper = distanceMapper,
            totalGeometryMetres = totalGeometryMetres,
        )
        val intersections = buildIntersectionAnchors(
            payload = payload,
            route = route,
            cumulativeMetres = cumulativeMetres,
        )
        val maneuverEvents = buildManeuverEvents(
            payload = payload,
            route = route,
            cumulativeMetres = cumulativeMetres,
            guidancePointMetres = guidancePointMetres,
            intersections = intersections,
        )
        val laneAnchors = buildLaneAnchors(
            payload = payload,
            route = route,
            cumulativeMetres = cumulativeMetres,
            guidancePointMetres = guidancePointMetres,
        )

        return AttachedRoute(
            route = route,
            cumulativeMetres = cumulativeMetres,
            totalGeometryMetres = totalGeometryMetres,
            maneuverEvents = maneuverEvents,
            laneAnchors = laneAnchors,
            panelEvents = buildPanelEvents(
                route = route,
                cumulativeMetres = cumulativeMetres,
                totalGeometryMetres = totalGeometryMetres,
                maneuverEvents = maneuverEvents,
                intersections = intersections,
                laneAnchors = laneAnchors,
            ),
        )
    }

    /**
     * 外部 API の summary 距離基準を OneNavi geometry 距離基準へ変換する mapper を作る。
     *
     * @param payload route guidance を含む payload
     * @param route fallback の総距離を持つ中立 route
     * @param totalGeometryMetres OneNavi geometry を haversine で積算した総距離
     * @return 始点 / 終点 anchor を持つ距離 mapper
     */
    private fun buildDistanceMapper(
        payload: ExtNavRoutePayload,
        route: RouteDetail,
        totalGeometryMetres: Double,
    ): RouteDistanceMapper {
        val sourceTotalMetres = sourceTotalMetres(
            payload = payload,
            route = route,
            totalGeometryMetres = totalGeometryMetres,
        )

        return RouteDistanceMapper(
            anchors = listOf(
                DistanceAnchor(sourceMetres = 0.0, geometryMetres = 0.0),
                DistanceAnchor(sourceMetres = sourceTotalMetres, geometryMetres = totalGeometryMetres),
            ),
        )
    }

    /**
     * GP 距離の source 側総距離を決める。
     *
     * @param payload summary 距離の第一候補
     * @param route route 詳細距離の第二候補
     * @param totalGeometryMetres geometry 積算距離の fallback
     * @return source 座標系の総距離
     */
    private fun sourceTotalMetres(
        payload: ExtNavRoutePayload,
        route: RouteDetail,
        totalGeometryMetres: Double,
    ): Double = payload.routeGuidance.summary.distanceMetres
        .toDouble()
        .takeIf { metres -> metres > 0.0 }
        ?: route.distanceMeters
            .takeIf { metres -> metres > 0.0 }
        ?: totalGeometryMetres

    /**
     * 各 GP の source 距離を geometry 上の累積距離へ射影する。
     *
     * @param payload guidancePoints を含む payload
     * @param distanceMapper source 距離から geometry 距離への mapper
     * @param totalGeometryMetres geometry 上の最大距離
     * @return GP index と同じ順序の geometry 累積距離配列
     */
    private fun buildGuidancePointMetres(
        payload: ExtNavRoutePayload,
        distanceMapper: RouteDistanceMapper,
        totalGeometryMetres: Double,
    ): DoubleArray = payload.routeGuidance.guidancePoints
        .map { guidancePoint ->
            distanceMapper
                .mapSourceToGeometry(guidancePoint.distanceFromStartMetres.toDouble())
                .coerceIn(0.0, totalGeometryMetres)
        }
        .toDoubleArray()

    /**
     * route geometry の各点までの累積距離を作る。
     *
     * @param geometry route polyline
     * @return geometry index と同じ順序の累積距離配列
     */
    private fun buildCumulativeGeometryMetres(geometry: List<RoutePoint>): DoubleArray =
        RouteGeometryMath.cumulativeMetres(geometry)

    /**
     * intersection の座標を最寄り geometry 点に snap し、距離 anchor として保持する。
     *
     * @param payload intersections を含む payload
     * @param route snap 対象の geometry を持つ route
     * @param cumulativeMetres geometry index ごとの累積距離
     * @return geometry 距離順の intersection anchor
     */
    private fun buildIntersectionAnchors(
        payload: ExtNavRoutePayload,
        route: RouteDetail,
        cumulativeMetres: DoubleArray,
    ): List<TrackerIntersection> {
        if (route.geometry.isEmpty() || cumulativeMetres.isEmpty()) return emptyList()

        return payload.routeGuidance.intersections
            .map { intersection ->
                val geometryIndex = nearestGeometryIndex(
                    geometry = route.geometry,
                    point = RoutePoint(
                        latitude = intersection.position.latDegrees,
                        longitude = intersection.position.lonDegrees,
                    ),
                )

                TrackerIntersection(
                    geometryMetres = cumulativeMetres[geometryIndex],
                    name = intersection.name,
                    guidanceText = intersection.panelGuidanceText(),
                    facility = intersection.panelFacility(),
                )
            }
            .sortedBy { intersection -> intersection.geometryMetres }
    }

    /**
     * GP 列から TBT とカメラフォーカスの対象にする進路選択イベントだけを抽出する。
     *
     * @param payload 外部ナビ API ライブラリ由来 payload
     * @param route 案内対象 route
     * @param cumulativeMetres geometry index ごとの累積距離
     * @param guidancePointMetres GP index ごとの geometry 累積距離
     * @param intersections geometry 距離に snap 済みの intersection anchors
     * @return 進路選択を伴うイベント列
     */
    private fun buildManeuverEvents(
        payload: ExtNavRoutePayload,
        route: RouteDetail,
        cumulativeMetres: DoubleArray,
        guidancePointMetres: DoubleArray,
        intersections: List<TrackerIntersection>,
    ): List<TrackerManeuverEvent> = payload.routeGuidance.guidancePoints
        .mapIndexedNotNull { guidancePointIndex, guidancePoint ->
            val guidancePointMetresOnGeometry = guidancePointMetres.getOrNull(guidancePointIndex)
                ?: return@mapIndexedNotNull null
            val bearingDiffDegrees = bearingDiffAt(
                route = route,
                cumulativeMetres = cumulativeMetres,
                targetMetres = guidancePointMetresOnGeometry,
            )
            val nearestIntersection = nearestIntersectionToGuidancePoint(
                intersections = intersections,
                guidancePointMetres = guidancePointMetresOnGeometry,
            )
            val facility = guidancePoint.panelFacility(nearestIntersection)
            val isLastGuidancePoint = guidancePointIndex == payload.routeGuidance.guidancePoints.lastIndex
            val modifier = maneuverModifier(bearingDiffDegrees)

            if (!guidancePoint.requiresManeuverEvent(
                    isLastGuidancePoint = isLastGuidancePoint,
                    bearingDiffDegrees = bearingDiffDegrees,
                    facility = facility,
                )
            ) {
                return@mapIndexedNotNull null
            }

            TrackerManeuverEvent(
                id = "maneuver-$guidancePointIndex",
                guidancePointIndex = guidancePointIndex,
                geometryMetres = guidancePointMetresOnGeometry,
                location = routePointAt(
                    route = route,
                    cumulativeMetres = cumulativeMetres,
                    targetMetres = guidancePointMetresOnGeometry,
                ),
                type = maneuverType(
                    categories = guidancePoint.categories(),
                    isLastGuidancePoint = isLastGuidancePoint,
                    bearingDiffDegrees = bearingDiffDegrees,
                ),
                modifier = modifier,
                intersectionName = nearestIntersection
                    ?.name
                    ?.takeIf { name -> name.isNotBlank() },
                exitNumber = null,
                roadClass = roadClassAt(
                    route = route,
                    cumulativeMetres = cumulativeMetres,
                    targetMetres = guidancePointMetresOnGeometry,
                ),
                facility = facility,
                subtitle = guidancePoint.panelManeuverSubtitle(
                    route = route,
                    cumulativeMetres = cumulativeMetres,
                    geometryMetres = guidancePointMetresOnGeometry,
                    nearestIntersection = nearestIntersection,
                ),
                laneGuidance = guidancePoint.maneuver
                    ?.laneInfo
                    ?.toLaneGuidance(modifier = modifier),
            )
        }
        .sortedBy { event -> event.geometryMetres }

    /**
     * レーン情報を持つ GP を geometry 距離付き anchor に変換する。
     *
     * @param payload 外部ナビ API ライブラリ由来 payload
     * @param route 案内対象 route
     * @param cumulativeMetres geometry index ごとの累積距離
     * @param guidancePointMetres GP index ごとの geometry 累積距離
     * @return geometry 距離順のレーン anchor
     */
    private fun buildLaneAnchors(
        payload: ExtNavRoutePayload,
        route: RouteDetail,
        cumulativeMetres: DoubleArray,
        guidancePointMetres: DoubleArray,
    ): List<TrackerLaneAnchor> = payload.routeGuidance.guidancePoints
        .mapIndexedNotNull { guidancePointIndex, guidancePoint ->
            val laneInfo = guidancePoint.maneuver?.laneInfo ?: return@mapIndexedNotNull null
            val geometryMetres = guidancePointMetres.getOrNull(guidancePointIndex)
                ?: return@mapIndexedNotNull null
            val bearingDiffDegrees = bearingDiffAt(
                route = route,
                cumulativeMetres = cumulativeMetres,
                targetMetres = geometryMetres,
            )
            val laneGuidance = laneInfo
                .toLaneGuidance(modifier = maneuverModifier(bearingDiffDegrees))
                ?: return@mapIndexedNotNull null

            TrackerLaneAnchor(
                geometryMetres = geometryMetres,
                laneGuidance = laneGuidance,
            )
        }
        .sortedBy { anchor -> anchor.geometryMetres }

    /**
     * パネルに出す進路選択イベントと通過施設イベントを 1 本の時系列にまとめる。
     *
     * @param route 案内対象 route
     * @param cumulativeMetres geometry index ごとの累積距離
     * @param totalGeometryMetres geometry 上の最大距離
     * @param maneuverEvents 進路選択イベント列
     * @param intersections geometry 距離に snap 済みの intersection anchors
     * @param laneAnchors レーン情報を持つ GP の anchor
     * @return パネル行テンプレート列
     */
    private fun buildPanelEvents(
        route: RouteDetail,
        cumulativeMetres: DoubleArray,
        totalGeometryMetres: Double,
        maneuverEvents: List<TrackerManeuverEvent>,
        intersections: List<TrackerIntersection>,
        laneAnchors: List<TrackerLaneAnchor>,
    ): List<TrackerPanelEvent> {
        val maneuverPanelEvents = maneuverEvents
            .filterNot { event -> event.type == ManeuverType.ARRIVE }
            .map { event -> TrackerPanelEvent.Maneuver(event = event) }
        val intersectionFacilityEvents = intersections
            .mapIndexedNotNull { intersectionIndex, intersection ->
                val facility = intersection.facility ?: return@mapIndexedNotNull null
                val geometryMetres = intersection.geometryMetres

                val location = routePointAt(
                    route = route,
                    cumulativeMetres = cumulativeMetres,
                    targetMetres = geometryMetres.coerceIn(0.0, totalGeometryMetres),
                )
                TrackerPanelEvent.Facility(
                    id = "facility-$intersectionIndex-${facility.name}",
                    geometryMetres = geometryMetres,
                    location = location,
                    name = intersection.panelFacilityName(),
                    kind = facility,
                    roadClass = roadClassAt(
                        route = route,
                        cumulativeMetres = cumulativeMetres,
                        targetMetres = geometryMetres,
                    ),
                    subtitle = facility.panelFacilitySubtitle(
                        route = route,
                        cumulativeMetres = cumulativeMetres,
                        geometryMetres = geometryMetres,
                    ),
                    laneGuidance = nearestLaneAnchor(
                        laneAnchors = laneAnchors,
                        geometryMetres = geometryMetres,
                    )?.laneGuidance,
                )
            }
        return (maneuverPanelEvents + intersectionFacilityEvents)
            .sortedBy { event -> event.geometryMetres }
    }

    /**
     * 指定点に最も近い geometry 点の index を探す。
     *
     * @param geometry 探索対象 polyline
     * @param point 探索基準点
     * @return point に最も近い geometry index
     */
    private fun nearestGeometryIndex(
        geometry: List<RoutePoint>,
        point: RoutePoint,
    ): Int {
        var bestIndex = 0
        var bestDistanceMetres = Double.MAX_VALUE

        for ((index, routePoint) in geometry.withIndex()) {
            val distanceMetres = haversineMetres(routePoint, point)
            if (distanceMetres < bestDistanceMetres) {
                bestDistanceMetres = distanceMetres
                bestIndex = index
            }
        }
        return bestIndex
    }

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
        val nextManeuverEventIndex = nextManeuverEventIndex(
            attached = attached,
            projection = projection,
        )
        val offRouteCandidate = isOffRouteCandidate(
            projection = projection,
            location = location,
            attached = attached,
        )
        val routeMatchState = updateRouteMatchState(
            attached = attached,
            projection = projection,
            location = location,
            isOffRouteCandidate = offRouteCandidate,
        )
        val progress = buildProgress(
            attached = attached,
            projection = projection,
            location = location,
            distanceRemainingMetres = distanceRemainingMetres,
            nextManeuverEventIndex = nextManeuverEventIndex,
            routeMatchState = routeMatchState,
        )

        return ExtNavProgressSnapshot(
            progress = progress,
            rawLocation = location,
            currentCumulativeMeters = projection.currentCumulativeMeters,
            distanceRemainingMeters = distanceRemainingMetres,
            matchedSegmentIndex = projection.matchedSegmentIndex,
            projectionErrorMeters = projection.projectionErrorMeters,
            locationTimestampMillis = location.timestampMillis,
            vehicleSpeedMps = location.speedMps,
            routeMatchState = routeMatchState,
            isOffRouteCandidate = offRouteCandidate,
            nextGuidancePointIndex = nextManeuverEventIndex?.let { eventIndex ->
                attached.maneuverEvents[eventIndex].guidancePointIndex
            },
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
     * 現在地より先にある最初の maneuver event index を返す。
     *
     * @param attached maneuver event を持つ attach 済み情報
     * @param projection 現在 projection
     * @return 次 maneuver event index。最後の event 通過後や event なしの場合は null
     */
    private fun nextManeuverEventIndex(
        attached: AttachedRoute,
        projection: RouteProjection,
    ): Int? = attached.maneuverEvents
        .firstIndexGreaterThan(projection.currentCumulativeMeters + NEXT_GP_EPSILON_METRES)

    /**
     * UI が直接読む [GuidanceProgress] を組み立てる。
     *
     * @param attached attach 済み route 情報
     * @param projection 今回 tick の projection
     * @param location 今回 tick の生 GPS
     * @param distanceRemainingMetres 事前算出済みの残距離
     * @param nextManeuverEventIndex 次マニューバ event index
     * @param routeMatchState 現在位置と案内 route の一致状態
     * @return UI 表示用の案内進捗
     */
    private fun buildProgress(
        attached: AttachedRoute,
        projection: RouteProjection,
        location: UserLocation,
        distanceRemainingMetres: Double,
        nextManeuverEventIndex: Int?,
        routeMatchState: RouteMatchState,
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
            currentCumulativeMeters = projection.currentCumulativeMeters,
            snappedLocation = projection.snappedLocation,
            bearingDegrees = location.bearingDegrees ?: projection.segmentBearingDegrees,
            observedLocation = location.toRoutePoint(),
            observedBearingDegrees = location.bearingDegrees,
            observedAccuracyMeters = location.accuracyMeters,
            locationTimestampMillis = location.timestampMillis,
            locationElapsedRealtimeNanos = location.elapsedRealtimeNanos,
            vehicleSpeedMps = location.speedMps,
            nextManeuver = nextManeuverEventIndex?.let { eventIndex ->
                buildManeuverInfo(
                    event = attached.maneuverEvents[eventIndex],
                    currentCumulativeMeters = projection.currentCumulativeMeters,
                )
            },
            followupManeuver = buildFollowupManeuverInfo(
                attached = attached,
                nextManeuverEventIndex = nextManeuverEventIndex,
                currentCumulativeMeters = projection.currentCumulativeMeters,
            ),
            panelItems = buildPanelItems(
                attached = attached,
                currentCumulativeMeters = projection.currentCumulativeMeters,
                timestampMillis = location.timestampMillis,
            ),
            lanes = currentLaneGuidance(
                attached = attached,
                nextManeuverEventIndex = nextManeuverEventIndex,
                currentCumulativeMeters = projection.currentCumulativeMeters,
            ),
            directionSign = null,
            highwayPanel = null,
            currentRoadName = null,
            currentRoadClass = currentRoadClassFor(
                route = attached.route,
                matchedSegmentIndex = projection.matchedSegmentIndex,
            ),
            currentSpeedLimitKmh = null,
            routeMatchState = routeMatchState,
            projectionErrorMeters = projection.projectionErrorMeters,
        )
    }

    /**
     * 次 GP のさらに次にある follow-up maneuver を作る。
     *
     * @param attached attach 済み route 情報
     * @param nextManeuverEventIndex 次マニューバ event index
     * @param currentCumulativeMeters 現在地の geometry 累積距離
     * @return follow-up maneuver。存在しない場合は null
     */
    private fun buildFollowupManeuverInfo(
        attached: AttachedRoute,
        nextManeuverEventIndex: Int?,
        currentCumulativeMeters: Double,
    ): GuidanceManeuverInfo? = nextManeuverEventIndex
        ?.plus(1)
        ?.takeIf { eventIndex -> eventIndex <= attached.maneuverEvents.lastIndex }
        ?.let { eventIndex ->
            buildManeuverInfo(
                event = attached.maneuverEvents[eventIndex],
                currentCumulativeMeters = currentCumulativeMeters,
            )
        }

    /**
     * 暫定の maneuver 変換。
     *
     * 仕様上は ExtNavGuidanceMapper に移す予定だが、現時点では Tracker 内で
     * phrase category、近傍 intersection、geometry 方位差から最小限の UI モデルを作る。
     */
    private fun buildManeuverInfo(
        event: TrackerManeuverEvent,
        currentCumulativeMeters: Double,
    ): GuidanceManeuverInfo {
        return GuidanceManeuverInfo(
            type = event.type,
            modifier = event.modifier,
            location = event.location,
            distanceToManeuverMeters = (event.geometryMetres - currentCumulativeMeters)
                .coerceAtLeast(0.0)
                .roundToInt(),
            intersectionName = event.intersectionName,
            exitNumber = event.exitNumber,
            guidancePointIndex = event.guidancePointIndex,
        )
    }

    /**
     * GP の geometry 距離に最も近い intersection を探す。
     *
     * @param intersections geometry 距離に snap 済みの intersection anchors
     * @param guidancePointMetres GP の geometry 累積距離
     * @return 許容距離内で最も近い intersection。見つからない場合は null
     */
    private fun nearestIntersectionToGuidancePoint(
        intersections: List<TrackerIntersection>,
        guidancePointMetres: Double,
    ): TrackerIntersection? = intersections
        .filter { intersection ->
            abs(intersection.geometryMetres - guidancePointMetres) <= INTERSECTION_SNAP_TOLERANCE_METRES
        }
        .minByOrNull { intersection -> abs(intersection.geometryMetres - guidancePointMetres) }

    /**
     * 施設の geometry 距離に最も近いレーン anchor を探す。
     *
     * @param laneAnchors geometry 距離順のレーン anchor
     * @param geometryMetres 施設の geometry 累積距離
     * @return 許容距離内で最も近いレーン anchor。見つからない場合は null
     */
    private fun nearestLaneAnchor(
        laneAnchors: List<TrackerLaneAnchor>,
        geometryMetres: Double,
    ): TrackerLaneAnchor? = laneAnchors
        .filter { anchor ->
            abs(anchor.geometryMetres - geometryMetres) <= LANE_FACILITY_SNAP_TOLERANCE_METRES
        }
        .minByOrNull { anchor -> abs(anchor.geometryMetres - geometryMetres) }

    /**
     * 次マニューバが visibility 距離内に迫っていれば、そのレーンガイダンスを取り出す。
     *
     * @param attached attach 済み route 情報
     * @param nextManeuverEventIndex 次マニューバ event index
     * @param currentCumulativeMeters 現在地の geometry 累積距離
     * @return 表示対象のレーンガイダンス。対象が無い場合は空
     */
    private fun currentLaneGuidance(
        attached: AttachedRoute,
        nextManeuverEventIndex: Int?,
        currentCumulativeMeters: Double,
    ): ImmutableList<LaneGuidance> {
        val event = nextManeuverEventIndex
            ?.let { eventIndex -> attached.maneuverEvents.getOrNull(eventIndex) }
            ?: return persistentListOf()
        val laneGuidance = event.laneGuidance ?: return persistentListOf()
        val distanceMetres = event.geometryMetres - currentCumulativeMeters
        if (distanceMetres !in 0.0..LANE_GUIDANCE_VISIBILITY_METRES) return persistentListOf()

        return persistentListOf(laneGuidance)
    }

    /**
     * レーンガイダンスをパネル補助表示へ変換する。レーンが無ければ null。
     *
     * @param laneGuidance レーンガイダンス
     * @return 推奨レーン補助表示。無い場合は null
     */
    private fun laneSubtitleOrNull(laneGuidance: LaneGuidance?): GuidancePanelSubtitle? =
        laneGuidance?.let { guidance -> RecommendedLanesPanelSubtitle(lanes = guidance.lanes) }

    /**
     * 現在地より先にあるパネル行を距離・ETA 付きの公開モデルへ変換する。
     *
     * @param attached attach 済み route 情報
     * @param currentCumulativeMeters 現在地の geometry 累積距離
     * @param timestampMillis 位置 tick の時刻
     * @return 現在地より先にあるパネル行
     */
    private fun buildPanelItems(
        attached: AttachedRoute,
        currentCumulativeMeters: Double,
        timestampMillis: Long,
    ): ImmutableList<GuidancePanelItem> = attached.panelEvents
        .asSequence()
        .filter { event -> event.geometryMetres > currentCumulativeMeters + NEXT_GP_EPSILON_METRES }
        .sortedByDescending { event -> event.geometryMetres }
        .map { event ->
            val distanceToItemMeters = (event.geometryMetres - currentCumulativeMeters)
                .coerceAtLeast(0.0)
                .roundToInt()
            val etaEpochMillis = etaEpochMillisFor(
                route = attached.route,
                totalGeometryMetres = attached.totalGeometryMetres,
                currentCumulativeMeters = currentCumulativeMeters,
                targetMetres = event.geometryMetres,
                timestampMillis = timestampMillis,
            )

            when (event) {
                is TrackerPanelEvent.Facility -> FacilityPanelItem(
                    id = event.id,
                    location = event.location,
                    distanceFromStartMeters = event.geometryMetres,
                    distanceToItemMeters = distanceToItemMeters,
                    etaEpochMillis = etaEpochMillis,
                    name = event.name,
                    kind = event.kind,
                    roadClass = event.roadClass,
                    services = persistentListOf(),
                    subtitle = laneSubtitleOrNull(event.laneGuidance) ?: event.subtitle,
                )
                is TrackerPanelEvent.Maneuver -> ManeuverPanelItem(
                    id = event.event.id,
                    location = event.event.location,
                    distanceFromStartMeters = event.event.geometryMetres,
                    distanceToItemMeters = distanceToItemMeters,
                    etaEpochMillis = etaEpochMillis,
                    type = event.event.type,
                    modifier = event.event.modifier,
                    intersectionName = event.event.intersectionName,
                    exitNumber = event.event.exitNumber,
                    roadClass = event.event.roadClass,
                    facility = event.event.facility,
                    subtitle = laneSubtitleOrNull(event.event.laneGuidance) ?: event.event.subtitle,
                )
            }
        }
        .toList()
        .toImmutableList()

    /**
     * 指定地点の推定通過時刻を route 全体の平均所要時間から求める。
     *
     * @param route durationSeconds を持つ route
     * @param totalGeometryMetres geometry 上の総距離
     * @param currentCumulativeMeters 現在地の geometry 累積距離
     * @param targetMetres 推定対象地点の geometry 累積距離
     * @param timestampMillis 現在 tick の時刻
     * @return 推定通過時刻。計算できない場合は null
     */
    private fun etaEpochMillisFor(
        route: RouteDetail,
        totalGeometryMetres: Double,
        currentCumulativeMeters: Double,
        targetMetres: Double,
        timestampMillis: Long,
    ): Long? {
        if (route.durationSeconds <= 0.0 || totalGeometryMetres <= 0.0) return null

        val distanceToTarget = (targetMetres - currentCumulativeMeters)
            .coerceAtLeast(0.0)
        val secondsToTarget = route.durationSeconds * (distanceToTarget / totalGeometryMetres)
        return timestampMillis + secondsToTarget.roundToInt().toLong() * MILLIS_PER_SECOND
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
    // Off-route / maneuver classification
    // ---------------------------------------------------------------------

    /**
     * 今回 tick が off-route 候補かどうかを 1 tick 単位で判定する。
     *
     * @param projection 今回 tick の projection
     * @param location 今回 tick の生 GPS
     * @param attached attach 済み route 情報
     * @return debounce 前の off-route 候補なら true
     */
    private fun isOffRouteCandidate(
        projection: RouteProjection,
        location: UserLocation,
        attached: AttachedRoute,
    ): Boolean {
        if (remainingDistanceMetres(attached, projection) <= ARRIVAL_SUPPRESSION_METRES) return false
        if (location.hasTooCoarseAccuracyForOffRoute()) return false
        if (location.hasTooSlowSpeedForOffRoute()) return false

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
     * @return debounce 後の route match 状態
     */
    private fun updateRouteMatchState(
        attached: AttachedRoute,
        projection: RouteProjection,
        location: UserLocation,
        isOffRouteCandidate: Boolean,
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
     * 通常区間では 50m または accuracy x2.5 を超える逸脱が 2 秒以上 / 3 tick 以上続いた場合に確定する。
     * 案内地点や交差点付近では、間違えて曲がったケースを早く拾うため、方位差が十分大きければ 2 tick で確定する。
     *
     * @param attached attach 済み route 情報
     * @param projection 今回 tick の projection
     * @param location 今回 tick の生 GPS
     * @param candidate 継続中の off-route 候補
     * @return リルート対象として確定できる場合 true
     */
    private fun isOffRouteConfirmedSample(
        attached: AttachedRoute,
        projection: RouteProjection,
        location: UserLocation,
        candidate: OffRouteCandidate,
    ): Boolean {
        val exceedsConfirmedDistance = projection.projectionErrorMeters >= offRouteConfirmedThreshold(location)
        val hasEnoughSamples = candidate.sampleCount >= OFF_ROUTE_CONFIRMATION_SAMPLE_COUNT
        val hasEnoughDuration = candidate.sampleCount >= OFF_ROUTE_MIN_DURATION_SAMPLE_COUNT &&
            candidate.durationMillis >= OFF_ROUTE_CONFIRMATION_DURATION_MILLIS

        if (exceedsConfirmedDistance && (hasEnoughSamples || hasEnoughDuration)) {
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
    ): Boolean = attached.maneuverEvents.any { event ->
        abs(event.geometryMetres - projection.currentCumulativeMeters) <= DECISION_POINT_RADIUS_METRES
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
     * 指定距離の地点付近の道路種別を返す。
     *
     * @param route roadClassSegments を持つ route
     * @param cumulativeMetres geometry index ごとの累積距離
     * @param targetMetres 判定対象の geometry 累積距離
     * @return 該当する道路種別。見つからない場合は一般道
     */
    private fun roadClassAt(
        route: RouteDetail,
        cumulativeMetres: DoubleArray,
        targetMetres: Double,
    ): RoadClass = currentRoadClassFor(
        route = route,
        matchedSegmentIndex = segmentIndexAt(
            cumulativeMetres = cumulativeMetres,
            targetMetres = targetMetres,
        ),
    )

    /**
     * GP が TBT の対象になる進路選択イベントかどうかを返す。
     *
     * @param isLastGuidancePoint 最終 GP かどうか
     * @param bearingDiffDegrees GP 前後の進行方位差
     * @param facility 施設種別。施設のみの GP は TBT から除外する
     * @return TBT 対象なら true
     */
    private fun ExtNavGuidancePoint.requiresManeuverEvent(
        isLastGuidancePoint: Boolean,
        bearingDiffDegrees: Float,
        facility: GuidancePanelFacility?,
    ): Boolean {
        if (isLastGuidancePoint) return true

        val categories = categories()
        val hasManeuverCategory = categories.any { category -> category in ManeuverClassifier.MANEUVER_CATEGORIES }
        val hasRouteDecisionDirection = hasRouteDecisionDirection(bearingDiffDegrees)
        val isMergeAlert = categories.any { category -> category in ManeuverClassifier.MERGE_CATEGORIES } &&
            categories.none { category -> category in ManeuverClassifier.ROUTE_DECISION_CATEGORIES }

        if (facility?.isPanelOnlyFacility() == true) return false
        if (isMergeAlert) return false
        if (facility != null && !hasRouteDecisionDirection) {
            return false
        }

        if (hasManeuverCategory) return true

        val hasMeaningfulPhrase = categories.any { category ->
            category != GuidanceCategory.Unspecified &&
                category != GuidanceCategory.RoadName
        }
        return hasMeaningfulPhrase && abs(bearingDiffDegrees) >= ManeuverClassifier.TURN_BEARING_DIFF_DEGREES
    }

    /**
     * GP が route 選択を伴う方向を持つかを返す。
     *
     * @param bearingDiffDegrees geometry 前後の進行方位差
     * @return 直進通過以外と判断できる場合 true
     */
    private fun ExtNavGuidancePoint.hasRouteDecisionDirection(
        bearingDiffDegrees: Float,
    ): Boolean =
        maneuver?.direction?.isRouteDecisionDirection() == true ||
            abs(bearingDiffDegrees) >= ManeuverClassifier.TURN_BEARING_DIFF_DEGREES

    /**
     * GP に紐付く phrase category を取得する。
     *
     * @return GP の phrase category 一覧
     */
    private fun ExtNavGuidancePoint.categories(): List<GuidanceCategory> =
        phrases.map { phrase -> phrase.category }

    /**
     * GP からパネル用施設種別を推定する。
     *
     * @return パネルに表示できる施設種別。施設でない場合は null
     */
    private fun ExtNavGuidancePoint.panelFacility(
        nearestIntersection: TrackerIntersection?,
    ): GuidancePanelFacility? {
        val facility = maneuver?.facilityHint?.kind?.toPanelFacility()
            ?: nearestIntersection?.facility
            ?: return null

        return facility.refinedByName(nearestIntersection?.name.orEmpty())
    }

    /**
     * GP のパネル補助表示を返す。
     *
     * @param route 案内対象 route
     * @param cumulativeMetres geometry index ごとの累積距離
     * @param geometryMetres GP の geometry 累積距離
     * @param nearestIntersection GP 近傍の intersection
     * @return パネル補助表示。表示すべき情報が無い場合は null
     */
    private fun ExtNavGuidancePoint.panelManeuverSubtitle(
        route: RouteDetail,
        cumulativeMetres: DoubleArray,
        geometryMetres: Double,
        nearestIntersection: TrackerIntersection?,
    ): GuidancePanelSubtitle? =
        highwayBoundarySubtitleAt(
            route = route,
            cumulativeMetres = cumulativeMetres,
            geometryMetres = geometryMetres,
        )
            ?: nearestIntersection
                ?.guidanceText
                ?.let { guidanceText -> GuidanceTextPanelSubtitle(text = guidanceText) }

    /**
     * 施設パネル行の補助表示を返す。
     *
     * @param route 案内対象 route
     * @param cumulativeMetres geometry index ごとの累積距離
     * @param geometryMetres 施設の geometry 累積距離
     * @return パネル補助表示。表示すべき情報が無い場合は null
     */
    private fun GuidancePanelFacility.panelFacilitySubtitle(
        route: RouteDetail,
        cumulativeMetres: DoubleArray,
        geometryMetres: Double,
    ): GuidancePanelSubtitle? {
        if (this == GuidancePanelFacility.TOLL_GATE) {
            return route.panelTollYen()?.let { amountYen -> TollPanelSubtitle(amountYen = amountYen) }
        }

        return when (this) {
            GuidancePanelFacility.IC,
            GuidancePanelFacility.JCT,
            -> highwayBoundarySubtitleAt(
                route = route,
                cumulativeMetres = cumulativeMetres,
                geometryMetres = geometryMetres,
            )
            GuidancePanelFacility.SA,
            GuidancePanelFacility.PA,
            GuidancePanelFacility.TOLL_GATE,
            -> null
        }
    }

    /**
     * 方面看板由来の案内文を返す。
     *
     * @return 案内文。表示すべき情報が無い場合は null
     */
    private fun ExtNavIntersection.panelGuidanceText(): String? =
        directionSignA.trim().takeIf { text -> text.isNotEmpty() }
            ?: directionSignB.trim().takeIf { text -> text.isNotEmpty() }

    /**
     * 料金所表示に使う合計料金を返す。
     *
     * @return 料金（円）。取得できない場合は null
     */
    private fun RouteDetail.panelTollYen(): Int? =
        tollFee?.takeIf { amountYen -> amountYen > 0 }
            ?: tollDetails
                .sumOf { detail -> detail.amount }
                .takeIf { amountYen -> amountYen > 0 }

    /**
     * 指定距離が高速道路の入口 / 出口付近なら、その補助表示を返す。
     *
     * @param route 案内対象 route
     * @param cumulativeMetres geometry index ごとの累積距離
     * @param geometryMetres 判定対象の geometry 累積距離
     * @return 入口 / 出口の補助表示。境界付近でない場合は null
     */
    private fun highwayBoundarySubtitleAt(
        route: RouteDetail,
        cumulativeMetres: DoubleArray,
        geometryMetres: Double,
    ): GuidancePanelSubtitle? {
        val candidates = mutableListOf<Pair<Double, GuidancePanelSubtitle>>()

        for (segment in route.roadClassSegments) {
            if (segment.roadClass != RoadClass.HIGHWAY) continue

            if (segment.startPointIndex > 0) {
                cumulativeMetres.valueAtOrNull(segment.startPointIndex)?.let { startMetres ->
                    candidates += startMetres to EntrancePanelSubtitle
                }
            }

            if (segment.endPointIndex < route.geometry.lastIndex) {
                cumulativeMetres.valueAtOrNull(segment.endPointIndex)?.let { endMetres ->
                    candidates += endMetres to ExitPanelSubtitle
                }
            }
        }

        return candidates
            .minByOrNull { candidate -> abs(candidate.first - geometryMetres) }
            ?.takeIf { candidate ->
                abs(candidate.first - geometryMetres) <= HIGHWAY_BOUNDARY_PANEL_TOLERANCE_METRES
            }
            ?.second
    }

    /**
     * index 範囲内の累積距離を返す。
     */
    private fun DoubleArray.valueAtOrNull(index: Int): Double? =
        if (index in indices) this[index] else null

    /**
     * レーン情報をレーンガイダンスへ変換する。
     *
     * @param modifier レーンアイコンの方向
     * @return レーンガイダンス。推奨レーンが無い場合は null
     */
    private fun ExtNavLaneInfo.toLaneGuidance(
        modifier: ManeuverModifier,
    ): LaneGuidance? {
        if (markers.isEmpty() || markers.none { marker -> marker.isRecommended }) return null

        return LaneGuidance(
            lanes = markers
                .map { marker ->
                    Lane(
                        allowedDirections = persistentListOf(modifier),
                        recommendedDirection = modifier.takeIf { marker.isRecommended },
                        isActive = marker.isRecommended,
                    )
                }
                .toImmutableList(),
        )
    }

    /**
     * intersection に施設マーカーが立っている場合だけパネル用施設種別を返す。
     *
     * @return パネルに表示できる施設種別。施設マーカーでない場合は null
     */
    private fun ExtNavIntersection.panelFacility(): GuidancePanelFacility? =
        facilityHint
            ?.kind
            ?.toPanelFacility()
            ?.refinedByName(name)

    /**
     * 外部ナビ API ライブラリ由来の施設種別をパネル用施設種別へ変換する。
     *
     * @return パネル用施設種別。端点などパネルに出さないものは null
     */
    private fun ExtNavGuidanceFacilityKind.toPanelFacility(): GuidancePanelFacility? = when (this) {
        ExtNavGuidanceFacilityKind.INTERCHANGE -> GuidancePanelFacility.IC
        ExtNavGuidanceFacilityKind.PARKING_AREA -> GuidancePanelFacility.PA
        ExtNavGuidanceFacilityKind.TOLL_GATE -> GuidancePanelFacility.TOLL_GATE
        ExtNavGuidanceFacilityKind.ENDPOINT -> null
    }

    /**
     * 施設名から IC / JCT や SA / PA の表示種別を補正する。
     *
     * @param name 施設名
     * @return 名前から補正したパネル用施設種別
     */
    private fun GuidancePanelFacility.refinedByName(name: String): GuidancePanelFacility = when {
        this == GuidancePanelFacility.IC && name.isJunctionName() -> GuidancePanelFacility.JCT
        this == GuidancePanelFacility.PA && name.isServiceAreaName() -> GuidancePanelFacility.SA
        else -> this
    }

    /**
     * 進路判断ではなくパネル専用として扱う施設かを返す。
     *
     * @return パネル専用なら true
     */
    private fun GuidancePanelFacility.isPanelOnlyFacility(): Boolean = when (this) {
        GuidancePanelFacility.SA,
        GuidancePanelFacility.PA,
        GuidancePanelFacility.TOLL_GATE,
        -> true
        GuidancePanelFacility.IC,
        GuidancePanelFacility.JCT,
        -> false
    }

    /**
     * 進路判断を伴う方向かを返す。
     *
     * @return 直進・不明以外なら true
     */
    private fun ExtNavManeuverDirection.isRouteDecisionDirection(): Boolean =
        ManeuverClassifier.isRouteDecisionDirection(this)

    /**
     * 外部ナビ API ライブラリ由来の方向を UI 用 modifier へ変換する。
     *
     * @return UI 用 maneuver modifier
     */
    private fun ExtNavManeuverDirection.toManeuverModifier(): ManeuverModifier =
        ManeuverClassifier.toManeuverModifier(this)

    /**
     * 施設パネル行の表示名を返す。
     *
     * @return 表示名
     */
    private fun TrackerIntersection.panelFacilityName(): String =
        name.takeIf { value -> value.isNotBlank() }.orEmpty()

    /**
     * JCT 名として扱える文字列かどうかを返す。
     *
     * @return JCT 名なら true
     */
    private fun String.isJunctionName(): Boolean =
        contains("JCT", ignoreCase = true)

    /**
     * SA 名として扱える文字列かどうかを返す。
     *
     * @return SA 名なら true
     */
    private fun String.isServiceAreaName(): Boolean =
        contains("SA", ignoreCase = true)

    /**
     * phrase category と方位差から maneuver 種別を推定する。
     *
     * @param categories GP に紐づく phrase category
     * @param isLastGuidancePoint 最終 GP かどうか
     * @param bearingDiffDegrees GP 前後の進行方位差
     * @return UI 用 maneuver 種別
     */
    private fun maneuverType(
        categories: List<GuidanceCategory>,
        isLastGuidancePoint: Boolean,
        bearingDiffDegrees: Float,
    ): ManeuverType = ManeuverClassifier.maneuverType(
        categories = categories,
        isLastGuidancePoint = isLastGuidancePoint,
        bearingDiffDegrees = bearingDiffDegrees,
    )

    /**
     * 方位差から左右・直進などの modifier を推定する。
     *
     * @param bearingDiffDegrees GP 前後の進行方位差
     * @return UI 用 maneuver modifier
     */
    private fun maneuverModifier(bearingDiffDegrees: Float): ManeuverModifier =
        ManeuverClassifier.maneuverModifier(bearingDiffDegrees)

    /**
     * 指定距離付近の前後 segment 方位差を計算する。
     *
     * @param route 方位計算対象 route
     * @param cumulativeMetres geometry index ごとの累積距離
     * @param targetMetres 方位差を求める geometry 累積距離
     * @return -180 度から 180 度に正規化した方位差
     */
    private fun bearingDiffAt(
        route: RouteDetail,
        cumulativeMetres: DoubleArray,
        targetMetres: Double,
    ): Float = RouteGeometryMath.bearingDiffAt(
        geometry = route.geometry,
        cumulativeMetres = cumulativeMetres,
        targetMetres = targetMetres,
    )

    /**
     * route geometry 上の累積距離から座標を補間する。
     *
     * @param route 座標を求める route
     * @param cumulativeMetres geometry index ごとの累積距離
     * @param targetMetres 座標を求める geometry 累積距離
     * @return route geometry 上の座標。geometry が無い場合は route origin
     */
    private fun routePointAt(
        route: RouteDetail,
        cumulativeMetres: DoubleArray,
        targetMetres: Double,
    ): RoutePoint = RouteGeometryMath.pointAt(
        geometry = route.geometry,
        cumulativeMetres = cumulativeMetres,
        targetMetres = targetMetres,
        fallback = route.origin,
    )

    // ---------------------------------------------------------------------
    // Small math helpers
    // ---------------------------------------------------------------------

    /**
     * 累積距離から、その距離を含む segment index を二分探索で求める。
     *
     * @param cumulativeMetres geometry index ごとの累積距離
     * @param targetMetres 探索する geometry 累積距離
     * @return targetMetres を含む segment index
     */
    private fun segmentIndexAt(
        cumulativeMetres: DoubleArray,
        targetMetres: Double,
    ): Int = RouteGeometryMath.segmentIndexAt(cumulativeMetres, targetMetres)

    /**
     * value より先にある最初の maneuver event index を二分探索で返す。
     *
     * @param value 探索基準値
     * @return value より先にある最初の index。存在しない場合は null
     */
    private fun List<TrackerManeuverEvent>.firstIndexGreaterThan(value: Double): Int? {
        if (isEmpty()) return null

        var lowIndex = 0
        var highIndex = size

        while (lowIndex < highIndex) {
            val middleIndex = (lowIndex + highIndex) / 2
            if (this[middleIndex].geometryMetres <= value) {
                lowIndex = middleIndex + 1
            } else {
                highIndex = middleIndex
            }
        }

        return lowIndex.takeIf { index -> index < size }
    }

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
     * attach 中の route で tick 時に再利用する事前計算済みデータ。
     *
     * @param route 案内対象の中立 route
     * @param cumulativeMetres geometry index ごとの累積距離
     * @param totalGeometryMetres geometry の総距離
     * @param maneuverEvents TBT 対象にする進路選択イベント
     * @param laneAnchors レーン情報を持つ GP の anchor
     * @param panelEvents 案内中パネルへ表示するイベント
     */
    private class AttachedRoute(
        val route: RouteDetail,
        val cumulativeMetres: DoubleArray,
        val totalGeometryMetres: Double,
        val maneuverEvents: List<TrackerManeuverEvent>,
        val laneAnchors: List<TrackerLaneAnchor>,
        val panelEvents: List<TrackerPanelEvent>,
    )

    /**
     * TBT 対象にする進路選択イベント。
     *
     * @param id event の安定 ID
     * @param guidancePointIndex 元の guidance point index
     * @param geometryMetres route geometry 上の累積距離
     * @param location route geometry 上の地点座標
     * @param type マニューバ種別
     * @param modifier 左右・直進などの方向修飾子
     * @param intersectionName 交差点名や分岐名
     * @param exitNumber 出口番号
     * @param roadClass 地点付近の道路種別
     * @param facility 施設を伴う案内地点の場合の施設種別
     * @param subtitle 補助表示。表示すべき情報が無い場合は null
     * @param laneGuidance レーンガイダンス。無い場合は null
     */
    private data class TrackerManeuverEvent(
        val id: String,
        val guidancePointIndex: Int,
        val geometryMetres: Double,
        val location: RoutePoint,
        val type: ManeuverType,
        val modifier: ManeuverModifier,
        val intersectionName: String?,
        val exitNumber: String?,
        val roadClass: RoadClass,
        val facility: GuidancePanelFacility?,
        val subtitle: GuidancePanelSubtitle?,
        val laneGuidance: LaneGuidance?,
    )

    /**
     * 案内中パネルへ表示するイベント。
     */
    private sealed interface TrackerPanelEvent {
        val geometryMetres: Double

        /**
         * 進路選択イベントのパネル行。
         *
         * @param event 元の進路選択イベント
         */
        data class Maneuver(
            val event: TrackerManeuverEvent,
        ) : TrackerPanelEvent {
            override val geometryMetres: Double = event.geometryMetres
        }

        /**
         * 通過施設イベントのパネル行。
         *
         * @param id event の安定 ID
         * @param geometryMetres route geometry 上の累積距離
         * @param location route geometry 上の地点座標
         * @param name 施設名
         * @param kind 施設種別
         * @param roadClass 地点付近の道路種別
         * @param subtitle 補助表示。表示すべき情報が無い場合は null
         * @param laneGuidance レーンガイダンス。無い場合は null
         */
        data class Facility(
            val id: String,
            override val geometryMetres: Double,
            val location: RoutePoint,
            val name: String,
            val kind: GuidancePanelFacility,
            val roadClass: RoadClass,
            val subtitle: GuidancePanelSubtitle?,
            val laneGuidance: LaneGuidance?,
        ) : TrackerPanelEvent
    }

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
     * intersection を geometry 距離へ snap した anchor。
     *
     * @param geometryMetres intersection に対応する geometry 累積距離
     * @param name intersection 名
     * @param guidanceText 方面看板由来の案内文。取得できない場合は null
     * @param facility パネルに出せる施設種別。施設でない場合は null
     */
    private data class TrackerIntersection(
        val geometryMetres: Double,
        val name: String,
        val guidanceText: String?,
        val facility: GuidancePanelFacility?,
    )

    /**
     * レーン情報を geometry 距離へ snap した anchor。
     *
     * @param geometryMetres レーン情報に対応する geometry 累積距離
     * @param laneGuidance 推奨レーンを含むレーンガイダンス
     */
    private data class TrackerLaneAnchor(
        val geometryMetres: Double,
        val laneGuidance: LaneGuidance,
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

    /** Tracker 内の数値閾値と category 分類定義。 */
    private companion object {
        /** 緯度 1 度をメートルへ近似変換する係数。 */
        private const val METRES_PER_DEGREE: Double = 111_320.0

        /** 秒からミリ秒へ変換する係数。 */
        private const val MILLIS_PER_SECOND: Long = 1_000L

        /** 前回 projection 以降に探索する最大 segment 数。 */
        private const val MAX_SEGMENT_LOOKAHEAD: Int = 300

        /** 現在地と同距離の GP を通過済みにするための小さな epsilon。 */
        private const val NEXT_GP_EPSILON_METRES: Double = 1.0

        /** GPS jitter とみなして前回 projection を維持する最大後退距離。 */
        private const val BACKWARD_HYSTERESIS_METRES: Double = 5.0

        /** GP と intersection を対応付ける最大距離。 */
        private const val INTERSECTION_SNAP_TOLERANCE_METRES: Double = 300.0

        /** 高速入口 / 出口と panel event を対応付ける最大距離。 */
        private const val HIGHWAY_BOUNDARY_PANEL_TOLERANCE_METRES: Double = 600.0

        /** 施設パネルとレーン anchor を対応付ける最大距離。 */
        private const val LANE_FACILITY_SNAP_TOLERANCE_METRES: Double = 150.0

        /** メインのレーンガイダンスを表示し始める手前距離。 */
        private const val LANE_GUIDANCE_VISIBILITY_METRES: Double = 800.0

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
        private const val MIN_OFF_ROUTE_CONFIRMED_ERROR_METRES: Double = 50.0

        /** GPS 精度から confirmed 閾値を増やす倍率。 */
        private const val OFF_ROUTE_CONFIRMED_ACCURACY_MULTIPLIER: Double = 2.5

        /** confirmed 判定に必要な off-route candidate の連続 tick 数。 */
        private const val OFF_ROUTE_CONFIRMATION_SAMPLE_COUNT: Int = 3

        /** confirmed 判定で時間条件を許可する最小 tick 数。 */
        private const val OFF_ROUTE_MIN_DURATION_SAMPLE_COUNT: Int = 2

        /** confirmed 判定に必要な off-route candidate 継続時間。 */
        private const val OFF_ROUTE_CONFIRMATION_DURATION_MILLIS: Long = 2_000L

        /** 案内判断点付近で confirmed 判定に必要な連続 tick 数。 */
        private const val OFF_ROUTE_DECISION_POINT_CONFIRMATION_SAMPLE_COUNT: Int = 2

        /** 案内地点・交差点付近として扱う route 上距離。 */
        private const val DECISION_POINT_RADIUS_METRES: Double = 40.0

        /** 案内判断点付近で使う GPS bearing と segment bearing の最小差分。 */
        private const val OFF_ROUTE_DECISION_POINT_BEARING_DIFF_DEGREES: Float = 45f
    }
}
