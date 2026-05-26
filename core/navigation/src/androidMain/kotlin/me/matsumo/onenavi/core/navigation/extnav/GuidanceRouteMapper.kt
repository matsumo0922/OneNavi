package me.matsumo.onenavi.core.navigation.extnav

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import me.matsumo.onenavi.core.model.ManeuverType
import me.matsumo.onenavi.core.model.RouteDetail
import me.matsumo.onenavi.core.model.RoutePoint
import me.matsumo.onenavi.core.navigation.newguidance.semantic.GuidanceEvent
import me.matsumo.onenavi.core.navigation.newguidance.semantic.GuidanceEventDetails
import me.matsumo.onenavi.core.navigation.newguidance.semantic.GuidanceEventId
import me.matsumo.onenavi.core.navigation.newguidance.semantic.GuidanceLane
import me.matsumo.onenavi.core.navigation.newguidance.semantic.GuidanceManeuver
import me.matsumo.onenavi.core.navigation.newguidance.semantic.GuidanceRoute
import me.matsumo.onenavi.core.navigation.newguidance.semantic.LaneConfidence
import me.matsumo.onenavi.core.navigation.newguidance.semantic.LaneLayout
import me.matsumo.onenavi.core.navigation.newguidance.semantic.LaneMark
import me.matsumo.onenavi.core.navigation.newguidance.semantic.LaneSource
import me.matsumo.onenavi.core.navigation.newguidance.semantic.RouteAnchor
import me.matsumo.onenavi.core.navigation.newguidance.semantic.SourceRef
import kotlin.math.abs
import kotlin.math.roundToInt
import me.matsumo.drive.supporter.api.guidance.domain.GuidancePoint as ExtNavGuidancePoint
import me.matsumo.drive.supporter.api.guidance.domain.Intersection as ExtNavIntersection
import me.matsumo.drive.supporter.api.guidance.domain.LaneInfo as ExtNavLaneInfo

/**
 * 外部ナビ API ライブラリ由来の payload を、位置非依存の semantic 案内ルート
 * ([GuidanceRoute]) へ射影する mapper。
 *
 * attach 時に 1 回だけ呼ばれることを想定する。tick ごとの進捗計算は持たない。
 * 距離・方位・座標は共通の [RouteGeometryMath] を、maneuver 分類は [ManeuverClassifier]
 * を使う。
 *
 * 現状は骨組み実装で、各イベントの主案内 ([GuidanceManeuver]) とレーン ([GuidanceLane])
 * のみを埋める。施設 / 看板 / 料金 / 境界 / 道路名 / 通知は後続で [GuidanceEventDetails]
 * に追加する。
 */
internal class GuidanceRouteMapper {

    /**
     * payload と中立 route から [GuidanceRoute] を構築する。
     *
     * @param payload guidancePoints / intersections を含む payload
     * @param route geometry と総距離・所要時間を持つ中立 route
     * @return 案内イベント列を持つ semantic ルート
     */
    fun map(payload: ExtNavRoutePayload, route: RouteDetail): GuidanceRoute {
        val geometry = route.geometry
        val cumulativeMetres = RouteGeometryMath.cumulativeMetres(geometry)
        val totalGeometryMetres = cumulativeMetres.lastOrNull() ?: 0.0
        val distanceMapper = buildDistanceMapper(
            payload = payload,
            route = route,
            totalGeometryMetres = totalGeometryMetres,
        )
        val intersectionAnchors = buildIntersectionAnchors(
            payload = payload,
            route = route,
            cumulativeMetres = cumulativeMetres,
        )
        val events = buildEvents(
            payload = payload,
            route = route,
            cumulativeMetres = cumulativeMetres,
            totalGeometryMetres = totalGeometryMetres,
            distanceMapper = distanceMapper,
            intersectionAnchors = intersectionAnchors,
        )

        return GuidanceRoute(
            totalDistanceMeters = totalGeometryMetres,
            totalDurationSeconds = route.durationSeconds.roundToInt(),
            tollTotalYen = null,
            events = events,
        )
    }

    /**
     * 外部 API の source 距離基準を OneNavi geometry 距離基準へ変換する mapper を作る。
     *
     * source 総距離は summary 距離 → route 詳細距離 → geometry 積算距離の優先順で決める。
     */
    private fun buildDistanceMapper(
        payload: ExtNavRoutePayload,
        route: RouteDetail,
        totalGeometryMetres: Double,
    ): RouteDistanceMapper {
        val summaryMetres = payload.routeGuidance.summary.distanceMetres
            .toDouble()
            .takeIf { metres -> metres > 0.0 }
        val routeMetres = route.distanceMeters.takeIf { metres -> metres > 0.0 }
        val sourceTotalMetres = summaryMetres ?: routeMetres ?: totalGeometryMetres

        return RouteDistanceMapper(
            anchors = listOf(
                DistanceAnchor(sourceMetres = 0.0, geometryMetres = 0.0),
                DistanceAnchor(sourceMetres = sourceTotalMetres, geometryMetres = totalGeometryMetres),
            ),
        )
    }

