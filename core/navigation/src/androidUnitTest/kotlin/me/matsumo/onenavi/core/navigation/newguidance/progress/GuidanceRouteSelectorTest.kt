package me.matsumo.onenavi.core.navigation.newguidance.progress

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import me.matsumo.onenavi.core.model.ManeuverModifier
import me.matsumo.onenavi.core.model.ManeuverType
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [GuidanceRouteSelector] のカーソル導出テスト。
 */
class GuidanceRouteSelectorTest {

    @Test
    fun `始点では次と次々の主案内を選び先行イベントを全て返す`() {
        val selector = GuidanceRouteSelector()
        val route = buildRoute()

        val selection = selector.select(route = route, currentCumulativeMeters = 0.0)

        assertEquals("turn", selection.nextPrimaryEvent?.id?.value)
        assertEquals("arrive", selection.followupPrimaryEvent?.id?.value)
        assertEquals(4, selection.eventsAfterCurrent.size)
    }

    @Test
    fun `通過済みのイベントは除外され主案内カーソルが進む`() {
        val selector = GuidanceRouteSelector()
        val route = buildRoute()

        val selection = selector.select(route = route, currentCumulativeMeters = 250.0)

        assertEquals("arrive", selection.nextPrimaryEvent?.id?.value)
        assertNull(selection.followupPrimaryEvent)
        // 距離 300 (facility) と 400 (arrive) の 2 件だけが先行イベントとして残る。
        assertEquals(2, selection.eventsAfterCurrent.size)
    }

    @Test
    fun `レーンを持つ通過イベントは先行イベントに残すが主案内カーソルにはしない`() {
        val selector = GuidanceRouteSelector()
        val route = GuidanceRoute(
            totalDistanceMeters = 400.0,
            totalDurationSeconds = 120,
            tollTotalYen = null,
            events = listOf(
                laneEvent(id = "toll-lane", geometryMeters = 150.0),
                maneuverEvent(id = "turn", geometryMeters = 250.0, type = ManeuverType.TURN),
            ).toImmutableList(),
        )

        val selection = selector.select(route = route, currentCumulativeMeters = 0.0)

        assertEquals("turn", selection.nextPrimaryEvent?.id?.value)
        assertTrue(selection.eventsAfterCurrent.any { event -> event.id.value == "toll-lane" })
    }

    private fun buildRoute(): GuidanceRoute = GuidanceRoute(
        totalDistanceMeters = 400.0,
        totalDurationSeconds = 120,
        tollTotalYen = null,
        events = listOf(
            facilityEvent(id = "facility-near", geometryMeters = 100.0),
            maneuverEvent(id = "turn", geometryMeters = 200.0, type = ManeuverType.TURN),
            facilityEvent(id = "facility-far", geometryMeters = 300.0),
            maneuverEvent(id = "arrive", geometryMeters = 400.0, type = ManeuverType.ARRIVE),
        ).toImmutableList(),
    )

    private fun maneuverEvent(
        id: String,
        geometryMeters: Double,
        type: ManeuverType,
    ): GuidanceEvent = GuidanceEvent(
        id = GuidanceEventId(id),
        anchor = anchorAt(geometryMeters),
        primary = GuidanceManeuver(
            type = type,
            modifier = ManeuverModifier.STRAIGHT,
            intersectionName = null,
            exitNumber = null,
        ),
        details = emptyDetails(),
        sourceRefs = persistentListOf(),
    )

    private fun facilityEvent(
        id: String,
        geometryMeters: Double,
    ): GuidanceEvent = GuidanceEvent(
        id = GuidanceEventId(id),
        anchor = anchorAt(geometryMeters),
        primary = null,
        details = emptyDetails(),
        sourceRefs = persistentListOf(),
    )

    private fun laneEvent(
        id: String,
        geometryMeters: Double,
    ): GuidanceEvent = GuidanceEvent(
        id = GuidanceEventId(id),
        anchor = anchorAt(geometryMeters),
        primary = null,
        details = GuidanceEventDetails(
            facility = null,
            lane = markerLane(),
            toll = null,
            signpost = null,
            boundary = null,
            roadName = null,
            notices = persistentListOf(),
        ),
        sourceRefs = persistentListOf(),
    )

    private fun markerLane(): GuidanceLane = GuidanceLane(
        layout = LaneLayout.MarkerLayout(
            lanes = listOf(LaneMark(rawA = 1, rawB = 0), LaneMark(rawA = 0, rawB = 0)).toImmutableList(),
            kind = 0,
        ),
        instruction = null,
        warning = null,
        sources = persistentSetOf(LaneSource.MARKER),
        confidence = LaneConfidence.MEDIUM,
        sourceRefs = persistentListOf(),
    )

    private fun anchorAt(geometryMeters: Double): RouteAnchor = RouteAnchor(
        sourceDistanceFromStartMeters = geometryMeters,
        geometryDistanceFromStartMeters = geometryMeters,
        location = RoutePoint(latitude = 0.0, longitude = 0.0),
        sourceGuidancePointIndex = null,
        sourceBlockIndex = null,
        matchErrorMeters = null,
    )

    private fun emptyDetails(): GuidanceEventDetails = GuidanceEventDetails(
        facility = null,
        lane = null,
        toll = null,
        signpost = null,
        boundary = null,
        roadName = null,
        notices = persistentListOf(),
    )
}
