package me.matsumo.onenavi.core.navigation.extnav

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import me.matsumo.drive.supporter.api.core.model.Coord
import me.matsumo.drive.supporter.api.guidance.domain.DsrRouteSummary
import me.matsumo.drive.supporter.api.guidance.domain.GuidanceCategory
import me.matsumo.drive.supporter.api.guidance.domain.GuidanceFacilityHint
import me.matsumo.drive.supporter.api.guidance.domain.GuidanceFacilityKind
import me.matsumo.drive.supporter.api.guidance.domain.GuidancePoint
import me.matsumo.drive.supporter.api.guidance.domain.Intersection
import me.matsumo.drive.supporter.api.guidance.domain.LaneInfo
import me.matsumo.drive.supporter.api.guidance.domain.LaneMarker
import me.matsumo.drive.supporter.api.guidance.domain.ManeuverDirection
import me.matsumo.drive.supporter.api.guidance.domain.ManeuverHint
import me.matsumo.drive.supporter.api.guidance.domain.RouteGuidance
import me.matsumo.drive.supporter.api.guidance.domain.SsmlPhrase
import me.matsumo.onenavi.core.model.ManeuverType
import me.matsumo.onenavi.core.model.RoadClass
import me.matsumo.onenavi.core.model.RoadClassSegment
import me.matsumo.onenavi.core.model.RouteDetail
import me.matsumo.onenavi.core.model.RoutePoint
import me.matsumo.onenavi.core.navigation.newguidance.semantic.FacilityKind
import me.matsumo.onenavi.core.navigation.newguidance.semantic.GuidanceNoticeKind
import me.matsumo.onenavi.core.navigation.newguidance.semantic.HighwayBoundary
import me.matsumo.onenavi.core.navigation.newguidance.semantic.LaneLayout
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
    fun `最終 GP は ARRIVE の主案内になる`() {
        val mapper = GuidanceRouteMapper()
        val route = buildRoute()
        val payload = ExtNavRoutePayload(id = route.id, routeGuidance = buildRouteGuidance())

        val guidanceRoute = mapper.map(payload = payload, route = route)

        val arriveEvent = guidanceRoute.events.last()
        assertEquals(ManeuverType.ARRIVE, arriveEvent.primary?.type)
    }

    @Test
    fun `料金所 GP は施設と料金と境界を持ち主案内を持たない`() {
        val mapper = GuidanceRouteMapper()
        val route = buildHighwayRoute()
        val payload = ExtNavRoutePayload(id = route.id, routeGuidance = buildHighwayRouteGuidance())

        val guidanceRoute = mapper.map(payload = payload, route = route)

        val tollEvent = guidanceRoute.events.first { event ->
            event.details.facility?.kind == FacilityKind.TOLL_GATE
        }
        assertNull(tollEvent.primary)
        assertEquals(320, tollEvent.details.toll?.amountYen)
        assertEquals(HighwayBoundary.ENTRANCE, tollEvent.details.boundary)
        assertEquals("東京方面", tollEvent.details.signpost?.primary)
        assertEquals(320, guidanceRoute.tollTotalYen)
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
            speedLimit = null,
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
            direction = ManeuverDirection.Straight,
            laneInfo = laneInfo,
            specialNode = null,
            speedLimit = null,
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

    private companion object {
        private const val ORIGIN_LATITUDE: Double = 35.0
        private const val ORIGIN_LONGITUDE: Double = 139.0
        private const val LONGITUDE_STEP: Double = 0.0025
    }
}
