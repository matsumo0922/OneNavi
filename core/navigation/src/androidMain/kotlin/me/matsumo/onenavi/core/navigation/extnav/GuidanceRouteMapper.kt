package me.matsumo.onenavi.core.navigation.extnav

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import me.matsumo.drive.supporter.api.guidance.domain.GuidanceCategory
import me.matsumo.onenavi.core.model.RoadClass
import me.matsumo.onenavi.core.model.RouteDetail
import me.matsumo.onenavi.core.model.RoutePoint
import me.matsumo.onenavi.core.navigation.newguidance.semantic.FacilityKind
import me.matsumo.onenavi.core.navigation.newguidance.semantic.GuidanceEvent
import me.matsumo.onenavi.core.navigation.newguidance.semantic.GuidanceEventDetails
import me.matsumo.onenavi.core.navigation.newguidance.semantic.GuidanceEventId
import me.matsumo.onenavi.core.navigation.newguidance.semantic.GuidanceLane
import me.matsumo.onenavi.core.navigation.newguidance.semantic.GuidanceManeuver
import me.matsumo.onenavi.core.navigation.newguidance.semantic.GuidanceNotice
import me.matsumo.onenavi.core.navigation.newguidance.semantic.GuidanceNoticeKind
import me.matsumo.onenavi.core.navigation.newguidance.semantic.GuidanceRoute
import me.matsumo.onenavi.core.navigation.newguidance.semantic.GuideImageKey
import me.matsumo.onenavi.core.navigation.newguidance.semantic.HighwayBoundary
import me.matsumo.onenavi.core.navigation.newguidance.semantic.LaneConfidence
import me.matsumo.onenavi.core.navigation.newguidance.semantic.LaneLayout
import me.matsumo.onenavi.core.navigation.newguidance.semantic.LaneMark
import me.matsumo.onenavi.core.navigation.newguidance.semantic.LaneSource
import me.matsumo.onenavi.core.navigation.newguidance.semantic.RouteAnchor
import me.matsumo.onenavi.core.navigation.newguidance.semantic.SourceRef
import me.matsumo.onenavi.core.navigation.newguidance.semantic.StepFacility
import me.matsumo.onenavi.core.navigation.newguidance.semantic.StepRoadName
import me.matsumo.onenavi.core.navigation.newguidance.semantic.StepSignpost
import kotlin.math.abs
import kotlin.math.roundToInt
import me.matsumo.drive.supporter.api.guidance.domain.GuidanceFacilityKind as ExtNavGuidanceFacilityKind
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
 * 各 GP を主案内 ([GuidanceManeuver])・レーン ([GuidanceLane])・施設・看板・境界・道路名・
 * 通知を持つ [GuidanceEvent] に射影する。さらに、GP に紐付かない施設付き intersection
 * (通過 SA / PA / 料金所など) を主案内 null の通過施設イベントとして補完する。
 * レーンは `lane_markers` (料金所・高速ゲート) を優先し、無ければ [LaneDiagramParser] が
 * `flags_group` から復元した一般道交差点 / 高速入口の車線図を使う。
 */
internal class GuidanceRouteMapper {

    private val laneDiagramParser = LaneDiagramParser()
    private val distanceContextFactory = RouteDistanceContextFactory()

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
        val distanceContext = distanceContextFactory.create(
            payload = payload,
            route = route,
            cumulativeMetres = cumulativeMetres,
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
            distanceContext = distanceContext,
            intersectionAnchors = intersectionAnchors,
        )

