package me.matsumo.onenavi.core.navigation.extnav

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableSet
import me.matsumo.drive.supporter.api.core.model.Coord
import me.matsumo.drive.supporter.api.guidance.domain.DsrRouteSummary
import me.matsumo.drive.supporter.api.guidance.domain.ExternalGuideAnchor
import me.matsumo.drive.supporter.api.guidance.domain.FlagsGroupEntry
import me.matsumo.drive.supporter.api.guidance.domain.GuidanceCategory
import me.matsumo.drive.supporter.api.guidance.domain.GuidanceFacilityHint
import me.matsumo.drive.supporter.api.guidance.domain.GuidanceFacilityKind
import me.matsumo.drive.supporter.api.guidance.domain.GuidancePoint
import me.matsumo.drive.supporter.api.guidance.domain.GuideAnnouncementBlock
import me.matsumo.drive.supporter.api.guidance.domain.GuideAnnouncementPiece
import me.matsumo.drive.supporter.api.guidance.domain.GuideImageRef
import me.matsumo.drive.supporter.api.guidance.domain.Intersection
import me.matsumo.drive.supporter.api.guidance.domain.LaneInfo
import me.matsumo.drive.supporter.api.guidance.domain.LaneMarker
import me.matsumo.drive.supporter.api.guidance.domain.ManeuverDirection
import me.matsumo.drive.supporter.api.guidance.domain.ManeuverHint
import me.matsumo.drive.supporter.api.guidance.domain.RouteGuidance
import me.matsumo.drive.supporter.api.guidance.domain.SsmlPhrase
import me.matsumo.onenavi.core.model.ManeuverModifier
import me.matsumo.onenavi.core.model.ManeuverType
import me.matsumo.onenavi.core.model.RoadClass
import me.matsumo.onenavi.core.model.RoadClassSegment
import me.matsumo.onenavi.core.model.RouteDetail
import me.matsumo.onenavi.core.model.RoutePoint
import me.matsumo.onenavi.core.navigation.newguidance.semantic.FacilityKind
import me.matsumo.onenavi.core.navigation.newguidance.semantic.GuidanceNoticeKind
import me.matsumo.onenavi.core.navigation.newguidance.semantic.HighwayBoundary
import me.matsumo.onenavi.core.navigation.newguidance.semantic.LaneConfidence
import me.matsumo.onenavi.core.navigation.newguidance.semantic.LaneLayout
import me.matsumo.onenavi.core.navigation.newguidance.semantic.LaneSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [GuidanceRouteMapper] の骨組み (anchor / 主案内 / lane) 射影テスト。
 */
class GuidanceRouteMapperTest {

    @Test
    fun `maneuver と lane を持つ GP だけがイベント化される`() {
        val mapper = GuidanceRouteMapper()
        val route = buildRoute()
        val payload = ExtNavRoutePayload(id = route.id, routeGuidance = buildRouteGuidance())

        val guidanceRoute = mapper.map(payload = payload, route = route)

        // GP0 (turn) / GP1 (lane) / GP3 (arrive) の 3 件。CONTINUE かつ lane 無しの GP2 は捨てる。
        assertEquals(3, guidanceRoute.events.size)
        assertEquals(300, guidanceRoute.totalDurationSeconds)
    }

    @Test
    fun `交差点案内 GP は TURN の主案内になる`() {
        val mapper = GuidanceRouteMapper()
        val route = buildRoute()
        val payload = ExtNavRoutePayload(id = route.id, routeGuidance = buildRouteGuidance())

        val guidanceRoute = mapper.map(payload = payload, route = route)

        val turnEvent = guidanceRoute.events.first()
        assertEquals(ManeuverType.TURN, turnEvent.primary?.type)
        assertEquals(200.0, turnEvent.anchor.sourceDistanceFromStartMeters)
    }

