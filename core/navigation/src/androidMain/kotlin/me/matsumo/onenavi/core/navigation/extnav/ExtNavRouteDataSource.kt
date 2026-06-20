package me.matsumo.onenavi.core.navigation.extnav

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import me.matsumo.drive.supporter.api.DriveSupporterClient
import me.matsumo.drive.supporter.api.core.model.Coord
import me.matsumo.drive.supporter.api.core.result.ApiResult
import me.matsumo.drive.supporter.api.guidance.domain.CongestionLevel
import me.matsumo.drive.supporter.api.guidance.domain.GuidanceCategory
import me.matsumo.drive.supporter.api.guidance.domain.GuidanceFacilityKind
import me.matsumo.drive.supporter.api.guidance.domain.Intersection
import me.matsumo.drive.supporter.api.guidance.domain.RouteCongestionSegment
import me.matsumo.drive.supporter.api.guidance.domain.RouteGuidance
import me.matsumo.drive.supporter.api.guidance.domain.StreetSegment
import me.matsumo.drive.supporter.api.route.domain.CarPriority
import me.matsumo.drive.supporter.api.route.domain.Route
import me.matsumo.drive.supporter.api.route.domain.RouteSearchCriteria
import me.matsumo.drive.supporter.api.route.domain.RouteWaypoint
import me.matsumo.drive.supporter.api.sapa.domain.SapaDetail
import me.matsumo.drive.supporter.api.sapa.domain.SapaSearchResult
import me.matsumo.onenavi.core.datasource.RouteDataSource
import me.matsumo.onenavi.core.model.CongestionSegment
import me.matsumo.onenavi.core.model.CongestionSegmentSource
import me.matsumo.onenavi.core.model.CongestionSeverity
import me.matsumo.onenavi.core.model.CongestionTrend
import me.matsumo.onenavi.core.model.RoadClass
import me.matsumo.onenavi.core.model.RoadClassSegment
import me.matsumo.onenavi.core.model.RoadSegmentDistance
import me.matsumo.onenavi.core.model.RouteDetail
import me.matsumo.onenavi.core.model.RouteItem
import me.matsumo.onenavi.core.model.RoutePoint
import me.matsumo.onenavi.core.model.RoutePriority
import me.matsumo.onenavi.core.model.RouteResult
import me.matsumo.onenavi.core.model.TollSegmentFee
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import me.matsumo.drive.supporter.api.guidance.domain.CongestionTrend as ExtNavCongestionTrend
import me.matsumo.drive.supporter.api.guidance.domain.RouteCongestionSource as ExtNavRouteCongestionSource

/**
 * 外部ナビ API ライブラリを使ったルート検索データソース。
 * `GuidanceClient.resolveGuidance` と priority 別の `RouteClient.search` を並列に発行し、
 * 中立な [RouteDetail] モデルに射影する。ルート探索エンドポイントは priority 1 件しか
 * 返さないため、複数候補は `resolveGuidance` の `Guidance.routes` から抽出する。
 * 各候補は独立した [ExtNavRoutePayload] として [ExtNavRouteRegistry] に保持する。
 */