        return GuidanceRoute(
            totalDistanceMeters = totalGeometryMetres,
            totalDurationSeconds = route.durationSeconds.roundToInt(),
            tollTotalYen = routeTollYen(route),
            events = events,
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
     * 各 GP を [GuidanceEvent] へ変換し、さらに GP に紐付かない施設付き intersection を
     * 通過施設イベントとして補完する。主案内・レーン・施設・通知のいずれかを持つものだけを
     * geometry 距離の昇順で返す。
     */
    private fun buildEvents(
        payload: ExtNavRoutePayload,
        route: RouteDetail,
        cumulativeMetres: DoubleArray,
        distanceContext: ExtNavRouteDistanceContext,
        intersectionAnchors: List<IntersectionAnchor>,
    ): ImmutableList<GuidanceEvent> {
        val guidancePoints = payload.routeGuidance.guidancePoints
        val lastIndex = guidancePoints.lastIndex
        val consumedIntersections = mutableSetOf<IntersectionAnchor>()
        val events = mutableListOf<GuidanceEvent>()

        // GP 由来イベント。各イベントが近傍として使った intersection を消費済みに記録する。
        for (guidancePointIndex in guidancePoints.indices) {
            val context = buildEventContext(
                guidancePointIndex = guidancePointIndex,
                guidancePoint = guidancePoints[guidancePointIndex],
                lastIndex = lastIndex,
                route = route,
                cumulativeMetres = cumulativeMetres,
                distanceContext = distanceContext,
                intersectionAnchors = intersectionAnchors,
            )
            val event = buildEvent(
                guidancePoint = guidancePoints[guidancePointIndex],
                context = context,
                route = route,
                cumulativeMetres = cumulativeMetres,
            ) ?: continue
            val nearestIntersection = context.nearestIntersection
            if (nearestIntersection != null) consumedIntersections += nearestIntersection
            events += event
        }

        addUncoveredFacilityEvents(
            events = events,
            intersectionAnchors = intersectionAnchors,
            consumedIntersections = consumedIntersections,
            route = route,
            cumulativeMetres = cumulativeMetres,
        )
        events.sortBy { event -> event.anchor.geometryDistanceFromStartMeters }
        return events.toImmutableList()
    }

    /**
     * どの GP イベントにも紐付かなかった施設付き intersection を、主案内を持たない通過施設
     * イベントとして [events] に追加する。GP が近傍として消費済みの intersection は重複を
     * 避けるため除外する。
     */
    private fun addUncoveredFacilityEvents(
        events: MutableList<GuidanceEvent>,
        intersectionAnchors: List<IntersectionAnchor>,
        consumedIntersections: Set<IntersectionAnchor>,
        route: RouteDetail,
        cumulativeMetres: DoubleArray,
    ) {
        for (intersectionIndex in intersectionAnchors.indices) {
            val intersectionAnchor = intersectionAnchors[intersectionIndex]
            if (intersectionAnchor in consumedIntersections) continue
            val event = buildIntersectionFacilityEvent(
                intersectionAnchor = intersectionAnchor,
                intersectionIndex = intersectionIndex,
                route = route,
                cumulativeMetres = cumulativeMetres,
            ) ?: continue
            events += event
        }
    }

    /**
     * 施設付き intersection から、主案内 null + facility 付きの通過施設イベントを作る。
     *
     * GP に紐付かない通過施設なので [RouteAnchor.sourceGuidancePointIndex] は null とし、
     * source 距離も持たない (geometry 距離のみ)。施設種別を持たない端点などは null を返す。
     */
    private fun buildIntersectionFacilityEvent(
        intersectionAnchor: IntersectionAnchor,
        intersectionIndex: Int,
        route: RouteDetail,
        cumulativeMetres: DoubleArray,
    ): GuidanceEvent? {
        val intersection = intersectionAnchor.intersection
        val rawKind = intersection.facilityHint?.kind ?: return null
        val facilityKind = rawKind.toFacilityKind() ?: return null
        val refinedKind = facilityKind.refinedByName(intersection.name)
        val geometryMetres = intersectionAnchor.geometryMetres
        val location = RouteGeometryMath.pointAt(
            geometry = route.geometry,
            cumulativeMetres = cumulativeMetres,
            targetMetres = geometryMetres,
            fallback = route.origin,
        )
        val facility = StepFacility(
            kind = refinedKind,
            name = intersection.name,
            services = persistentListOf(),
        )
        val details = GuidanceEventDetails(
            facility = facility,
            lane = null,
            toll = null,
            signpost = buildSignpost(intersectionAnchor),
            boundary = boundaryAt(
                route = route,
                cumulativeMetres = cumulativeMetres,
                geometryMetres = geometryMetres,
            ),
            roadName = buildRoadName(intersectionAnchor),
            notices = persistentListOf(),
        )
        val anchor = RouteAnchor(
            sourceDistanceFromStartMeters = null,
            geometryDistanceFromStartMeters = geometryMetres,
            location = location,
            sourceGuidancePointIndex = null,
            sourceBlockIndex = null,
            matchErrorMeters = null,
        )
        return GuidanceEvent(
            id = GuidanceEventId("event-intersection-$intersectionIndex"),
            anchor = anchor,
            primary = null,
            details = details,
            sourceRefs = persistentListOf(),
        )
    }

    /** GP の geometry 距離・座標・方位差・近傍 intersection をまとめた [EventContext] を作る。 */
    private fun buildEventContext(
        guidancePointIndex: Int,
        guidancePoint: ExtNavGuidancePoint,
        lastIndex: Int,
        route: RouteDetail,
        cumulativeMetres: DoubleArray,
        distanceContext: ExtNavRouteDistanceContext,
        intersectionAnchors: List<IntersectionAnchor>,
    ): EventContext {
        val sourceDistanceMetres = guidancePoint.distanceFromStartMetres.toDouble()
        val geometryMetres = distanceContext.geometryMetresFor(sourceDistanceMetres)
        val location = RouteGeometryMath.pointAt(
            geometry = route.geometry,
            cumulativeMetres = cumulativeMetres,
            targetMetres = geometryMetres,
            fallback = route.origin,
        )
        val bearingDiffDegrees = RouteGeometryMath.bearingDiffAt(
            geometry = route.geometry,
            cumulativeMetres = cumulativeMetres,
            targetMetres = geometryMetres,
        )
        return EventContext(
            guidancePointIndex = guidancePointIndex,
            isLastGuidancePoint = guidancePointIndex == lastIndex,
            sourceDistanceMetres = sourceDistanceMetres,
            geometryMetres = geometryMetres,
            location = location,
            bearingDiffDegrees = bearingDiffDegrees,
            nearestIntersection = nearestIntersection(intersectionAnchors, geometryMetres),
        )
    }

    /**
     * 1 GP を [GuidanceEvent] に変換する。
     *
     * 主案内・レーン・施設・通知のいずれも無い GP は意味が無いので捨てる。施設のみの
     * 通過 GP (例: SA/PA 通過) は主案内 null + facility 付きのイベントとして拾う。
     */
    private fun buildEvent(
        guidancePoint: ExtNavGuidancePoint,
        context: EventContext,
        route: RouteDetail,
        cumulativeMetres: DoubleArray,
    ): GuidanceEvent? {
        val categories = guidancePoint.guidanceCategories()
        val eventSourceRefs = guidancePoint.buildSourceRefs(context.guidancePointIndex)
        val laneSourceRefs = persistentListOf(
            SourceRef(
                guidancePointIndex = context.guidancePointIndex,
                blockId = null,
                pieceIndex = null,
            ),
        )
        val facility = resolveFacility(guidancePoint = guidancePoint, context = context)
        val primary = buildPrimaryManeuver(
            guidancePoint = guidancePoint,
            context = context,
            categories = categories,
            facility = facility,
        )
        val lane = buildLane(guidancePoint = guidancePoint, sourceRefs = laneSourceRefs)
        val notices = buildNotices(categories)

        val hasContent = primary != null || lane != null || facility != null || notices.isNotEmpty()
        if (!hasContent) return null

        val anchor = RouteAnchor(
            sourceDistanceFromStartMeters = context.sourceDistanceMetres,
            geometryDistanceFromStartMeters = context.geometryMetres,
            location = context.location,
            sourceGuidancePointIndex = context.guidancePointIndex,
            sourceBlockIndex = null,
            matchErrorMeters = null,
        )
        val details = GuidanceEventDetails(
            facility = facility,
            lane = lane,
            toll = null,
            signpost = buildSignpost(context.nearestIntersection),
            boundary = boundaryAt(
                route = route,
                cumulativeMetres = cumulativeMetres,
                geometryMetres = context.geometryMetres,
            ),
            roadName = buildRoadName(context.nearestIntersection),
            notices = notices,
        )
        return GuidanceEvent(
            id = GuidanceEventId("event-${context.guidancePointIndex}"),
            anchor = anchor,
            primary = primary,
            details = details,
            sourceRefs = eventSourceRefs,
        )
    }

    /**
     * GP の発話片の category を piece 単位で取得する。
     *
     * L0 で保持した [announcementBlocks] の各 piece の category を使い、代表 1 つに
     * 潰さない。block が無い場合のみ従来の phrases 由来 category にフォールバックする。
     */
    private fun ExtNavGuidancePoint.guidanceCategories(): List<GuidanceCategory> {
        val pieceCategories = announcementBlocks
            .flatMap { block -> block.pieces }
            .mapNotNull { piece -> piece.category }
        if (pieceCategories.isNotEmpty()) return pieceCategories
        return phrases.map { phrase -> phrase.category }
    }

    /**
     * GP の発話片を piece 単位で辿る [SourceRef] 列を作る。
     *
     * block / piece 単位で source を残すことで、後段が text lane / notice を
     * 拾い直せるようにする。block が無い場合は GP 単位の参照 1 件にフォールバックする。
     */
    private fun ExtNavGuidancePoint.buildSourceRefs(
        guidancePointIndex: Int,
    ): ImmutableList<SourceRef> {
        val refs = mutableListOf<SourceRef>()
        for (block in announcementBlocks) {
            for (pieceIndex in block.pieces.indices) {
                val ref = SourceRef(
                    guidancePointIndex = guidancePointIndex,
                    blockId = block.id,
                    pieceIndex = pieceIndex,
                )
                refs += ref
            }
        }
        if (refs.isNotEmpty()) return refs.toImmutableList()
        val fallback = SourceRef(
            guidancePointIndex = guidancePointIndex,
            blockId = null,
            pieceIndex = null,
        )
        return persistentListOf(fallback)
    }

    /**
     * GP の主案内を作る。進路選択を伴わない通過 GP やパネル専用施設では null。
     *
     * category と方位差で maneuver 種別を決めつつ、パネル専用施設 (SA / PA / 料金所) は
     * 通過扱いとして主案内にしない (= [GuidanceEventDetails.facility] 側で表現する)。
     */
    private fun buildPrimaryManeuver(
        guidancePoint: ExtNavGuidancePoint,
        context: EventContext,
        categories: List<GuidanceCategory>,
        facility: StepFacility?,
    ): GuidanceManeuver? {
        val isPanelOnlyFacility = facility != null && facility.kind.isPanelOnly()
        val hasRouteDecisionDirection = guidancePoint.hasRouteDecisionDirection(context.bearingDiffDegrees)
        val shouldCreate = ManeuverClassifier.shouldCreatePrimaryManeuver(
            categories = categories,
            bearingDiffDegrees = context.bearingDiffDegrees,
            isLastGuidancePoint = context.isLastGuidancePoint,
            hasFacility = facility != null,
            isPanelOnlyFacility = isPanelOnlyFacility,
            hasRouteDecisionDirection = hasRouteDecisionDirection,
        )
        if (!shouldCreate) return null

        val maneuverType = ManeuverClassifier.maneuverType(
            categories = categories,
            isLastGuidancePoint = context.isLastGuidancePoint,
            bearingDiffDegrees = context.bearingDiffDegrees,
        )
        val modifier = ManeuverClassifier.toManeuverModifierOrNull(guidancePoint.maneuver?.direction)
            ?: ManeuverClassifier.maneuverModifier(context.bearingDiffDegrees)
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
     * GP が進路判断を伴う方向を持つかを返す (方向 enum または方位差から判定)。
     */
    private fun ExtNavGuidancePoint.hasRouteDecisionDirection(bearingDiffDegrees: Float): Boolean {
        val direction = maneuver?.direction
        val isDecisionDirection = direction != null && ManeuverClassifier.isRouteDecisionDirection(direction)
        return isDecisionDirection || abs(bearingDiffDegrees) >= ManeuverClassifier.TURN_BEARING_DIFF_DEGREES
    }

    /**
     * GP の施設情報を解決する。GP 自身の facilityHint を優先し、無ければ近傍 intersection
     * から取る。名前から IC→JCT / PA→SA を補正する。
     */
    private fun resolveFacility(
        guidancePoint: ExtNavGuidancePoint,
        context: EventContext,
    ): StepFacility? {
        val guidancePointFacilityKind = guidancePoint.maneuver?.facilityHint?.kind
        val intersectionFacilityKind = context.nearestIntersection?.intersection?.facilityHint?.kind
        val rawKind = guidancePointFacilityKind ?: intersectionFacilityKind ?: return null
        val facilityKind = rawKind.toFacilityKind() ?: return null
        val name = context.nearestIntersection?.intersection?.name.orEmpty()
        val refinedKind = facilityKind.refinedByName(name)
        return StepFacility(
            kind = refinedKind,
            name = name,
            services = persistentListOf(),
        )
    }

    /** 近傍 intersection の方面看板から [StepSignpost] を作る。主方面が空なら null。 */
    private fun buildSignpost(nearestIntersection: IntersectionAnchor?): StepSignpost? {
        val intersection = nearestIntersection?.intersection ?: return null
        val primary = intersection.directionSignA.trim().takeIf { text -> text.isNotEmpty() } ?: return null
        val secondary = intersection.directionSignB.trim().takeIf { text -> text.isNotEmpty() }
        val firstImage = intersection.imageRefs.firstOrNull()
        val imageRef = firstImage?.let { image -> GuideImageKey(major = image.major, minor = image.minor) }
        return StepSignpost(
            primary = primary,
            secondary = secondary,
            imageRef = imageRef,
        )
    }

    /** 近傍 intersection の道路名から [StepRoadName] を作る。名前が無ければ null。 */
    private fun buildRoadName(nearestIntersection: IntersectionAnchor?): StepRoadName? {
        val intersection = nearestIntersection?.intersection ?: return null
        val primaryRoadName = intersection.roadName.trim().takeIf { text -> text.isNotEmpty() }
        val officialRoadName = intersection.roadNameOfficial.trim().takeIf { text -> text.isNotEmpty() }
        val roadName = primaryRoadName ?: officialRoadName ?: return null
        return StepRoadName(text = roadName)
    }

    /** GP の category 群を裾の通知 ([GuidanceNotice]) に変換する。 */
    private fun buildNotices(categories: List<GuidanceCategory>): ImmutableList<GuidanceNotice> {
        val notices = categories.mapNotNull { category -> category.toNoticeOrNull() }
        return notices.distinct().toImmutableList()
    }

    /** category を通知に変換する。通知対象でなければ null。 */
    private fun GuidanceCategory.toNoticeOrNull(): GuidanceNotice? {
        val kind = NOTICE_KIND_BY_CATEGORY[this] ?: return null
        return GuidanceNotice(kind = kind, text = null)
    }

    /**
     * 指定 geometry 距離が高速の入口 / 出口付近なら境界種別を返す。
     *
     * roadClassSegments の HIGHWAY 区間の開始 (入口) / 終了 (出口) のうち、許容距離内で
     * 最も近いものを採用する。
     */
    private fun boundaryAt(
        route: RouteDetail,
        cumulativeMetres: DoubleArray,
        geometryMetres: Double,
    ): HighwayBoundary? {
        val candidates = mutableListOf<Pair<Double, HighwayBoundary>>()
        for (segment in route.roadClassSegments) {
            if (segment.roadClass != RoadClass.HIGHWAY) continue
            if (segment.startPointIndex > 0) {
                val startMetres = cumulativeMetres.valueAtOrNull(segment.startPointIndex)
                if (startMetres != null) candidates += startMetres to HighwayBoundary.ENTRANCE
            }
            if (segment.endPointIndex < route.geometry.lastIndex) {
                val endMetres = cumulativeMetres.valueAtOrNull(segment.endPointIndex)
                if (endMetres != null) candidates += endMetres to HighwayBoundary.EXIT
            }
        }

        val nearest = candidates.minByOrNull { candidate -> abs(candidate.first - geometryMetres) }
            ?: return null
        if (abs(nearest.first - geometryMetres) > HIGHWAY_BOUNDARY_TOLERANCE_METRES) return null
        return nearest.second
    }

    /** ルート合計料金 (円)。tollFee 優先、無ければ内訳合計。0 以下なら null。 */
    private fun routeTollYen(route: RouteDetail): Int? {
        val directFee = route.tollFee?.takeIf { amountYen -> amountYen > 0 }
        if (directFee != null) return directFee
        val summedFee = route.tollDetails.sumOf { detail -> detail.amount }
        return summedFee.takeIf { amountYen -> amountYen > 0 }
    }

    /** index 範囲内の累積距離を返す。範囲外なら null。 */
    private fun DoubleArray.valueAtOrNull(index: Int): Double? =
        if (index in indices) this[index] else null

    /** 外部 API 由来の施設種別を semantic 施設種別へ変換する。端点は null。 */
    private fun ExtNavGuidanceFacilityKind.toFacilityKind(): FacilityKind? = when (this) {
        ExtNavGuidanceFacilityKind.INTERCHANGE -> FacilityKind.IC
        ExtNavGuidanceFacilityKind.PARKING_AREA -> FacilityKind.PA
        ExtNavGuidanceFacilityKind.TOLL_GATE -> FacilityKind.TOLL_GATE
        ExtNavGuidanceFacilityKind.ENDPOINT -> null
    }

    /** 施設名から IC→JCT / PA→SA を補正する。 */
    private fun FacilityKind.refinedByName(name: String): FacilityKind = when {
        this == FacilityKind.IC && name.contains("JCT", ignoreCase = true) -> FacilityKind.JCT
        this == FacilityKind.PA && name.contains("SA", ignoreCase = true) -> FacilityKind.SA
        else -> this
    }

    /** 進路判断ではなく通過パネルとして扱う施設か。 */
    private fun FacilityKind.isPanelOnly(): Boolean = when (this) {
        FacilityKind.SA,
        FacilityKind.PA,
        FacilityKind.TOLL_GATE,
        -> true
        FacilityKind.IC,
        FacilityKind.JCT,
        -> false
    }

    /**
     * GP のレーン情報を組み立てる。`lane_markers` (料金所・高速ゲート) を優先し、無ければ
     * `flags_group` 由来の一般道交差点 / 高速入口の車線図を使う。どちらも無ければ null。
     *
     * @param guidancePoint 対象 GP
     * @param sourceRefs 元データへの参照
     * @return レーン情報。marker も車線図も無ければ null
     */
    private fun buildLane(
        guidancePoint: ExtNavGuidancePoint,
        sourceRefs: ImmutableList<SourceRef>,
    ): GuidanceLane? {
        val maneuver = guidancePoint.maneuver ?: return null
        val markerLane = maneuver.laneInfo?.toGuidanceLane(sourceRefs = sourceRefs)
        if (markerLane != null) return markerLane
        return laneDiagramParser.parse(entries = maneuver.flagsGroup, sourceRefs = sourceRefs)
    }

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

        /** 高速入口 / 出口と GP を対応付ける最大距離。 */
        private const val HIGHWAY_BOUNDARY_TOLERANCE_METRES: Double = 600.0

        /** 裾の通知に変換する category とその通知種別の対応。 */
        private val NOTICE_KIND_BY_CATEGORY: Map<GuidanceCategory, GuidanceNoticeKind> = mapOf(
            GuidanceCategory.AccidentBlackSpot to GuidanceNoticeKind.ACCIDENT_BLACK_SPOT,
            GuidanceCategory.Orbis to GuidanceNoticeKind.SPEED_CAMERA,
            GuidanceCategory.Curve to GuidanceNoticeKind.CURVE,
            GuidanceCategory.StopLine to GuidanceNoticeKind.STOP_LINE,
            GuidanceCategory.SpeedAdjustment to GuidanceNoticeKind.SPEED_ADJUSTMENT,
            GuidanceCategory.MergeAttention to GuidanceNoticeKind.MERGE_ATTENTION,
        )
    }
}
