package me.matsumo.onenavi.core.navigation.newguidance.progress

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import me.matsumo.onenavi.core.model.ManeuverModifier
import me.matsumo.onenavi.core.model.ManeuverType
import me.matsumo.onenavi.core.model.RouteDetail
import me.matsumo.onenavi.core.model.RoutePoint
import me.matsumo.onenavi.core.navigation.extnav.RouteGeometryMath
import me.matsumo.onenavi.core.navigation.newguidance.model.FacilityPanelItem
import me.matsumo.onenavi.core.navigation.newguidance.model.GuidancePanelFacility
import me.matsumo.onenavi.core.navigation.newguidance.model.ManeuverPanelItem
import me.matsumo.onenavi.core.navigation.newguidance.model.TollPanelSubtitle
import me.matsumo.onenavi.core.navigation.newguidance.semantic.FacilityKind
import me.matsumo.onenavi.core.navigation.newguidance.semantic.GuidanceEvent
import me.matsumo.onenavi.core.navigation.newguidance.semantic.GuidanceEventDetails
import me.matsumo.onenavi.core.navigation.newguidance.semantic.GuidanceEventId
import me.matsumo.onenavi.core.navigation.newguidance.semantic.GuidanceManeuver
import me.matsumo.onenavi.core.navigation.newguidance.semantic.GuidanceRoute
import me.matsumo.onenavi.core.navigation.newguidance.semantic.RouteAnchor
import me.matsumo.onenavi.core.navigation.newguidance.semantic.StepFacility
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * [GuidanceProgressAdapter] の semantic → UI モデル射影テスト。
 */
class GuidanceProgressAdapterTest {

    @Test
    fun `通過施設と主案内をパネル行へ射影し到着は除外する`() {
        val selector = GuidanceRouteSelector()
        val adapter = GuidanceProgressAdapter()
        val guidanceRoute = buildRoute()
        val context = buildContext()

        val selection = selector.select(route = guidanceRoute, currentCumulativeMeters = 0.0)
        val projection = adapter.adapt(
            guidanceRoute = guidanceRoute,
            selection = selection,
            context = context,
            currentCumulativeMeters = 0.0,
            timestampMillis = 1_000L,
        )

        assertEquals(ManeuverType.TURN, projection.nextManeuver?.type)
        assertEquals(2, projection.nextManeuver?.guidancePointIndex)
        assertEquals(ManeuverType.ARRIVE, projection.followupManeuver?.type)

        // 到着は除外。残るのは主案内 (turn) と通過施設 (料金所) の 2 件で距離の降順。
        assertEquals(2, projection.panelItems.size)
        val maneuverItem = assertIs<ManeuverPanelItem>(projection.panelItems[0])
        assertEquals("maneuver-2", maneuverItem.id)

        val tollItem = assertIs<FacilityPanelItem>(projection.panelItems[1])
        assertEquals(GuidancePanelFacility.TOLL_GATE, tollItem.kind)
        assertEquals(TollPanelSubtitle(amountYen = 320), tollItem.subtitle)
    }

    private fun buildRoute(): GuidanceRoute = GuidanceRoute(
        totalDistanceMeters = 400.0,
        totalDurationSeconds = 300,
        tollTotalYen = 320,
        events = listOf(
            facilityEvent(id = "event-toll", geometryMeters = 100.0, kind = FacilityKind.TOLL_GATE),
            maneuverEvent(id = "event-2", guidancePointIndex = 2, geometryMeters = 200.0, type = ManeuverType.TURN),
            maneuverEvent(id = "event-5", guidancePointIndex = 5, geometryMeters = 400.0, type = ManeuverType.ARRIVE),
        ).toImmutableList(),
    )

    private fun buildContext(): RouteProjectionContext {
        val geometry = listOf(
            RoutePoint(latitude = 0.0, longitude = 0.0),
            RoutePoint(latitude = 0.0, longitude = 0.004),
        )
        val cumulativeMetres = RouteGeometryMath.cumulativeMetres(geometry)
        return RouteProjectionContext(
            route = RouteDetail(
                id = "adapter-test",
                origin = geometry.first(),
                destination = geometry.last(),
                intermediateWaypoints = persistentListOf(),
                geometry = geometry.toImmutableList(),
                distanceMeters = 400.0,
                durationSeconds = 300.0,
                steps = persistentListOf(),
            ),
            cumulativeMetres = cumulativeMetres,
            totalGeometryMetres = 400.0,
        )
    }

    private fun maneuverEvent(
        id: String,
        guidancePointIndex: Int,
        geometryMeters: Double,
        type: ManeuverType,
    ): GuidanceEvent = GuidanceEvent(
        id = GuidanceEventId(id),
        anchor = anchorAt(geometryMeters = geometryMeters, guidancePointIndex = guidancePointIndex),
        primary = GuidanceManeuver(
            type = type,
            modifier = ManeuverModifier.STRAIGHT,
            intersectionName = null,
            exitNumber = null,
        ),
        details = emptyDetails(facility = null),
        sourceRefs = persistentListOf(),
    )

    private fun facilityEvent(
        id: String,
        geometryMeters: Double,
        kind: FacilityKind,
    ): GuidanceEvent = GuidanceEvent(
        id = GuidanceEventId(id),
        anchor = anchorAt(geometryMeters = geometryMeters, guidancePointIndex = null),
        primary = null,
        details = emptyDetails(
            facility = StepFacility(kind = kind, name = "料金所", services = persistentListOf()),
        ),
        sourceRefs = persistentListOf(),
    )

    private fun anchorAt(
        geometryMeters: Double,
        guidancePointIndex: Int?,
    ): RouteAnchor = RouteAnchor(
        sourceDistanceFromStartMeters = geometryMeters,
        geometryDistanceFromStartMeters = geometryMeters,
        location = RoutePoint(latitude = 0.0, longitude = 0.0),
        sourceGuidancePointIndex = guidancePointIndex,
        sourceBlockIndex = null,
        matchErrorMeters = null,
    )

    private fun emptyDetails(facility: StepFacility?): GuidanceEventDetails = GuidanceEventDetails(
        facility = facility,
        lane = null,
        toll = null,
        signpost = null,
        boundary = null,
        roadName = null,
        notices = persistentListOf(),
    )
}