class ExtNavRouteDataSource(
    private val clientProvider: ExtNavClientProvider,
    private val authGateway: ExtNavAuthGateway,
    private val registry: ExtNavRouteRegistry,
) : RouteDataSource {

    override suspend fun searchRoutes(
        originLatitude: Double,
        originLongitude: Double,
        destinationLatitude: Double,
        destinationLongitude: Double,
        intermediateWaypoints: List<Pair<Double, Double>>,
        originDirectionDegrees: Int?,
    ): Result<List<RouteResult>> = runCatching {
        authGateway.ensureSignedIn().getOrThrow()

        val client = clientProvider.get()
        val criteria = RouteSearchCriteria(
            start = Coord.fromDegrees(originLatitude, originLongitude),
            goal = Coord.fromDegrees(destinationLatitude, destinationLongitude),
            waypoints = intermediateWaypoints
                .map { (lat, lng) -> RouteWaypoint(coord = Coord.fromDegrees(lat, lng)) }
                .toImmutableList(),
            priorities = persistentSetOf(
                CarPriority.Recommended,
                CarPriority.AvoidCongestion,
                CarPriority.Express,
                CarPriority.Free,
            ),
            startDirection = originDirectionDegrees ?: RouteSearchCriteria.DIRECTION_UNSPECIFIED,
        )

        val (interchangeNameHints, guidanceResult) = coroutineScope {
            val guidanceDeferred = async { client.guidance.resolveGuidance(criteria) }
            val interchangeNameHintsDeferred = criteria.priorities.map { priority ->
                async {
                    priority to client.route
                        .search(criteria.copy(priorities = persistentSetOf(priority)))
                        .toInterchangeNameHint()
                }
            }

            val hints = interchangeNameHintsDeferred.awaitAll()
                .mapNotNull { (priority, hint) -> hint?.let { priority to it } }
                .toMap()
            hints to guidanceDeferred.await()
        }

        val guidance = guidanceResult.unwrap("guidance.resolveGuidance")
        if (guidance.routes.isEmpty()) {
            error("guidance.resolveGuidance returned no routes")
        }
        val sapaDetailsByName = fetchSapaDetailsByName(client, guidance.routes)

        val originPoint = RoutePoint(originLatitude, originLongitude)
        val destinationPoint = RoutePoint(destinationLatitude, destinationLongitude)
        val intermediates = intermediateWaypoints
            .map { (lat, lng) -> RoutePoint(lat, lng) }
            .toImmutableList()

        guidance.routes.map { routeGuidance ->
            val routeId = routeIdFor(routeGuidance)
            val geometry = buildGeometry(routeGuidance, originPoint, destinationPoint)
            val interchangeNameHint = routeGuidance.priority
                ?.let { priority -> interchangeNameHints[priority] }
            val roadClassSegments = buildRoadClassSegments(routeGuidance, geometry, interchangeNameHint)
            val congestionSegments = buildCongestionSegments(routeGuidance, geometry)
            val pointEvents = ExtNavRoutePointEventMapper.map(routeGuidance, geometry)
            val routeIncidents = ExtNavRouteIncidentMapper.map(routeGuidance, geometry)
            val tollYen = routeGuidance.summary.tollYen.takeIf { it > 0 }
            val distanceMetres = routeGuidance.summary.distanceMetres.toDouble()
            val timeSeconds = routeGuidance.summary.timeSeconds.toDouble()

            val routePriority = routePriorityFor(routeGuidance.priority)
            val tollDetails = routeGuidance.summary.tollDetails
                .map { detail -> TollSegmentFee(roadName = detail.road, amount = detail.amount) }
                .toImmutableList()
            val roadSegments = buildRoadSegmentDistances(routeGuidance)

            val routeDetail = RouteDetail(
                id = routeId,
                origin = originPoint,
                destination = destinationPoint,
                intermediateWaypoints = intermediates,
                geometry = geometry,
                distanceMeters = distanceMetres,
                durationSeconds = timeSeconds,
                steps = persistentListOf(),
                roadClassSegments = roadClassSegments,
                congestionSegments = congestionSegments,
                pointEvents = pointEvents,
                priority = routePriority,
                tollFee = tollYen,
                tollDetails = tollDetails,
                roadSegments = roadSegments,
                routeIncidents = routeIncidents,
            )

            registry.put(
                ExtNavRoutePayload(
                    id = routeId,
                    routeGuidance = routeGuidance,
                    sapaDetailsByName = sapaDetailsByName,
                ),
            )

            val item = RouteItem(
                durationSeconds = timeSeconds,
                distanceMeters = distanceMetres,
                geometry = geometry,
                viaRoadNames = persistentListOf(),
                hasTolls = tollYen != null,
                tollFee = tollYen,
                congestionSegments = congestionSegments,
                priorityLabel = routePriority?.label,
            )

            RouteResult(
                item = item,
                detail = routeDetail,
            )
        }
    }

    private fun routeIdFor(routeGuidance: RouteGuidance): String =
        routeGuidance.priority?.name ?: "route-${routeGuidance.index}"

    private fun buildGeometry(
        routeGuidance: RouteGuidance,
        originPoint: RoutePoint,
        destinationPoint: RoutePoint,
    ): ImmutableList<RoutePoint> {
        // ROUTE バイナリ由来の dense polyline を最優先で使う (74.4km に 960 点 ≒ 77m 間隔)。
        // ROUTE 欠落 / decode 失敗時のみ intersection 連結 (≒ 500m 間隔) にフォールバック。
        val dense = routeGuidance.polyline.map { coord ->
            RoutePoint(coord.latDegrees, coord.lonDegrees)
        }
        val raw = dense.ifEmpty {
            routeGuidance.intersections.map { intersection ->
                RoutePoint(intersection.position.latDegrees, intersection.position.lonDegrees)
            }
        }
        if (raw.isEmpty()) {
            return listOf(originPoint, destinationPoint).toImmutableList()
        }
        return buildList {
            if (raw.first() != originPoint) add(originPoint)
            addAll(raw)
            if (raw.last() != destinationPoint) add(destinationPoint)
        }.toImmutableList()
    }

    /**
     * [routeGuidance] の渋滞区間（外部ナビ API ライブラリが route バイナリから算出済み）を中立モデルに詰め替える。
     *
     * polyline index はライブラリ側で [RouteGuidance.polyline] に対して計算されている。OneNavi の
     * [geometry] は先頭に出発地を足している場合があるため、その分だけ index をずらしてから [geometry] の
     * 範囲にクランプする。座標マッチや測地系変換は行わない。
     */
    private fun buildCongestionSegments(
        routeGuidance: RouteGuidance,
        geometry: ImmutableList<RoutePoint>,
    ): ImmutableList<CongestionSegment> {
        if (routeGuidance.congestionSegments.isEmpty() || geometry.isEmpty()) {
            return persistentListOf()
        }

        val polyline = routeGuidance.polyline
        val originPrepended = polyline.isNotEmpty() && (geometry.first().latitude != polyline.first().latDegrees || geometry.first().longitude != polyline.first().lonDegrees)
        val indexOffset = if (originPrepended) 1 else 0

        return routeGuidance.congestionSegments
            .map { segment -> segment.toModel(indexOffset, geometry.lastIndex) }
            .toImmutableList()
    }

    private fun RouteCongestionSegment.toModel(
        indexOffset: Int,
        lastGeometryIndex: Int,
    ): CongestionSegment {
        val startIndex = (polylineStartIndex + indexOffset).coerceIn(0, lastGeometryIndex)
        val endIndex = (polylineEndIndex + indexOffset).coerceIn(startIndex, lastGeometryIndex)

        return CongestionSegment(
            startPolylinePointIndex = startIndex,
            endPolylinePointIndex = endIndex,
            severity = level.toSeverity(),
            startDistanceMeters = startDistanceFromRouteStartMetres.toDouble(),
            endDistanceMeters = endDistanceFromRouteStartMetres.toDouble(),
            congestionDistanceMeters = congestionDistanceMetres.toDouble(),
            transitMinutes = transitTimeSeconds?.let { seconds -> (seconds + SECONDS_PER_MINUTE / 2) / SECONDS_PER_MINUTE },
            trend = trend.toModel(),
            isIntermittent = isIntermittent,
            headPointName = headPointName?.ifBlank { null },
            headPointKana = headPointKana?.ifBlank { null },
            headRoadNumbering = headRoadNumbering?.ifBlank { null },
            tailPointName = tailPointName?.ifBlank { null },
            tailPointKana = tailPointKana?.ifBlank { null },
            tailRoadNumbering = tailRoadNumbering?.ifBlank { null },
            source = source.toModel(),
        )
    }

    private fun CongestionLevel.toSeverity(): CongestionSeverity = when (this) {
        CongestionLevel.Smooth -> CongestionSeverity.NORMAL
        CongestionLevel.Crowded -> CongestionSeverity.SLOW
        CongestionLevel.Jam -> CongestionSeverity.TRAFFIC_JAM
        CongestionLevel.Unknown -> CongestionSeverity.UNKNOWN
    }

    private fun ExtNavCongestionTrend.toModel(): CongestionTrend = when (this) {
        ExtNavCongestionTrend.Stable -> CongestionTrend.STABLE
        ExtNavCongestionTrend.Increasing, ExtNavCongestionTrend.PartlyIncreasing -> CongestionTrend.INCREASING
        ExtNavCongestionTrend.Decreasing, ExtNavCongestionTrend.PartlyDecreasing -> CongestionTrend.DECREASING
        ExtNavCongestionTrend.Intermittent -> CongestionTrend.INTERMITTENT
        ExtNavCongestionTrend.Unknown -> CongestionTrend.UNKNOWN
    }

    private fun ExtNavRouteCongestionSource.toModel(): CongestionSegmentSource = when (this) {
        ExtNavRouteCongestionSource.Guide -> CongestionSegmentSource.GUIDANCE_POINT
        ExtNavRouteCongestionSource.RouteLink -> CongestionSegmentSource.ROUTE_LINK
    }

    /**
     * [geometry] を道路種別（高速 / 一般道）ごとの連続区間に分割する。
     *
     * 道路種別そのものは経路サマリの [StreetSegment.highway] を正とする。境界位置は
     * サマリ距離を単純に全長按分せず、道路名一致で作った距離アンカー、入口 GP、路線記号を
     * 順に使って polyline 上の index に落とす。
     */
    private fun buildRoadClassSegments(
        routeGuidance: RouteGuidance,
        geometry: ImmutableList<RoutePoint>,
        interchangeNameHint: InterchangeNameHint?,
    ): ImmutableList<RoadClassSegment> {
        if (geometry.size < 2) {
            return persistentListOf()
        }

        val orderedStreets = routeGuidance.summary.streets
            .sortedBy { it.sequence }
            .filter { it.distanceMetres > 0 }
        val totalStreetMetres = orderedStreets.sumOf { it.distanceMetres }.toDouble()
        if (orderedStreets.isEmpty() || totalStreetMetres <= 0.0) {
            return persistentListOf()
        }

        val roadClassStretches = buildRoadClassStretches(orderedStreets)
        val cumulativeMetres = buildCumulativeGeometryMetres(geometry)
        val totalGeometryMetres = cumulativeMetres.last()

        if (totalGeometryMetres <= 0.0) {
            return persistentListOf()
        }

        val snappedIntersections = buildSnappedIntersections(
            intersections = routeGuidance.intersections,
            geometry = geometry,
            cumulativeMetres = cumulativeMetres,
        )

        val distanceAnchors = buildDistanceAnchors(
            streets = orderedStreets,
            intersections = snappedIntersections,
            totalStreetMetres = totalStreetMetres,
            totalGeometryMetres = totalGeometryMetres,
        )

        val distanceMapper = RouteDistanceMapper(distanceAnchors)

        val entryEventMetres = buildExpresswayEntryEventMetres(
            routeGuidance = routeGuidance,
            totalGeometryMetres = totalGeometryMetres,
            intersections = snappedIntersections,
        ).toMutableList()

        val boundaryEstimates = roadClassStretches
            .dropLast(1)
            .map { stretch -> distanceMapper.mapSourceToGeometry(stretch.endMetres) }

        var previousBoundaryIndex = 0
        val boundaryIndices = roadClassStretches.dropLast(1).mapIndexed { stretchIndex, stretch ->
            val nextStretch = roadClassStretches[stretchIndex + 1]
            val estimatedMetres = boundaryEstimates[stretchIndex]
            val previousBoundaryMetres = cumulativeMetres[previousBoundaryIndex]
            val nextBoundaryEstimateMetres = boundaryEstimates.getOrNull(stretchIndex + 1) ?: totalGeometryMetres
            val refinedMetres = chooseBoundaryGeometryMetres(
                sourceMetres = stretch.endMetres,
                estimatedMetres = estimatedMetres,
                fromClass = stretch.roadClass,
                toClass = nextStretch.roadClass,
                fromRoadNames = stretch.roadNames,
                previousBoundaryMetres = previousBoundaryMetres,
                nextBoundaryEstimateMetres = nextBoundaryEstimateMetres,
                distanceAnchors = distanceAnchors,
                entryEventMetres = entryEventMetres,
                intersections = snappedIntersections,
            )
            val refinedIndex = closestGeometryIndex(
                cumulativeMetres = cumulativeMetres,
                targetMetres = refinedMetres,
            )
            val monotonicIndex = refinedIndex
                .coerceAtLeast(previousBoundaryIndex + 1)
                .coerceAtMost(geometry.lastIndex)
            previousBoundaryIndex = monotonicIndex
            monotonicIndex
        }

        val segments = mutableListOf<RoadClassSegment>()
        var startIndex = 0

        val lastStretchIndex = roadClassStretches.lastIndex
        val firstHighwayStretchIndex = roadClassStretches
            .indexOfFirst { stretch -> stretch.roadClass == RoadClass.HIGHWAY }
        val lastHighwayStretchIndex = roadClassStretches
            .indexOfLast { stretch -> stretch.roadClass == RoadClass.HIGHWAY }
        for ((stretchIndex, stretch) in roadClassStretches.withIndex()) {
            val endIndex = boundaryIndices.getOrNull(stretchIndex) ?: geometry.lastIndex
            if (endIndex > startIndex) {
                // 出発地 / 目的地に紐付くセグメント端は IC ではないので null。
                // 一方、ルート末尾の ORDINARY が極短いと boundary が geometry.lastIndex まで丸められて
                // HIGHWAY セグメントの endIndex == lastIndex になりうるが、それは「最終 stretch」ではないので
                // 出口 IC として扱う必要がある。geometry index ではなく stretch の位置で判定する。
                val entryName = if (stretchIndex == 0) {
                    null
                } else {
                    findBoundaryInterchangeName(
                        geometryIndex = startIndex,
                        cumulativeMetres = cumulativeMetres,
                        intersections = routeGuidance.intersections,
                        snappedIntersections = snappedIntersections,
                    )
                }
                val exitName = if (stretchIndex == lastStretchIndex) {
                    null
                } else {
                    findBoundaryInterchangeName(
                        geometryIndex = endIndex,
                        cumulativeMetres = cumulativeMetres,
                        intersections = routeGuidance.intersections,
                        snappedIntersections = snappedIntersections,
                    )
                }
                segments.add(
                    RoadClassSegment(
                        startPointIndex = startIndex,
                        endPointIndex = endIndex,
                        roadClass = stretch.roadClass,
                        entryInterchangeName = if (stretchIndex == firstHighwayStretchIndex) {
                            interchangeNameHint?.entryName ?: entryName
                        } else {
                            entryName
                        },
                        exitInterchangeName = if (stretchIndex == lastHighwayStretchIndex) {
                            interchangeNameHint?.exitName ?: exitName
                        } else {
                            exitName
                        },
                    ),
                )
                startIndex = endIndex
            }
        }
        return segments.toImmutableList()
    }

    private fun ApiResult<ImmutableList<Route>>.toInterchangeNameHint(): InterchangeNameHint? = when (this) {
        is ApiResult.Success -> value.firstOrNull()?.toInterchangeNameHint()
        is ApiResult.Failure -> null
    }

    private fun Route.toInterchangeNameHint(): InterchangeNameHint? {
        if (fareSegments.isEmpty()) return null

        val entryName = fareSegments
            .asSequence()
            .mapNotNull { segment -> segment.from.name.toInterchangeNameOrNull() }
            .firstOrNull()
        val exitName = fareSegments
            .asReversed()
            .asSequence()
            .mapNotNull { segment -> segment.to.name.toInterchangeNameOrNull() }
            .firstOrNull()

        return if (entryName == null && exitName == null) {
            null
        } else {
            InterchangeNameHint(entryName = entryName, exitName = exitName)
        }
    }

    private fun String.toInterchangeNameOrNull(): String? =
        trim()
            .takeIf { name -> name.isNotEmpty() }
            ?.takeUnless { name ->
                name.equals("start", ignoreCase = true) ||
                    name.equals("goal", ignoreCase = true)
            }

    /**
     * [geometryIndex] に対応する地点付近で「名前を持つ最も近い交差点」の名前を返す。
     *
     * 推定境界の geometry index に最近傍の intersection が「目的地」「無名の分岐点」等で name が空の
     * 場合があるため、許容距離以内で name が非空の中から最近傍を選ぶ。許容距離内に name 付きが
     * 1 件も無ければ null。
     */
    private fun findBoundaryInterchangeName(
        geometryIndex: Int,
        cumulativeMetres: DoubleArray,
        intersections: ImmutableList<Intersection>,
        snappedIntersections: List<SnappedIntersection>,
    ): String? {
        if (snappedIntersections.isEmpty()) return null
        val targetMetres = cumulativeMetres[geometryIndex]
        return snappedIntersections
            .asSequence()
            .filter { snapped -> abs(snapped.geometryMetres - targetMetres) <= INTERCHANGE_SNAP_TOLERANCE_METRES }
            .sortedBy { snapped -> abs(snapped.geometryMetres - targetMetres) }
            .mapNotNull { snapped ->
                intersections.getOrNull(snapped.intersectionIndex)
                    ?.name
                    ?.trim()
                    ?.takeIf { name -> name.isNotEmpty() }
            }
            .firstOrNull()
    }

    private fun buildRoadClassStretches(streets: List<StreetSegment>): List<RoadClassStretch> {
        val stretches = mutableListOf<RoadClassStretch>()
        var accumulatedMetres = 0.0

        for (street in streets) {
            val startMetres = accumulatedMetres
            val endMetres = startMetres + street.distanceMetres
            val roadClass = street.toRoadClass()
            val roadNames = street.roadIdentityNames()
            val lastStretch = stretches.lastOrNull()

            if (lastStretch != null && lastStretch.roadClass == roadClass) {
                stretches[stretches.lastIndex] = lastStretch.copy(
                    endMetres = endMetres,
                    roadNames = (lastStretch.roadNames + roadNames).toImmutableSet(),
                )
            } else {
                stretches += RoadClassStretch(
                    startMetres = startMetres,
                    endMetres = endMetres,
                    roadClass = roadClass,
                    roadNames = roadNames,
                )
            }
            accumulatedMetres = endMetres
        }
        return stretches
    }

    private fun buildCumulativeGeometryMetres(geometry: ImmutableList<RoutePoint>): DoubleArray {
        val cumulativeMetres = DoubleArray(geometry.size)
        for (index in 1 until geometry.size) {
            cumulativeMetres[index] = cumulativeMetres[index - 1] + haversineMetres(geometry[index - 1], geometry[index])
        }
        return cumulativeMetres
    }

    private fun buildSnappedIntersections(
        intersections: ImmutableList<Intersection>,
        geometry: ImmutableList<RoutePoint>,
        cumulativeMetres: DoubleArray,
    ): List<SnappedIntersection> = intersections
        .mapIndexed { intersectionIndex, intersection ->
            val geometryIndex = nearestGeometryIndex(
                geometry = geometry,
                latitude = intersection.position.latDegrees,
                longitude = intersection.position.lonDegrees,
            )
            SnappedIntersection(
                intersectionIndex = intersectionIndex,
                geometryMetres = cumulativeMetres[geometryIndex],
                roadNames = intersection.roadIdentityNames(),
                hasHighwaySign = intersection.roadNumberSign.isNotBlank(),
            )
        }
        .sortedWith(compareBy<SnappedIntersection> { it.geometryMetres }.thenBy { it.intersectionIndex })

    private fun buildDistanceAnchors(
        streets: List<StreetSegment>,
        intersections: List<SnappedIntersection>,
        totalStreetMetres: Double,
        totalGeometryMetres: Double,
    ): List<DistanceAnchor> {
        val namedStretches = buildNamedStreetStretches(streets)
        val anchors = mutableListOf(
            DistanceAnchor(sourceMetres = 0.0, geometryMetres = 0.0),
            DistanceAnchor(sourceMetres = totalStreetMetres, geometryMetres = totalGeometryMetres),
        )
        var lastMatchedStretchIndex = -1

        for (intersection in intersections) {
            if (intersection.roadNames.isEmpty()) continue

            val matchedStretchIndex = findMatchingNamedStreetStretchIndex(
                namedStretches = namedStretches,
                roadNames = intersection.roadNames,
                lastMatchedStretchIndex = lastMatchedStretchIndex,
            ) ?: continue

            if (matchedStretchIndex != lastMatchedStretchIndex) {
                val matchedStretch = namedStretches[matchedStretchIndex]
                if (matchedStretch.startMetres > ANCHOR_SOURCE_TOLERANCE_METRES && matchedStretch.startMetres < totalStreetMetres - ANCHOR_SOURCE_TOLERANCE_METRES) {
                    anchors += DistanceAnchor(
                        sourceMetres = matchedStretch.startMetres,
                        geometryMetres = intersection.geometryMetres,
                    )
                }
            }
            lastMatchedStretchIndex = max(lastMatchedStretchIndex, matchedStretchIndex)
        }
        return anchors
            .distinctBy { it.sourceMetres }
            .sortedBy { it.sourceMetres }
            .filterMonotonicDistanceAnchors()
    }

    private fun buildNamedStreetStretches(streets: List<StreetSegment>): List<NamedStreetStretch> {
        val stretches = mutableListOf<NamedStreetStretch>()
        var accumulatedMetres = 0.0

        for (street in streets) {
            val startMetres = accumulatedMetres
            val endMetres = startMetres + street.distanceMetres
            val roadNames = street.roadIdentityNames()
            val roadClass = street.toRoadClass()
            val lastStretch = stretches.lastOrNull()

            if (lastStretch != null && lastStretch.roadClass == roadClass && lastStretch.roadNames.hasAnyNameIn(roadNames)) {
                stretches[stretches.lastIndex] = lastStretch.copy(
                    endMetres = endMetres,
                    roadNames = (lastStretch.roadNames + roadNames).toImmutableSet(),
                )
            } else if (roadNames.isNotEmpty()) {
                stretches += NamedStreetStretch(
                    startMetres = startMetres,
                    endMetres = endMetres,
                    roadClass = roadClass,
                    roadNames = roadNames,
                )
            }

            accumulatedMetres = endMetres
        }
        return stretches
    }

    private fun findMatchingNamedStreetStretchIndex(
        namedStretches: List<NamedStreetStretch>,
        roadNames: ImmutableSet<String>,
        lastMatchedStretchIndex: Int,
    ): Int? {
        if (lastMatchedStretchIndex >= 0 && namedStretches.getOrNull(lastMatchedStretchIndex)?.roadNames?.hasAnyNameIn(roadNames) == true) {
            return lastMatchedStretchIndex
        }

        val searchStartIndex = (lastMatchedStretchIndex + 1).coerceAtLeast(0)

        for (streetIndex in searchStartIndex until namedStretches.size) {
            if (namedStretches[streetIndex].roadNames.hasAnyNameIn(roadNames)) {
                return streetIndex
            }
        }
        return null
    }

    private fun List<DistanceAnchor>.filterMonotonicDistanceAnchors(): List<DistanceAnchor> {
        val filteredAnchors = mutableListOf<DistanceAnchor>()

        for (anchor in this) {
            val previousAnchor = filteredAnchors.lastOrNull()

            if (previousAnchor == null || anchor.sourceMetres > previousAnchor.sourceMetres + ANCHOR_SOURCE_TOLERANCE_METRES && anchor.geometryMetres > previousAnchor.geometryMetres + ANCHOR_GEOMETRY_TOLERANCE_METRES) {
                filteredAnchors += anchor
            }
        }
        return filteredAnchors
    }

    private fun buildExpresswayEntryEventMetres(
        routeGuidance: RouteGuidance,
        totalGeometryMetres: Double,
        intersections: List<SnappedIntersection>,
    ): List<Double> {
        val guidanceTotalMetres = routeGuidance.summary.distanceMetres
            .takeIf { it > 0 }
            ?.toDouble()
            ?: return emptyList()

        return routeGuidance.guidancePoints
            .asSequence()
            .filter { guidancePoint ->
                guidancePoint.phrases.any { phrase -> phrase.category == GuidanceCategory.AutoExpresswayEntry }
            }
            .map { guidancePoint ->
                val eventMetres = guidancePoint.distanceFromStartMetres
                    .toDouble()
                    .coerceIn(0.0, guidanceTotalMetres) / guidanceTotalMetres * totalGeometryMetres
                intersections
                    .filter { intersection ->
                        intersection.hasHighwaySign &&
                            abs(intersection.geometryMetres - eventMetres) <= ENTRY_EVENT_SNAP_TOLERANCE_METRES
                    }
                    .minByOrNull { intersection -> abs(intersection.geometryMetres - eventMetres) }
                    ?.geometryMetres
                    ?: eventMetres
            }
            .sorted()
            .toList()
    }

    private fun chooseBoundaryGeometryMetres(
        sourceMetres: Double,
        estimatedMetres: Double,
        fromClass: RoadClass,
        toClass: RoadClass,
        fromRoadNames: ImmutableSet<String>,
        previousBoundaryMetres: Double,
        nextBoundaryEstimateMetres: Double,
        distanceAnchors: List<DistanceAnchor>,
        entryEventMetres: MutableList<Double>,
        intersections: List<SnappedIntersection>,
    ): Double {
        if (fromClass == RoadClass.ORDINARY && toClass == RoadClass.HIGHWAY) {
            consumeNearestEntryEvent(
                entryEventMetres = entryEventMetres,
                estimatedMetres = estimatedMetres,
                previousBoundaryMetres = previousBoundaryMetres,
                nextBoundaryEstimateMetres = nextBoundaryEstimateMetres,
            )?.let { return it }
        }

        if (fromClass == RoadClass.HIGHWAY && toClass == RoadClass.ORDINARY) {
            // 降りる IC は高速側の道路名を持つので、その名前を持つ最後の交差点 = 出口 IC とみなす。
            // 路線記号や全長按分の推定よりこちらが優先。出口 IC を過ぎても一般道が青のままになるのを防ぐ。
            // ただし推定境界から大きく離れた JCT（経路途中で別の高速へ乗り換える地点など）まで遡らないよう、
            // 探索半径内に収まる交差点に限定する。
            findHighwayExitMetres(
                highwayRoadNames = fromRoadNames,
                estimatedMetres = estimatedMetres,
                previousBoundaryMetres = previousBoundaryMetres,
                nextBoundaryEstimateMetres = nextBoundaryEstimateMetres,
                intersections = intersections,
            )?.let { return it }
        }

        findAnchorAtBoundary(
            sourceMetres = sourceMetres,
            estimatedMetres = estimatedMetres,
            previousBoundaryMetres = previousBoundaryMetres,
            nextBoundaryEstimateMetres = nextBoundaryEstimateMetres,
            anchors = distanceAnchors,
        )?.let { return it }

        findRoadSignBoundary(
            toClass = toClass,
            estimatedMetres = estimatedMetres,
            previousBoundaryMetres = previousBoundaryMetres,
            nextBoundaryEstimateMetres = nextBoundaryEstimateMetres,
            intersections = intersections,
        )?.let { return it }

        return estimatedMetres
    }

    private fun findHighwayExitMetres(
        highwayRoadNames: ImmutableSet<String>,
        estimatedMetres: Double,
        previousBoundaryMetres: Double,
        nextBoundaryEstimateMetres: Double,
        intersections: List<SnappedIntersection>,
    ): Double? {
        if (highwayRoadNames.isEmpty()) return null
        val searchRadiusMetres = boundarySearchRadius(
            previousBoundaryMetres = previousBoundaryMetres,
            nextBoundaryEstimateMetres = nextBoundaryEstimateMetres,
        )
        return intersections
            .asSequence()
            .filter { intersection -> intersection.geometryMetres > previousBoundaryMetres }
            .filter { intersection -> intersection.geometryMetres < nextBoundaryEstimateMetres }
            .filter { intersection -> abs(intersection.geometryMetres - estimatedMetres) <= searchRadiusMetres }
            .filter { intersection -> intersection.roadNames.hasAnyNameIn(highwayRoadNames) }
            .maxByOrNull { intersection -> intersection.geometryMetres }
            ?.geometryMetres
    }

    private fun consumeNearestEntryEvent(
        entryEventMetres: MutableList<Double>,
        estimatedMetres: Double,
        previousBoundaryMetres: Double,
        nextBoundaryEstimateMetres: Double,
    ): Double? {
        val searchRadiusMetres = boundarySearchRadius(
            previousBoundaryMetres = previousBoundaryMetres,
            nextBoundaryEstimateMetres = nextBoundaryEstimateMetres,
        )
        val selectedEntry = entryEventMetres
            .withIndex()
            .filter { indexedEvent ->
                val eventMetres = indexedEvent.value
                eventMetres > previousBoundaryMetres &&
                    eventMetres < nextBoundaryEstimateMetres &&
                    abs(eventMetres - estimatedMetres) <= searchRadiusMetres
            }
            .minByOrNull { indexedEvent -> abs(indexedEvent.value - estimatedMetres) }
            ?: return null
        entryEventMetres.removeAt(selectedEntry.index)
        return selectedEntry.value
    }

    private fun findAnchorAtBoundary(
        sourceMetres: Double,
        estimatedMetres: Double,
        previousBoundaryMetres: Double,
        nextBoundaryEstimateMetres: Double,
        anchors: List<DistanceAnchor>,
    ): Double? = anchors
        .asSequence()
        .filter { anchor -> abs(anchor.sourceMetres - sourceMetres) <= ANCHOR_SOURCE_TOLERANCE_METRES }
        .filter { anchor -> anchor.geometryMetres > previousBoundaryMetres }
        .filter { anchor -> anchor.geometryMetres < nextBoundaryEstimateMetres }
        .minByOrNull { anchor -> abs(anchor.geometryMetres - estimatedMetres) }
        ?.geometryMetres

    private fun findRoadSignBoundary(
        toClass: RoadClass,
        estimatedMetres: Double,
        previousBoundaryMetres: Double,
        nextBoundaryEstimateMetres: Double,
        intersections: List<SnappedIntersection>,
    ): Double? {
        val searchRadiusMetres = boundarySearchRadius(
            previousBoundaryMetres = previousBoundaryMetres,
            nextBoundaryEstimateMetres = nextBoundaryEstimateMetres,
        )
        return intersections
            .asSequence()
            .filter { intersection -> intersection.geometryMetres > previousBoundaryMetres }
            .filter { intersection -> intersection.geometryMetres < nextBoundaryEstimateMetres }
            .filter { intersection -> intersection.signalRoadClass == toClass }
            .filter { intersection -> abs(intersection.geometryMetres - estimatedMetres) <= searchRadiusMetres }
            .minByOrNull { intersection -> abs(intersection.geometryMetres - estimatedMetres) }
            ?.geometryMetres
    }

    private fun boundarySearchRadius(
        previousBoundaryMetres: Double,
        nextBoundaryEstimateMetres: Double,
    ): Double {
        val intervalMetres = (nextBoundaryEstimateMetres - previousBoundaryMetres).coerceAtLeast(0.0)
        return (intervalMetres * BOUNDARY_SEARCH_INTERVAL_RATIO)
            .coerceIn(MIN_BOUNDARY_SEARCH_RADIUS_METRES, MAX_BOUNDARY_SEARCH_RADIUS_METRES)
    }

    private fun closestGeometryIndex(
        cumulativeMetres: DoubleArray,
        targetMetres: Double,
    ): Int {
        if (targetMetres <= 0.0) return 0
        if (targetMetres >= cumulativeMetres.last()) return cumulativeMetres.lastIndex

        var lowIndex = 0
        var highIndex = cumulativeMetres.lastIndex
        while (lowIndex < highIndex) {
            val middleIndex = (lowIndex + highIndex) / 2
            if (cumulativeMetres[middleIndex] < targetMetres) {
                lowIndex = middleIndex + 1
            } else {
                highIndex = middleIndex
            }
        }

        val upperIndex = lowIndex
        val lowerIndex = (upperIndex - 1).coerceAtLeast(0)
        return if (abs(cumulativeMetres[lowerIndex] - targetMetres) <= abs(cumulativeMetres[upperIndex] - targetMetres)) {
            lowerIndex
        } else {
            upperIndex
        }
    }

    /** [latitude], [longitude] に最も近い [geometry] の点の index を返す。 */
    private fun nearestGeometryIndex(
        geometry: ImmutableList<RoutePoint>,
        latitude: Double,
        longitude: Double,
    ): Int {
        // 最近傍判定なので測地線距離は不要。経度を緯度に応じて縮めた擬似平面距離の二乗で十分。
        val longitudeScale = cos(Math.toRadians(latitude))
        var bestIndex = 0
        var bestSquaredDistance = Double.MAX_VALUE
        for (index in geometry.indices) {
            val point = geometry[index]
            val deltaLatitude = point.latitude - latitude
            val deltaLongitude = (point.longitude - longitude) * longitudeScale
            val squaredDistance = deltaLatitude * deltaLatitude + deltaLongitude * deltaLongitude
            if (squaredDistance < bestSquaredDistance) {
                bestSquaredDistance = squaredDistance
                bestIndex = index
            }
        }
        return bestIndex
    }

    private fun haversineMetres(from: RoutePoint, to: RoutePoint): Double {
        val earthRadiusMetres = 6_371_000.0
        val fromLatRadians = Math.toRadians(from.latitude)
        val toLatRadians = Math.toRadians(to.latitude)
        val deltaLatRadians = Math.toRadians(to.latitude - from.latitude)
        val deltaLngRadians = Math.toRadians(to.longitude - from.longitude)
        val haversineTerm = sin(deltaLatRadians / 2.0) * sin(deltaLatRadians / 2.0) +
            cos(fromLatRadians) * cos(toLatRadians) * sin(deltaLngRadians / 2.0) * sin(deltaLngRadians / 2.0)
        return earthRadiusMetres * 2.0 * atan2(sqrt(haversineTerm), sqrt(1.0 - haversineTerm))
    }

    private fun StreetSegment.toRoadClass(): RoadClass =
        if (highway) RoadClass.HIGHWAY else RoadClass.ORDINARY

    private fun StreetSegment.roadIdentityNames(): ImmutableSet<String> =
        listOfNotNull(officialName, nickname)
            .map { name -> name.trim() }
            .filter { name -> name.isNotEmpty() }
            .toImmutableSet()

    private fun Intersection.roadIdentityNames(): ImmutableSet<String> =
        listOf(roadNameOfficial, roadName)
            .map { name -> name.trim() }
            .filter { name -> name.isNotEmpty() }
            .toImmutableSet()

    private fun ImmutableSet<String>.hasAnyNameIn(other: ImmutableSet<String>): Boolean =
        any { name -> name in other }

    /**
     * 経路サマリ由来の通過道路一覧を中立モデルに射影する。
     * 道路名は `officialName` を優先、無ければ `nickname` を使う。匿名 / 距離 0 の区間はスキップ。
     */
    private fun buildRoadSegmentDistances(routeGuidance: RouteGuidance): ImmutableList<RoadSegmentDistance> =
        routeGuidance.summary.streets
            .mapNotNull { street ->
                val roadName = (street.officialName ?: street.nickname)
                    ?.trim()
                    ?.takeIf { name -> name.isNotEmpty() }
                if (roadName == null || street.distanceMetres <= 0) {
                    null
                } else {
                    RoadSegmentDistance(
                        roadName = roadName,
                        distanceMeters = street.distanceMetres,
                    )
                }
            }
            .toImmutableList()

    private fun routePriorityFor(priority: CarPriority?): RoutePriority? = when (priority) {
        CarPriority.Recommended -> RoutePriority.Recommended
        CarPriority.AiRoute -> RoutePriority.AiRoute
        CarPriority.Free -> RoutePriority.Free
        CarPriority.Express -> RoutePriority.Express
        CarPriority.Distance -> RoutePriority.Distance
        CarPriority.AvoidCongestion -> RoutePriority.AvoidCongestion
        CarPriority.EcoPriority -> RoutePriority.EcoPriority
        CarPriority.Scenic -> RoutePriority.Scenic
        CarPriority.FreeDistance -> RoutePriority.FreeDistance
        CarPriority.UrbanExpress -> RoutePriority.UrbanExpress
        CarPriority.AvoidUrbanExpress -> RoutePriority.AvoidUrbanExpress
        CarPriority.SecondRecommended -> RoutePriority.SecondRecommended
        null -> null
    }

    /** ルート候補群に含まれる SA/PA 詳細をまとめて取得する。失敗時は空で返す。 */
    private suspend fun fetchSapaDetailsByName(
        client: DriveSupporterClient,
        routeGuidances: ImmutableList<RouteGuidance>,
    ): ImmutableMap<String, SapaDetail> {
        val targets = routeGuidances
            .flatMap(::collectSapaLookupTargets)
            .distinctBy { target -> target.distinctKey }
            .toImmutableList()
        if (targets.isEmpty()) return persistentMapOf()

        val searchMatches = coroutineScope {
            targets
                .map { target ->
                    async {
                        val searchResult = searchSapaNearTarget(client, target)
                        SapaSearchMatch(target = target, searchResult = searchResult)
                    }
                }
                .awaitAll()
        }
            .filter { match -> match.searchResult != null }

        val sapaIds = searchMatches
            .mapNotNull { match -> match.searchResult?.id }
            .distinctBy { sapaId -> sapaId.value }
            .toImmutableList()
        if (sapaIds.isEmpty()) return persistentMapOf()

        val detailsById = when (val result = client.sapa.fetchDetails(sapaIds, true)) {
            is ApiResult.Success -> {
                result.value
                    .associateBy { detail -> detail.id }
            }

            is ApiResult.Failure -> persistentMapOf()
        }

        val detailsByName = mutableMapOf<String, SapaDetail>()
        for (match in searchMatches) {
            val searchResult = match.searchResult ?: continue
            val detail = detailsById[searchResult.id] ?: continue
            detailsByName[ExtNavSapaNameNormalizer.normalize(match.target.name)] = detail
            detailsByName[ExtNavSapaNameNormalizer.normalize(searchResult.name)] = detail
        }

        return detailsByName.toImmutableMap()
    }

    private fun collectSapaLookupTargets(routeGuidance: RouteGuidance): List<SapaLookupTarget> =
        routeGuidance.intersections.mapNotNull(::toSapaLookupTargetOrNull)

    private fun toSapaLookupTargetOrNull(intersection: Intersection): SapaLookupTarget? {
        val facilityKind = intersection.facilityHint?.kind
        if (facilityKind != GuidanceFacilityKind.PARKING_AREA) return null

        val normalizedName = ExtNavSapaNameNormalizer.normalize(intersection.name)
        if (normalizedName.isBlank()) return null

        return SapaLookupTarget(
            name = intersection.name,
            coord = intersection.position,
        )
    }

    private suspend fun searchSapaNearTarget(
        client: DriveSupporterClient,
        target: SapaLookupTarget,
    ): SapaSearchResult? {
        val result = client.sapa.searchNear(
            coord = target.coord,
            radiusMeters = SAPA_SEARCH_RADIUS_METRES,
        )

        return when (result) {
            is ApiResult.Success -> result.value.bestSapaSearchMatchFor(target.name)
            is ApiResult.Failure -> null
        }
    }

    /** SA/PA 詳細検索対象。 */
    @Immutable
    private data class SapaLookupTarget(
        val name: String,
        val coord: Coord,
    ) {
        val distinctKey: String
            get() = "${ExtNavSapaNameNormalizer.normalize(name)}:${coord.latMsec}:${coord.lonMsec}"
    }

    /** SA/PA 詳細検索対象と検索結果の組。 */
    @Immutable
    private data class SapaSearchMatch(
        val target: SapaLookupTarget,
        val searchResult: SapaSearchResult?,
    )

    private fun <T> ApiResult<T>.unwrap(hint: String): T = when (this) {
        is ApiResult.Success -> value
        is ApiResult.Failure -> error("$hint failed: $failure")
    }

    /** 道路種別セグメント推定で使う距離しきい値ほか。 */
    private companion object {
        /** SA/PA spot 近傍検索の半径。 */
        private const val SAPA_SEARCH_RADIUS_METRES: Int = 5_000

        private const val ANCHOR_SOURCE_TOLERANCE_METRES: Double = 1.0
        private const val ANCHOR_GEOMETRY_TOLERANCE_METRES: Double = 1.0
        private const val ENTRY_EVENT_SNAP_TOLERANCE_METRES: Double = 600.0
        private const val BOUNDARY_SEARCH_INTERVAL_RATIO: Double = 0.35
        private const val MIN_BOUNDARY_SEARCH_RADIUS_METRES: Double = 750.0
        private const val MAX_BOUNDARY_SEARCH_RADIUS_METRES: Double = 5_000.0
        private const val INTERCHANGE_SNAP_TOLERANCE_METRES: Double = 1_000.0
        private const val SECONDS_PER_MINUTE: Int = 60
    }
}