    @Test
    fun `origin が追加された route は API polyline 区間へ source 距離を対応させる`() {
        val mapper = GuidanceRouteMapper()
        val origin = routeOrigin()
        val apiPolylineCoords = (1..4).map { index ->
            Coord.fromDegrees(
                latDeg = ORIGIN_LATITUDE,
                lonDeg = ORIGIN_LONGITUDE + LONGITUDE_STEP * index,
            )
        }
        val apiPolyline = apiPolylineCoords.map { coord ->
            RoutePoint(
                latitude = coord.latDegrees,
                longitude = coord.lonDegrees,
            )
        }
        val route = RouteDetail(
            id = "route-mapper-prepended-origin-test",
            origin = origin,
            destination = apiPolyline.last(),
            intermediateWaypoints = persistentListOf(),
            geometry = (listOf(origin) + apiPolyline).toImmutableList(),
            distanceMeters = 1_000.0,
            durationSeconds = 300.0,
            steps = persistentListOf(),
        )
        val cumulativeMetres = RouteGeometryMath.cumulativeMetres(route.geometry)
        val routeGuidance = buildRouteGuidance().copy(
            guidancePoints = listOf(
                buildGuidancePoint(
                    index = 0,
                    distanceFromStartMetres = 200,
                    category = GuidanceCategory.IntersectionGuide,
                    laneInfo = null,
                    direction = ManeuverDirection.Left,
                ),
                buildGuidancePoint(
                    index = 1,
                    distanceFromStartMetres = 950,
                    category = GuidanceCategory.Unspecified,
                    laneInfo = null,
                ),
            ).toImmutableList(),
            polyline = apiPolylineCoords.toImmutableList(),
        )
        val payload = ExtNavRoutePayload(id = route.id, routeGuidance = routeGuidance)

        val guidanceRoute = mapper.map(payload = payload, route = route)

        val turnEvent = guidanceRoute.events.first()
        val sourceGeometryStartMetres = cumulativeMetres[1]
        val sourceGeometryEndMetres = cumulativeMetres.last()
        val expectedGeometryMetres = sourceGeometryStartMetres +
            (sourceGeometryEndMetres - sourceGeometryStartMetres) * 200.0 / 1_000.0
        assertEquals(
            expectedGeometryMetres,
            turnEvent.anchor.geometryDistanceFromStartMeters,
            absoluteTolerance = 0.001,
        )
    }

    @Test
    fun `主案内 modifier は geometry より案内方向分類を優先する`() {
        val mapper = GuidanceRouteMapper()
        val route = buildRoute()
        val routeGuidance = buildRouteGuidance().copy(
            guidancePoints = listOf(
                buildGuidancePoint(
                    index = 0,
                    distanceFromStartMetres = 200,
                    category = GuidanceCategory.IntersectionGuide,
                    laneInfo = null,
                    direction = ManeuverDirection.Left,
                ),
                buildGuidancePoint(
                    index = 1,
                    distanceFromStartMetres = 950,
                    category = GuidanceCategory.Unspecified,
                    laneInfo = null,
                ),
            ).toImmutableList(),
        )
        val payload = ExtNavRoutePayload(id = route.id, routeGuidance = routeGuidance)

        val guidanceRoute = mapper.map(payload = payload, route = route)

        val turnEvent = guidanceRoute.events.first()
        assertEquals(ManeuverType.TURN, turnEvent.primary?.type)
        assertEquals(ManeuverModifier.LEFT, turnEvent.primary?.modifier)
    }

    @Test
    fun `lane を持つ GP は marker layout を保持し主案内を持たない`() {
        val mapper = GuidanceRouteMapper()
        val route = buildRoute()
        val payload = ExtNavRoutePayload(id = route.id, routeGuidance = buildRouteGuidance())

        val guidanceRoute = mapper.map(payload = payload, route = route)

        val laneEvent = guidanceRoute.events.first { event -> event.details.lane != null }
        assertNull(laneEvent.primary)
        val layout = assertIs<LaneLayout.MarkerLayout>(laneEvent.details.lane?.layout)
        assertEquals(3, layout.lanes.size)
        assertTrue(layout.lanes.any { lane -> lane.isRecommended })
    }

