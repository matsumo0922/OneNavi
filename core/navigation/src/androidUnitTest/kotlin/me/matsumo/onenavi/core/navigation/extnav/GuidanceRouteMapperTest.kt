package me.matsumo.onenavi.core.navigation.extnav

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import me.matsumo.drive.supporter.api.guidance.domain.DsrRouteSummary
import me.matsumo.drive.supporter.api.guidance.domain.GuidanceCategory
import me.matsumo.drive.supporter.api.guidance.domain.GuidancePoint
import me.matsumo.drive.supporter.api.guidance.domain.LaneInfo
import me.matsumo.drive.supporter.api.guidance.domain.LaneMarker
import me.matsumo.drive.supporter.api.guidance.domain.ManeuverDirection
import me.matsumo.drive.supporter.api.guidance.domain.ManeuverHint
import me.matsumo.drive.supporter.api.guidance.domain.RouteGuidance
import me.matsumo.drive.supporter.api.guidance.domain.SsmlPhrase
import me.matsumo.onenavi.core.model.ManeuverType
import me.matsumo.onenavi.core.model.RouteDetail
import me.matsumo.onenavi.core.model.RoutePoint
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