/**
 * サマリ距離上で同じ道路種別が続く範囲。
 *
 * @param roadNames この範囲を構成する StreetSegment の道路名（出口 IC の同定に使う）。
 */
@Immutable
private data class RoadClassStretch(
    val startMetres: Double,
    val endMetres: Double,
    val roadClass: RoadClass,
    val roadNames: ImmutableSet<String>,
)

/**
 * 道路名で交差点と対応付けできるサマリ距離上の範囲。
 */
@Immutable
private data class NamedStreetStretch(
    val startMetres: Double,
    val endMetres: Double,
    val roadClass: RoadClass,
    val roadNames: ImmutableSet<String>,
)

/**
 * polyline に射影済みの交差点。
 */
@Immutable
private data class SnappedIntersection(
    val intersectionIndex: Int,
    val geometryMetres: Double,
    val roadNames: ImmutableSet<String>,
    val hasHighwaySign: Boolean,
) {
    val signalRoadClass: RoadClass
        get() = if (hasHighwaySign) RoadClass.HIGHWAY else RoadClass.ORDINARY
}

/**
 * 料金区間の入口 / 出口としてサーバが返す地点名。
 */
@Immutable
private data class InterchangeNameHint(
    val entryName: String?,
    val exitName: String?,
)

/**
 * ExtNav 由来のルート 1 本分のペイロード。`Guidance.routes` の 1 要素に対応する。
 * セッション管理層が [ExtNavRouteRegistry] 経由で取得する。
 *
 * @property id OneNavi 内で扱う route ID。
 * @property routeGuidance 外部ナビ API ライブラリ由来のルート案内。
 * @property sapaDetailsByName SA/PA 詳細設備。正規化した SA/PA 名を key にし、取得できない場合は空。
 */
@Immutable
data class ExtNavRoutePayload(
    val id: String,
    val routeGuidance: RouteGuidance,
    val sapaDetailsByName: ImmutableMap<String, SapaDetail> = persistentMapOf(),
)

/** SA/PA 検索結果から対象名に一致する候補だけを選ぶ。 */
internal fun ImmutableList<SapaSearchResult>.bestSapaSearchMatchFor(targetName: String): SapaSearchResult? =
    firstOrNull { result -> ExtNavSapaNameNormalizer.matches(result.name, targetName) }
