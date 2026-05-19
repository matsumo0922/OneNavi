package me.matsumo.onenavi.core.navigation.extnav

import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.matsumo.onenavi.core.datasource.location.UserLocation
import me.matsumo.onenavi.core.model.ManeuverModifier
import me.matsumo.onenavi.core.model.ManeuverType
import me.matsumo.onenavi.core.model.RoadClass
import me.matsumo.onenavi.core.model.RouteDetail
import me.matsumo.onenavi.core.model.RoutePoint
import me.matsumo.onenavi.core.navigation.newguidance.model.GuidanceManeuverInfo
import me.matsumo.onenavi.core.navigation.newguidance.model.GuidanceProgress
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

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

    /**
     * 案内対象の route と、Preview 時にキャッシュした外部ナビ API ライブラリ由来 payload を紐付ける。
     *
     * tick ごとの計算を軽くするため、ここで geometry の累積距離、GP の geometry 距離、
     * intersection の geometry 距離を事前計算する。
     */
    @Suppress("unused")
    fun attach(payload: ExtNavRoutePayload, route: RouteDetail) {
        attachedRoute = buildAttachedRoute(payload, route)
        lastProjection = null
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

        lastProjection = projection
        _snapshot.value = buildSnapshot(
            attached = attached,
            projection = projection,
            location = location,
        )
    }

    /** attach 済み route、前回 projection、公開 snapshot を破棄する。 */
    @Suppress("unused")
    fun detach() {
        attachedRoute = null
        lastProjection = null
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

        return AttachedRoute(
            payload = payload,
            route = route,
            cumulativeMetres = cumulativeMetres,
            totalGeometryMetres = totalGeometryMetres,
            guidancePointMetres = buildGuidancePointMetres(
                payload = payload,
                distanceMapper = distanceMapper,
                totalGeometryMetres = totalGeometryMetres,
            ),
            intersections = buildIntersectionAnchors(
                payload = payload,
                route = route,
                cumulativeMetres = cumulativeMetres,
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
    private fun buildCumulativeGeometryMetres(geometry: List<RoutePoint>): DoubleArray {
        if (geometry.isEmpty()) return DoubleArray(0)

        val cumulativeMetres = DoubleArray(geometry.size)
        for (index in 1 until geometry.size) {
            cumulativeMetres[index] = cumulativeMetres[index - 1] +
                haversineMetres(geometry[index - 1], geometry[index])
        }
        return cumulativeMetres
    }

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
                )
            }
            .sortedBy { intersection -> intersection.geometryMetres }
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
        val nextGuidancePointIndex = nextGuidancePointIndex(
            attached = attached,
            projection = projection,
        )
        val progress = buildProgress(
            attached = attached,
            projection = projection,
            location = location,
            distanceRemainingMetres = distanceRemainingMetres,
            nextGuidancePointIndex = nextGuidancePointIndex,
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
            isOffRouteCandidate = isOffRouteCandidate(
                projection = projection,
                location = location,
                attached = attached,
            ),
            nextGuidancePointIndex = nextGuidancePointIndex,
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
     * 現在地より先にある最初の GP index を返す。
     *
     * @param attached GP の geometry 距離配列を持つ attach 済み情報
     * @param projection 現在 projection
     * @return 次 GP index。最後の GP 通過後や GP なしの場合は null
     */
    private fun nextGuidancePointIndex(
        attached: AttachedRoute,
        projection: RouteProjection,
    ): Int? = attached.guidancePointMetres
        .firstIndexGreaterThan(projection.currentCumulativeMeters + NEXT_GP_EPSILON_METRES)

    /**
     * UI が直接読む [GuidanceProgress] を組み立てる。
     *
     * @param attached attach 済み route 情報
     * @param projection 今回 tick の projection
     * @param location 今回 tick の生 GPS
     * @param distanceRemainingMetres 事前算出済みの残距離
     * @param nextGuidancePointIndex 次 GP index
     * @return UI 表示用の案内進捗
     */
    private fun buildProgress(
        attached: AttachedRoute,
        projection: RouteProjection,
        location: UserLocation,
        distanceRemainingMetres: Double,
        nextGuidancePointIndex: Int?,
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
            snappedLocation = projection.snappedLocation,
            bearingDegrees = location.bearingDegrees ?: projection.segmentBearingDegrees,
            nextManeuver = nextGuidancePointIndex?.let { guidancePointIndex ->
                buildManeuverInfo(
                    attached = attached,
                    guidancePointIndex = guidancePointIndex,
                    currentCumulativeMeters = projection.currentCumulativeMeters,
                )
            },
            followupManeuver = buildFollowupManeuverInfo(
                attached = attached,
                nextGuidancePointIndex = nextGuidancePointIndex,
                currentCumulativeMeters = projection.currentCumulativeMeters,
            ),
            lanes = persistentListOf(),
            directionSign = null,
            highwayPanel = null,
            currentRoadName = null,
            currentRoadClass = currentRoadClassFor(
                route = attached.route,
                matchedSegmentIndex = projection.matchedSegmentIndex,
            ),
            currentSpeedLimitKmh = null,
        )
    }

    /**
     * 次 GP のさらに次にある follow-up maneuver を作る。
     *
     * @param attached attach 済み route 情報
     * @param nextGuidancePointIndex 次 GP index
     * @param currentCumulativeMeters 現在地の geometry 累積距離
     * @return follow-up maneuver。存在しない場合は null
     */
    private fun buildFollowupManeuverInfo(
        attached: AttachedRoute,
        nextGuidancePointIndex: Int?,
        currentCumulativeMeters: Double,
    ): GuidanceManeuverInfo? = nextGuidancePointIndex
        ?.plus(1)
        ?.takeIf { guidancePointIndex -> guidancePointIndex <= attached.guidancePointMetres.lastIndex }
        ?.let { guidancePointIndex ->
            buildManeuverInfo(
                attached = attached,
                guidancePointIndex = guidancePointIndex,
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
        attached: AttachedRoute,
        guidancePointIndex: Int,
        currentCumulativeMeters: Double,
    ): GuidanceManeuverInfo {
        val guidancePoint = attached.payload.routeGuidance.guidancePoints[guidancePointIndex]
        val guidancePointMetres = attached.guidancePointMetres[guidancePointIndex]
        val nearestIntersection = nearestIntersectionToGuidancePoint(
            attached = attached,
            guidancePointMetres = guidancePointMetres,
        )
        val bearingDiffDegrees = bearingDiffAt(
            route = attached.route,
            cumulativeMetres = attached.cumulativeMetres,
            targetMetres = guidancePointMetres,
        )

        return GuidanceManeuverInfo(
            type = maneuverType(
                categoryNames = guidancePoint.phrases.map { phrase -> phrase.category.name },
                isLastGuidancePoint = guidancePointIndex == attached.guidancePointMetres.lastIndex,
                bearingDiffDegrees = bearingDiffDegrees,
            ),
            modifier = maneuverModifier(bearingDiffDegrees),
            distanceToManeuverMeters = (guidancePointMetres - currentCumulativeMeters)
                .coerceAtLeast(0.0)
                .roundToInt(),
            intersectionName = nearestIntersection?.name?.takeIf { name -> name.isNotBlank() },
            exitNumber = null,
            guidancePointIndex = guidancePointIndex,
        )
    }

    /**
     * GP の geometry 距離に最も近い intersection を探す。
     *
     * @param attached intersection anchors を持つ attach 済み情報
     * @param guidancePointMetres GP の geometry 累積距離
     * @return 許容距離内で最も近い intersection。見つからない場合は null
     */
    private fun nearestIntersectionToGuidancePoint(
        attached: AttachedRoute,
        guidancePointMetres: Double,
    ): TrackerIntersection? = attached.intersections
        .filter { intersection ->
            abs(intersection.geometryMetres - guidancePointMetres) <= INTERSECTION_SNAP_TOLERANCE_METRES
        }
        .minByOrNull { intersection -> abs(intersection.geometryMetres - guidancePointMetres) }

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
    ): RoutePoint = RoutePoint(
        latitude = start.latitude + (end.latitude - start.latitude) * ratio,
        longitude = start.longitude + (end.longitude - start.longitude) * ratio,
    )

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
        if (location.accuracyMeters > MAX_OFF_ROUTE_ACCURACY_METRES) return false
        if ((location.speedMps ?: 0f) < MIN_OFF_ROUTE_SPEED_MPS) return false
        if (projection.projectionErrorMeters < offRouteErrorThreshold(location)) return false

        val bearingDegrees = location.bearingDegrees ?: return true
        val bearingDiffDegrees = abs(normalizeDegrees(bearingDegrees - projection.segmentBearingDegrees))

        return bearingDiffDegrees >= OFF_ROUTE_BEARING_DIFF_DEGREES
    }

    /**
     * GPS 精度を加味した off-route 判定用の横ズレ閾値を返す。
     *
     * @param location 今回 tick の生 GPS
     * @return off-route 候補とみなす projection error 閾値
     */
    private fun offRouteErrorThreshold(location: UserLocation): Double = max(
        MIN_OFF_ROUTE_ERROR_METRES,
        location.accuracyMeters * OFF_ROUTE_ACCURACY_MULTIPLIER,
    )

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
     * phrase category と方位差から maneuver 種別を推定する。
     *
     * @param categoryNames GP に紐づく phrase category 名
     * @param isLastGuidancePoint 最終 GP かどうか
     * @param bearingDiffDegrees GP 前後の進行方位差
     * @return UI 用 maneuver 種別
     */
    private fun maneuverType(
        categoryNames: List<String>,
        isLastGuidancePoint: Boolean,
        bearingDiffDegrees: Float,
    ): ManeuverType {
        if (isLastGuidancePoint) return ManeuverType.ARRIVE
        if (categoryNames.any { name -> name in MERGE_CATEGORY_NAMES }) return ManeuverType.MERGE
        if (categoryNames.any { name -> name in FORK_CATEGORY_NAMES }) return ManeuverType.FORK
        if (categoryNames.any { name -> name == ROAD_NAME_CATEGORY_NAME }) return ManeuverType.NAME_CHANGE
        if (abs(bearingDiffDegrees) >= TURN_BEARING_DIFF_DEGREES || categoryNames.any { name -> name in TURN_CATEGORY_NAMES }) {
            return ManeuverType.TURN
        }

        return ManeuverType.CONTINUE
    }

    /**
     * 方位差から左右・直進などの modifier を推定する。
     *
     * @param bearingDiffDegrees GP 前後の進行方位差
     * @return UI 用 maneuver modifier
     */
    private fun maneuverModifier(bearingDiffDegrees: Float): ManeuverModifier {
        val absDiffDegrees = abs(bearingDiffDegrees)

        return when {
            absDiffDegrees <= STRAIGHT_MAX_DEGREES -> ManeuverModifier.STRAIGHT
            absDiffDegrees <= SLIGHT_MAX_DEGREES -> if (bearingDiffDegrees >= 0f) ManeuverModifier.SLIGHT_RIGHT else ManeuverModifier.SLIGHT_LEFT
            absDiffDegrees <= TURN_MAX_DEGREES -> if (bearingDiffDegrees >= 0f) ManeuverModifier.RIGHT else ManeuverModifier.LEFT
            else -> ManeuverModifier.UTURN
        }
    }

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
    ): Float {
        if (route.geometry.size < 3 || cumulativeMetres.size < 3) return 0f

        val segmentIndex = segmentIndexAt(cumulativeMetres, targetMetres)
        val beforeIndex = (segmentIndex - 1).coerceAtLeast(0)
        val afterIndex = (segmentIndex + 1).coerceAtMost(route.geometry.lastIndex - 1)
        val beforeBearing = bearingDegrees(route.geometry[beforeIndex], route.geometry[beforeIndex + 1])
        val afterBearing = bearingDegrees(route.geometry[afterIndex], route.geometry[afterIndex + 1])

        return normalizeDegrees(afterBearing - beforeBearing)
    }

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
    ): Int {
        if (cumulativeMetres.size <= 1) return 0
        if (targetMetres <= 0.0) return 0
        if (targetMetres >= cumulativeMetres.last()) return cumulativeMetres.lastIndex - 1

        var lowIndex = 1
        var highIndex = cumulativeMetres.lastIndex

        while (lowIndex < highIndex) {
            val middleIndex = (lowIndex + highIndex) / 2
            if (cumulativeMetres[middleIndex] < targetMetres) {
                lowIndex = middleIndex + 1
            } else {
                highIndex = middleIndex
            }
        }

        return (lowIndex - 1).coerceIn(0, cumulativeMetres.lastIndex - 1)
    }

    /**
     * value より大きい最初の要素 index を二分探索で返す。
     *
     * @param value 探索基準値
     * @return value より大きい最初の index。存在しない場合は null
     */
    private fun DoubleArray.firstIndexGreaterThan(value: Double): Int? {
        if (isEmpty()) return null

        var lowIndex = 0
        var highIndex = size

        while (lowIndex < highIndex) {
            val middleIndex = (lowIndex + highIndex) / 2
            if (this[middleIndex] <= value) {
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
    private fun haversineMetres(from: RoutePoint, to: RoutePoint): Double {
        val fromLatRadians = latitudeRadians(from)
        val toLatRadians = latitudeRadians(to)
        val deltaLatRadians = Math.toRadians(to.latitude - from.latitude)
        val deltaLngRadians = Math.toRadians(to.longitude - from.longitude)
        val haversineTerm = sin(deltaLatRadians / 2.0) * sin(deltaLatRadians / 2.0) +
            cos(fromLatRadians) * cos(toLatRadians) * sin(deltaLngRadians / 2.0) * sin(deltaLngRadians / 2.0)
        return EARTH_RADIUS_METRES * 2.0 * atan2(sqrt(haversineTerm), sqrt(1.0 - haversineTerm))
    }

    /**
     * 2 点を結ぶ進行方位を計算する。
     *
     * @param from 始点
     * @param to 終点
     * @return 0 度以上 360 度未満の方位角
     */
    private fun bearingDegrees(from: RoutePoint, to: RoutePoint): Float {
        val fromLatRadians = latitudeRadians(from)
        val toLatRadians = latitudeRadians(to)
        val deltaLngRadians = Math.toRadians(to.longitude - from.longitude)
        val y = sin(deltaLngRadians) * cos(toLatRadians)
        val x = cos(fromLatRadians) * sin(toLatRadians) -
            sin(fromLatRadians) * cos(toLatRadians) * cos(deltaLngRadians)
        return ((Math.toDegrees(atan2(y, x)) + FULL_CIRCLE_DEGREES) % FULL_CIRCLE_DEGREES).toFloat()
    }

    /**
     * route point の緯度をラジアンへ変換する。
     *
     * @param point 変換対象 point
     * @return 緯度ラジアン
     */
    private fun latitudeRadians(point: RoutePoint): Double = Math.toRadians(point.latitude)

    /**
     * 方位差を -180 度から 180 度の範囲へ正規化する。
     *
     * @param degrees 正規化前の角度
     * @return 正規化後の角度
     */
    private fun normalizeDegrees(degrees: Float): Float {
        var normalized = degrees % FULL_CIRCLE_DEGREES
        if (normalized > HALF_CIRCLE_DEGREES) normalized -= FULL_CIRCLE_DEGREES
        if (normalized < -HALF_CIRCLE_DEGREES) normalized += FULL_CIRCLE_DEGREES
        return normalized
    }

    /**
     * attach 中の route で tick 時に再利用する事前計算済みデータ。
     *
     * @param payload Preview 時に取得した外部ナビ API ライブラリ由来 payload
     * @param route 案内対象の中立 route
     * @param cumulativeMetres geometry index ごとの累積距離
     * @param totalGeometryMetres geometry の総距離
     * @param guidancePointMetres GP index ごとの geometry 累積距離
     * @param intersections geometry 距離に snap 済みの intersection anchors
     */
    private class AttachedRoute(
        val payload: ExtNavRoutePayload,
        val route: RouteDetail,
        val cumulativeMetres: DoubleArray,
        val totalGeometryMetres: Double,
        val guidancePointMetres: DoubleArray,
        val intersections: List<TrackerIntersection>,
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
     * intersection を geometry 距離へ snap した anchor。
     *
     * @param geometryMetres intersection に対応する geometry 累積距離
     * @param name intersection 名
     */
    private data class TrackerIntersection(
        val geometryMetres: Double,
        val name: String,
    )

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
        /** haversine 距離計算で使う地球半径メートル。 */
        private const val EARTH_RADIUS_METRES: Double = 6_371_000.0

        /** 緯度 1 度をメートルへ近似変換する係数。 */
        private const val METRES_PER_DEGREE: Double = 111_320.0

        /** 方位角の 1 周分の度数。 */
        private const val FULL_CIRCLE_DEGREES: Float = 360f

        /** 方位差を正規化するときの半周分の度数。 */
        private const val HALF_CIRCLE_DEGREES: Float = 180f

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

        /** 目的地直前で off-route 候補を抑制する残距離。 */
        private const val ARRIVAL_SUPPRESSION_METRES: Double = 100.0

        /** off-route 判定を許可する最大 GPS 精度値。 */
        private const val MAX_OFF_ROUTE_ACCURACY_METRES: Float = 50f

        /** off-route 判定を許可する最小速度。 */
        private const val MIN_OFF_ROUTE_SPEED_MPS: Float = 1.5f

        /** off-route 判定で使う最小 projection error。 */
        private const val MIN_OFF_ROUTE_ERROR_METRES: Double = 30.0

        /** GPS 精度から projection error 閾値を増やす倍率。 */
        private const val OFF_ROUTE_ACCURACY_MULTIPLIER: Double = 1.5

        /** off-route 判定で使う GPS bearing と segment bearing の最小差分。 */
        private const val OFF_ROUTE_BEARING_DIFF_DEGREES: Float = 70f

        /** 方位差から turn とみなす最小角度。 */
        private const val TURN_BEARING_DIFF_DEGREES: Float = 30f

        /** straight modifier とみなす最大角度差。 */
        private const val STRAIGHT_MAX_DEGREES: Float = 5f

        /** slight modifier とみなす最大角度差。 */
        private const val SLIGHT_MAX_DEGREES: Float = 60f

        /** left / right modifier とみなす最大角度差。 */
        private const val TURN_MAX_DEGREES: Float = 150f

        /** merge 系 maneuver として扱う phrase category 名。 */
        private val MERGE_CATEGORY_NAMES = setOf(
            "Merge",
            "MergeAttention",
            "HighwayLaneReduction",
        )

        /** fork 系 maneuver として扱う phrase category 名。 */
        private val FORK_CATEGORY_NAMES = setOf(
            "AutoExpresswayEntry",
            "TunnelBranch",
        )

        /** turn 系 maneuver として扱う phrase category 名。 */
        private val TURN_CATEGORY_NAMES = setOf(
            "IntersectionGuide",
            "IntersectionGuideSoon",
            "TrafficLight",
            "TurnAttention",
            "LocalRoadDirection",
        )

        /** road name change として扱う phrase category 名。 */
        private const val ROAD_NAME_CATEGORY_NAME: String = "RoadName"
    }
}