    /**
     * intersection を最寄り geometry 点へ snap し、geometry 距離順の anchor 列にする。
     */
    private fun buildIntersectionAnchors(
        payload: ExtNavRoutePayload,
        route: RouteDetail,
        cumulativeMetres: DoubleArray,
    ): List<IntersectionAnchor> {
        val geometry = route.geometry
        if (geometry.isEmpty() || cumulativeMetres.isEmpty()) return emptyList()

        return payload.routeGuidance.intersections
            .map { intersection ->
                val position = RoutePoint(
                    latitude = intersection.position.latDegrees,
                    longitude = intersection.position.lonDegrees,
                )
                val geometryIndex = nearestGeometryIndex(geometry, position)
                IntersectionAnchor(
                    geometryMetres = cumulativeMetres[geometryIndex],
                    intersection = intersection,
                )
            }
            .sortedBy { anchor -> anchor.geometryMetres }
    }

    /**
     * 各 GP を [GuidanceEvent] へ変換する。骨組みでは主案内とレーンを持つ GP だけを出す。
     */
    private fun buildEvents(
        payload: ExtNavRoutePayload,
        route: RouteDetail,
        cumulativeMetres: DoubleArray,
        totalGeometryMetres: Double,
        distanceMapper: RouteDistanceMapper,
        intersectionAnchors: List<IntersectionAnchor>,
    ): ImmutableList<GuidanceEvent> {
        val guidancePoints = payload.routeGuidance.guidancePoints
        val lastIndex = guidancePoints.lastIndex
        val events = guidancePoints.mapIndexedNotNull { guidancePointIndex, guidancePoint ->
            val sourceDistanceMetres = guidancePoint.distanceFromStartMetres.toDouble()
            val geometryMetres = distanceMapper
                .mapSourceToGeometry(sourceDistanceMetres)
                .coerceIn(0.0, totalGeometryMetres)
            val context = EventContext(
                guidancePointIndex = guidancePointIndex,
                isLastGuidancePoint = guidancePointIndex == lastIndex,
                sourceDistanceMetres = sourceDistanceMetres,
                geometryMetres = geometryMetres,
                location = RouteGeometryMath.pointAt(
                    geometry = route.geometry,
                    cumulativeMetres = cumulativeMetres,
                    targetMetres = geometryMetres,
                    fallback = route.origin,
                ),
                bearingDiffDegrees = RouteGeometryMath.bearingDiffAt(
                    geometry = route.geometry,
                    cumulativeMetres = cumulativeMetres,
                    targetMetres = geometryMetres,
                ),
                nearestIntersection = nearestIntersection(intersectionAnchors, geometryMetres),
            )
            buildEvent(guidancePoint = guidancePoint, context = context)
        }
        return events.toImmutableList()
    }

    /**
     * 1 GP を [GuidanceEvent] に変換する。主案内もレーンも無い GP は骨組みでは捨てる
     * (施設のみの通過イベントは詳細追加時に拾う)。
     */
    private fun buildEvent(
        guidancePoint: ExtNavGuidancePoint,
        context: EventContext,
    ): GuidanceEvent? {
        val sourceRef = SourceRef(
            guidancePointIndex = context.guidancePointIndex,
            blockId = null,
            pieceIndex = null,
        )
        val sourceRefs = persistentListOf(sourceRef)
        val primary = buildPrimaryManeuver(guidancePoint = guidancePoint, context = context)
        val lane = guidancePoint.maneuver?.laneInfo?.toGuidanceLane(sourceRefs = sourceRefs)
        if (primary == null && lane == null) return null

        val anchor = RouteAnchor(
            sourceDistanceFromStartMeters = context.sourceDistanceMetres,
            geometryDistanceFromStartMeters = context.geometryMetres,
            location = context.location,
            sourceGuidancePointIndex = context.guidancePointIndex,
            sourceBlockIndex = null,
            matchErrorMeters = null,
        )
        val details = GuidanceEventDetails(
            facility = null,
            lane = lane,
            toll = null,
            signpost = null,
            boundary = null,
            roadName = null,
            notices = persistentListOf(),
        )
        return GuidanceEvent(
            id = GuidanceEventId("event-${context.guidancePointIndex}"),
            anchor = anchor,
            primary = primary,
            details = details,
            sourceRefs = sourceRefs,
        )
    }