    @Test
    fun `flags_group を持つ GP は方向付き車線図レーンになる`() {
        val mapper = GuidanceRouteMapper()
        val route = buildRoute()
        val guidancePoint = buildFlagsGroupGuidancePoint(
            entries = listOf(
                FlagsGroupEntry(a = 0, b = 0, compactDirections = persistentListOf(2, 4)),
                FlagsGroupEntry(a = 0, b = 0, compactDirections = persistentListOf(4)),
                FlagsGroupEntry(a = 1, b = 1, compactDirections = persistentListOf(6)),
            ),
        )
        val payload = ExtNavRoutePayload(id = route.id, routeGuidance = buildLaneGuidance(guidancePoint))

        val guidanceRoute = mapper.map(payload = payload, route = route)

        val lane = guidanceRoute.events.first { event -> event.details.lane != null }.details.lane
        val layout = assertIs<LaneLayout.DirectionLayout>(lane?.layout)
        assertEquals(3, layout.lanes.size)
        assertEquals(setOf(ManeuverModifier.LEFT, ManeuverModifier.STRAIGHT), layout.lanes[0].directions)
        assertTrue(layout.lanes[2].isTarget)
        assertEquals(LaneConfidence.HIGH, lane?.confidence)
        assertTrue(lane?.sources?.contains(LaneSource.LANE_DIAGRAM) == true)
    }

    @Test
    fun `最終 GP は ARRIVE の主案内になる`() {
        val mapper = GuidanceRouteMapper()
        val route = buildRoute()
        val payload = ExtNavRoutePayload(id = route.id, routeGuidance = buildRouteGuidance())

        val guidanceRoute = mapper.map(payload = payload, route = route)

        val arriveEvent = guidanceRoute.events.last()
        assertEquals(ManeuverType.ARRIVE, arriveEvent.primary?.type)
    }

    @Test
    fun `料金所 GP は施設と境界と看板を持ち主案内を持たない`() {
        val mapper = GuidanceRouteMapper()
        val route = buildHighwayRoute()
        val payload = ExtNavRoutePayload(id = route.id, routeGuidance = buildHighwayRouteGuidance())

        val guidanceRoute = mapper.map(payload = payload, route = route)

        val tollEvent = guidanceRoute.events.first { event ->
            event.details.facility?.kind == FacilityKind.TOLL_GATE
        }
        assertNull(tollEvent.primary)
        assertEquals(HighwayBoundary.ENTRANCE, tollEvent.details.boundary)
        assertEquals("東京方面", tollEvent.details.signpost?.primary)
        // 料金はルート合計として route 側に持つ。各料金所地点には重複させない。
        assertNull(tollEvent.details.toll)
        assertEquals(320, guidanceRoute.tollTotalYen)
    }

    @Test
    fun `看板画像は近傍 intersection より案内点自身の画像 ID を優先する`() {
        val mapper = GuidanceRouteMapper()
        val route = buildHighwayRoute()
        val guidancePointImage = GuideImageRef(major = 5, minor = 222_222)
        val intersectionImage = GuideImageRef(major = 101, minor = 111_111)
        val routeGuidance = buildHighwayRouteGuidance()
        val routeGuidanceWithImages = routeGuidance.withFirstGuidancePointAndIntersectionImages(
            guidancePointImages = listOf(guidancePointImage),
            intersectionImages = listOf(intersectionImage),
        )
        val payload = ExtNavRoutePayload(id = route.id, routeGuidance = routeGuidanceWithImages)

        val guidanceRoute = mapper.map(payload = payload, route = route)

        val tollEvent = guidanceRoute.events.first { event ->
            event.details.facility?.kind == FacilityKind.TOLL_GATE
        }
        assertEquals(guidancePointImage.major, tollEvent.details.signpost?.imageRef?.major)
        assertEquals(guidancePointImage.minor, tollEvent.details.signpost?.imageRef?.minor)
    }

    @Test
    fun `看板画像は案内点自身の画像 ID が空なら近傍 intersection の画像 ID を使う`() {
        val mapper = GuidanceRouteMapper()
        val route = buildHighwayRoute()
        val intersectionImage = GuideImageRef(major = 101, minor = 111_111)
        val routeGuidance = buildHighwayRouteGuidance()
        val routeGuidanceWithImages = routeGuidance.withFirstGuidancePointAndIntersectionImages(
            guidancePointImages = emptyList(),
            intersectionImages = listOf(intersectionImage),
        )
        val payload = ExtNavRoutePayload(id = route.id, routeGuidance = routeGuidanceWithImages)

        val guidanceRoute = mapper.map(payload = payload, route = route)

        val tollEvent = guidanceRoute.events.first { event ->
            event.details.facility?.kind == FacilityKind.TOLL_GATE
        }
        assertEquals(intersectionImage.major, tollEvent.details.signpost?.imageRef?.major)
        assertEquals(intersectionImage.minor, tollEvent.details.signpost?.imageRef?.minor)
    }

    @Test
    fun `料金所画像は方面看板画像として採用しない`() {
        val mapper = GuidanceRouteMapper()
        val route = buildHighwayRoute()
        val tollGateImage = GuideImageRef(major = 201, minor = 222_222)
        val routeGuidance = buildHighwayRouteGuidance()
        val routeGuidanceWithImages = routeGuidance.withFirstGuidancePointAndIntersectionImages(
            guidancePointImages = listOf(tollGateImage),
            intersectionImages = listOf(tollGateImage),
        )
        val payload = ExtNavRoutePayload(id = route.id, routeGuidance = routeGuidanceWithImages)

        val guidanceRoute = mapper.map(payload = payload, route = route)

        val tollEvent = guidanceRoute.events.first { event ->
            event.details.facility?.kind == FacilityKind.TOLL_GATE
        }
        assertNull(tollEvent.details.signpost?.imageRef)
    }

    @Test
    fun `オービスの category は通知になる`() {
        val mapper = GuidanceRouteMapper()
        val route = buildHighwayRoute()
        val payload = ExtNavRoutePayload(id = route.id, routeGuidance = buildHighwayRouteGuidance())

        val guidanceRoute = mapper.map(payload = payload, route = route)

        val noticeEvent = guidanceRoute.events.first { event ->
            event.details.notices.isNotEmpty()
        }
        assertTrue(noticeEvent.details.notices.any { notice -> notice.kind == GuidanceNoticeKind.SPEED_CAMERA })
    }

    @Test
    fun `混在 block の piece category が L1 で潰れず notice に残る`() {
        val mapper = GuidanceRouteMapper()
        val route = buildRoute()
        val payload = ExtNavRoutePayload(id = route.id, routeGuidance = buildMixedBlockGuidance())

        val guidanceRoute = mapper.map(payload = payload, route = route)

        // GP0 は「交差点案内 + 一時停止」の混在 block。代表 category に潰れず両方残る。
        val event = guidanceRoute.events.first()
        assertEquals(ManeuverType.TURN, event.primary?.type)
        assertTrue(
            event.details.notices.any { notice -> notice.kind == GuidanceNoticeKind.STOP_LINE },
            "一時停止 (StopLine) が notice として残る",
        )
        // SourceRef が block / piece 単位で付与される。
        assertTrue(
            event.sourceRefs.any { ref -> ref.blockId != null && ref.pieceIndex != null },
            "piece 単位の SourceRef が付与される",
        )
    }

    @Test
    fun `GP に紐付かない施設付き intersection も通過施設イベントになる`() {
        val mapper = GuidanceRouteMapper()
        val route = buildRoute()
        val payload = ExtNavRoutePayload(id = route.id, routeGuidance = buildUncoveredFacilityGuidance())

        val guidanceRoute = mapper.map(payload = payload, route = route)

        // PA は GP から 300m 以上離れているため GP イベントには載らず、通過施設イベントとして補完される。
        val facilityEvent = guidanceRoute.events.first { event -> event.details.facility?.kind == FacilityKind.PA }
        assertNull(facilityEvent.primary)
        assertNull(facilityEvent.anchor.sourceGuidancePointIndex)
    }