    /**
     * GP の主案内を作る。進路選択を伴わない通過 GP では null。
     *
     * 骨組みでは category と方位差による簡易判定で、施設による除外
     * (パネル専用施設・合流注意のみ等) は詳細追加時に揃える。
     */
    private fun buildPrimaryManeuver(
        guidancePoint: ExtNavGuidancePoint,
        context: EventContext,
    ): GuidanceManeuver? {
        val categories = guidancePoint.phrases.map { phrase -> phrase.category }
        val maneuverType = ManeuverClassifier.maneuverType(
            categories = categories,
            isLastGuidancePoint = context.isLastGuidancePoint,
            bearingDiffDegrees = context.bearingDiffDegrees,
        )
        val isManeuver = context.isLastGuidancePoint || maneuverType != ManeuverType.CONTINUE
        if (!isManeuver) return null

        val modifier = ManeuverClassifier.maneuverModifier(context.bearingDiffDegrees)
        val intersectionName = context.nearestIntersection
            ?.intersection
            ?.name
            ?.takeIf { name -> name.isNotBlank() }
        return GuidanceManeuver(
            type = maneuverType,
            modifier = modifier,
            intersectionName = intersectionName,
            exitNumber = null,
        )
    }

    /**
     * marker 由来のレーン情報を semantic [GuidanceLane] に変換する。
     *
     * marker の値の意味 (rawA/rawB) が完全には確定していないため confidence は MEDIUM。
     * instruction / warning は発話テキスト解析経路を作る段階で埋める。
     */
    private fun ExtNavLaneInfo.toGuidanceLane(
        sourceRefs: ImmutableList<SourceRef>,
    ): GuidanceLane? {
        if (markers.isEmpty()) return null

        val lanes = markers
            .map { marker -> LaneMark(rawA = marker.rawA, rawB = marker.rawB) }
            .toImmutableList()
        val layout = LaneLayout.MarkerLayout(lanes = lanes, kind = kind)
        return GuidanceLane(
            layout = layout,
            instruction = null,
            warning = null,
            sources = persistentSetOf(LaneSource.MARKER),
            confidence = LaneConfidence.MEDIUM,
            sourceRefs = sourceRefs,
        )
    }

    /**
     * geometry 距離が最も近い intersection anchor を許容距離内で返す。
     */
    private fun nearestIntersection(
        intersectionAnchors: List<IntersectionAnchor>,
        geometryMetres: Double,
    ): IntersectionAnchor? = intersectionAnchors
        .filter { anchor -> abs(anchor.geometryMetres - geometryMetres) <= INTERSECTION_SNAP_TOLERANCE_METRES }
        .minByOrNull { anchor -> abs(anchor.geometryMetres - geometryMetres) }

    /**
     * geometry 上で指定座標に最も近い点の index を線形探索で返す。
     */
    private fun nearestGeometryIndex(
        geometry: List<RoutePoint>,
        point: RoutePoint,
    ): Int {
        var bestIndex = 0
        var bestDistanceMetres = Double.MAX_VALUE
        for (index in geometry.indices) {
            val distanceMetres = RouteGeometryMath.haversineMetres(geometry[index], point)
            if (distanceMetres < bestDistanceMetres) {
                bestDistanceMetres = distanceMetres
                bestIndex = index
            }
        }
        return bestIndex
    }

    /**
     * 1 GP を変換する間だけ使う、位置に関する中間計算結果。
     *
     * @property guidancePointIndex GP の index
     * @property isLastGuidancePoint 最終 GP かどうか
     * @property sourceDistanceMetres 外部データ上の始点からの距離 (m)
     * @property geometryMetres geometry に射影した始点からの距離 (m)
     * @property location geometry 上の座標
     * @property bearingDiffDegrees GP 前後の進行方位差
     * @property nearestIntersection 近傍 intersection anchor。無ければ null
     */
    private class EventContext(
        val guidancePointIndex: Int,
        val isLastGuidancePoint: Boolean,
        val sourceDistanceMetres: Double,
        val geometryMetres: Double,
        val location: RoutePoint,
        val bearingDiffDegrees: Float,
        val nearestIntersection: IntersectionAnchor?,
    )

    /**
     * intersection を geometry 距離へ snap した anchor (raw な intersection を保持)。
     *
     * @property geometryMetres intersection に対応する geometry 累積距離
     * @property intersection 元の intersection (名前 / 看板 / 施設などを後段で読む)
     */
    private class IntersectionAnchor(
        val geometryMetres: Double,
        val intersection: ExtNavIntersection,
    )

    private companion object {
        /** GP と intersection を対応付ける最大距離。 */
        private const val INTERSECTION_SNAP_TOLERANCE_METRES: Double = 300.0
    }
}