    private fun buildUncoveredFacilityGuidance(): RouteGuidance = RouteGuidance(
        index = 1,
        priority = null,
        summary = DsrRouteSummary(
            depth = 0,
            distanceMetres = 1_000,
            timeSeconds = 300,
            fuelLitres = 0f,
            tollYen = 0,
            tollDetails = persistentListOf(),
            streets = persistentListOf(),
            priority = 0,
            trafficCongestionAvoidanceRate = 0f,
        ),
        guidancePoints = listOf(
            buildGuidancePoint(
                index = 0,
                distanceFromStartMetres = 100,
                category = GuidanceCategory.IntersectionGuide,
                laneInfo = null,
            ),
            buildGuidancePoint(
                index = 1,
                distanceFromStartMetres = 950,
                category = GuidanceCategory.Unspecified,
                laneInfo = null,
            ),
        ).toImmutableList(),
        intersections = persistentListOf(
            Intersection(
                id = 0,
                name = "テストPA",
                nameRuby = "",
                roadName = "",
                roadNameOfficial = "",
                roadNumberSign = "",
                directionSignA = "",
                directionSignAKana = "",
                directionSignB = "",
                directionSignBKana = "",
                position = Coord.fromDegrees(latDeg = ORIGIN_LATITUDE, lonDeg = ORIGIN_LONGITUDE + LONGITUDE_STEP * 2),
                angleIn = 0,
                angleOut = 0,
                direction = ManeuverDirection.Straight,
                imageRefs = persistentListOf(),
                facilityHint = GuidanceFacilityHint(kind = GuidanceFacilityKind.PARKING_AREA),
            ),
        ),
        imageIds = persistentListOf(),
        polyline = persistentListOf(),
    )

    private fun buildMixedBlockGuidance(): RouteGuidance = RouteGuidance(
        index = 1,
        priority = null,
        summary = DsrRouteSummary(
            depth = 0,
            distanceMetres = 1_000,
            timeSeconds = 300,
            fuelLitres = 0f,
            tollYen = 0,
            tollDetails = persistentListOf(),
            streets = persistentListOf(),
            priority = 0,
            trafficCongestionAvoidanceRate = 0f,
        ),
        guidancePoints = listOf(
            buildBlockGuidancePoint(
                index = 0,
                distanceFromStartMetres = 250,
                categories = listOf(GuidanceCategory.IntersectionGuide, GuidanceCategory.StopLine),
            ),
            buildBlockGuidancePoint(
                index = 1,
                distanceFromStartMetres = 900,
                categories = listOf(GuidanceCategory.Unspecified),
            ),
        ).toImmutableList(),
        intersections = persistentListOf(),
        imageIds = persistentListOf(),
        polyline = persistentListOf(),
    )

    private fun buildBlockGuidancePoint(
        index: Int,
        distanceFromStartMetres: Int,
        categories: List<GuidanceCategory>,
    ): GuidancePoint {
        val pieces = categories
            .map { category ->
                GuideAnnouncementPiece(
                    text = category.name,
                    ssml = null,
                    templateRef = null,
                    category = category,
                )
            }
            .toImmutableList()
        val block = GuideAnnouncementBlock(
            id = "block-$index",
            anchor = ExternalGuideAnchor(
                sourceDistanceFromStartMetres = distanceFromStartMetres.toDouble(),
                sourceGuidancePointIndex = index,
                sourceBlockIndex = 0,
            ),
            triggerDistanceMetres = 0,
            groupId = 0,
            window = null,
            pieces = pieces,
            hasBlankAnnouncementSlot = false,
            categories = categories.toImmutableSet(),
        )
        return GuidancePoint(
            index = index,
            gpType = 0,
            distanceFromPrevMetres = 0,
            distanceFromStartMetres = distanceFromStartMetres,
            phrases = persistentListOf(),
            announcementBlocks = persistentListOf(block),
            imageRefs = persistentListOf(),
            maneuver = ManeuverHint(
                angleIn = 0,
                angleOut = 0,
                direction = ManeuverDirection.Straight,
                laneInfo = null,
                specialNode = null,
                flagsGroup = persistentListOf(),
                mergeSide = null,
                facilityHint = null,
            ),
        )
    }

    private fun buildHighwayRoute(): RouteDetail {
        val points = (0..4).map { index ->
            RoutePoint(latitude = ORIGIN_LATITUDE, longitude = ORIGIN_LONGITUDE + LONGITUDE_STEP * index)
        }
        return RouteDetail(
            id = "route-mapper-highway-test",
            origin = points.first(),
            destination = points.last(),
            intermediateWaypoints = persistentListOf(),
            geometry = points.toImmutableList(),
            distanceMeters = 1_000.0,
            durationSeconds = 300.0,
            steps = persistentListOf(),
            roadClassSegments = persistentListOf(
                RoadClassSegment(
                    startPointIndex = 1,
                    endPointIndex = 3,
                    roadClass = RoadClass.HIGHWAY,
                ),
            ),
            tollFee = 320,
        )
    }

    private fun buildHighwayRouteGuidance(): RouteGuidance = RouteGuidance(
        index = 1,
        priority = null,
        summary = DsrRouteSummary(
            depth = 0,
            distanceMetres = 1_000,
            timeSeconds = 300,
            fuelLitres = 0f,
            tollYen = 320,
            tollDetails = persistentListOf(),
            streets = persistentListOf(),
            priority = 0,
            trafficCongestionAvoidanceRate = 0f,
        ),
        guidancePoints = listOf(
            buildFacilityGuidancePoint(
                index = 0,
                distanceFromStartMetres = 250,
                facilityKind = GuidanceFacilityKind.TOLL_GATE,
            ),
            buildGuidancePoint(
                index = 1,
                distanceFromStartMetres = 500,
                category = GuidanceCategory.Orbis,
                laneInfo = null,
            ),
        ).toImmutableList(),
        intersections = persistentListOf(
            Intersection(
                id = 0,
                name = "テスト料金所",
                nameRuby = "",
                roadName = "",
                roadNameOfficial = "",
                roadNumberSign = "",
                directionSignA = "東京方面",
                directionSignAKana = "",
                directionSignB = "",
                directionSignBKana = "",
                position = Coord.fromDegrees(latDeg = ORIGIN_LATITUDE, lonDeg = ORIGIN_LONGITUDE + LONGITUDE_STEP),
                angleIn = 0,
                angleOut = 0,
                direction = ManeuverDirection.Straight,
                imageRefs = persistentListOf(),
                facilityHint = GuidanceFacilityHint(kind = GuidanceFacilityKind.TOLL_GATE),
            ),
        ),
        imageIds = persistentListOf(),
        polyline = persistentListOf(),
    )

    private fun RouteGuidance.withFirstGuidancePointAndIntersectionImages(
        guidancePointImages: List<GuideImageRef>,
        intersectionImages: List<GuideImageRef>,
    ): RouteGuidance = copy(
        guidancePoints = guidancePoints
            .mapIndexed { index, guidancePoint ->
                guidancePoint.withImageRefsIfFirst(index, guidancePointImages)
            }
            .toImmutableList(),
        intersections = intersections
            .map { intersection -> intersection.copy(imageRefs = intersectionImages.toImmutableList()) }
            .toImmutableList(),
    )

    private fun GuidancePoint.withImageRefsIfFirst(
        index: Int,
        imageRefs: List<GuideImageRef>,
    ): GuidancePoint {
        if (index != 0) return this
        return copy(imageRefs = imageRefs.toImmutableList())
    }

    private fun buildFacilityGuidancePoint(
        index: Int,
        distanceFromStartMetres: Int,
        facilityKind: GuidanceFacilityKind,
    ): GuidancePoint = GuidancePoint(
        index = index,
        gpType = 0,
        distanceFromPrevMetres = 0,
        distanceFromStartMetres = distanceFromStartMetres,
        phrases = persistentListOf(
            SsmlPhrase(
                ssml = "facility",
                distanceMetres = 0,
                category = GuidanceCategory.Unspecified,
            ),
        ),
        announcementBlocks = persistentListOf(),
        imageRefs = persistentListOf(),
        maneuver = ManeuverHint(
            angleIn = 0,
            angleOut = 0,
            direction = ManeuverDirection.Straight,
            laneInfo = null,
            specialNode = null,
            flagsGroup = persistentListOf(),
            mergeSide = null,
            facilityHint = GuidanceFacilityHint(kind = facilityKind),
        ),
    )

    private fun buildRoute(): RouteDetail {
        val points = (0..4).map { index ->
            RoutePoint(latitude = ORIGIN_LATITUDE, longitude = ORIGIN_LONGITUDE + LONGITUDE_STEP * index)
        }
        return RouteDetail(
            id = "route-mapper-test",
            origin = points.first(),
            destination = points.last(),
            intermediateWaypoints = persistentListOf(),
            geometry = points.toImmutableList(),
            distanceMeters = 1_000.0,
            durationSeconds = 300.0,
            steps = persistentListOf(),
        )
    }

    private fun routeOrigin(): RoutePoint = RoutePoint(
        latitude = ORIGIN_LATITUDE,
        longitude = ORIGIN_LONGITUDE,
    )

    private fun buildRouteGuidance(): RouteGuidance = RouteGuidance(
        index = 1,
        priority = null,
        summary = DsrRouteSummary(
            depth = 0,
            distanceMetres = 1_000,
            timeSeconds = 300,
            fuelLitres = 0f,
            tollYen = 0,
            tollDetails = persistentListOf(),
            streets = persistentListOf(),
            priority = 0,
            trafficCongestionAvoidanceRate = 0f,
        ),
        guidancePoints = listOf(
            buildGuidancePoint(
                index = 0,
                distanceFromStartMetres = 200,
                category = GuidanceCategory.IntersectionGuide,
                laneInfo = null,
            ),
            buildGuidancePoint(
                index = 1,
                distanceFromStartMetres = 400,
                category = GuidanceCategory.Unspecified,
                laneInfo = buildLaneInfo(),
            ),
            buildGuidancePoint(
                index = 2,
                distanceFromStartMetres = 600,
                category = GuidanceCategory.Unspecified,
                laneInfo = null,
            ),
            buildGuidancePoint(
                index = 3,
                distanceFromStartMetres = 950,
                category = GuidanceCategory.Unspecified,
                laneInfo = null,
            ),
        ).toImmutableList(),
        intersections = persistentListOf(),
        imageIds = persistentListOf(),
        polyline = persistentListOf(),
    )

    private fun buildGuidancePoint(
        index: Int,
        distanceFromStartMetres: Int,
        category: GuidanceCategory,
        laneInfo: LaneInfo?,
        direction: ManeuverDirection = ManeuverDirection.Straight,
    ): GuidancePoint = GuidancePoint(
        index = index,
        gpType = 0,
        distanceFromPrevMetres = 0,
        distanceFromStartMetres = distanceFromStartMetres,
        phrases = persistentListOf(
            SsmlPhrase(
                ssml = category.name,
                distanceMetres = 0,
                category = category,
            ),
        ),
        announcementBlocks = persistentListOf(),
        imageRefs = persistentListOf(),
        maneuver = ManeuverHint(
            angleIn = 0,
            angleOut = 0,
            direction = direction,
            laneInfo = laneInfo,
            specialNode = null,
            flagsGroup = persistentListOf(),
            mergeSide = null,
            facilityHint = null,
        ),
    )

    private fun buildLaneInfo(): LaneInfo = LaneInfo(
        markers = listOf(
            LaneMarker(rawA = 0, rawB = 0),
            LaneMarker(rawA = 1, rawB = 0),
            LaneMarker(rawA = 0, rawB = 0),
        ).toImmutableList(),
        kind = 0,
    )

    private fun buildLaneGuidance(guidancePoint: GuidancePoint): RouteGuidance = RouteGuidance(
        index = 1,
        priority = null,
        summary = DsrRouteSummary(
            depth = 0,
            distanceMetres = 1_000,
            timeSeconds = 300,
            fuelLitres = 0f,
            tollYen = 0,
            tollDetails = persistentListOf(),
            streets = persistentListOf(),
            priority = 0,
            trafficCongestionAvoidanceRate = 0f,
        ),
        guidancePoints = persistentListOf(guidancePoint),
        intersections = persistentListOf(),
        imageIds = persistentListOf(),
        polyline = persistentListOf(),
    )

    private fun buildFlagsGroupGuidancePoint(entries: List<FlagsGroupEntry>): GuidancePoint = GuidancePoint(
        index = 0,
        gpType = 0,
        distanceFromPrevMetres = 0,
        distanceFromStartMetres = 400,
        phrases = persistentListOf(),
        announcementBlocks = persistentListOf(),
        imageRefs = persistentListOf(),
        maneuver = ManeuverHint(
            angleIn = 0,
            angleOut = 0,
            direction = ManeuverDirection.Straight,
            laneInfo = null,
            specialNode = null,
            flagsGroup = entries.toImmutableList(),
            mergeSide = null,
            facilityHint = null,
        ),
    )

    private companion object {
        private const val ORIGIN_LATITUDE: Double = 35.0
        private const val ORIGIN_LONGITUDE: Double = 139.0
        private const val LONGITUDE_STEP: Double = 0.0025
    }
}
