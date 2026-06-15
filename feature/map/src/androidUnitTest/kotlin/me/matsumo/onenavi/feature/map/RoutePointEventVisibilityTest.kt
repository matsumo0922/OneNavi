package me.matsumo.onenavi.feature.map

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import me.matsumo.onenavi.core.model.RouteDetail
import me.matsumo.onenavi.core.model.RoutePoint
import me.matsumo.onenavi.core.model.RoutePointEvent
import me.matsumo.onenavi.core.model.RoutePointEventKind
import kotlin.test.Test
import kotlin.test.assertEquals

/** [RoutePointEventVisibility] の表示範囲判定テスト。 */
class RoutePointEventVisibilityTest {

    @Test
    fun visiblePointEventsStopsAtGuidanceTargetDistance() {
        val route = routeDetail(
            pointEvents = listOf(
                pointEvent(distanceFromStartMeters = 110.0),
                pointEvent(distanceFromStartMeters = 150.0),
                pointEvent(distanceFromStartMeters = 230.0),
            ),
        )

        val visiblePointEvents = RoutePointEventVisibility.visiblePointEvents(
            route = route,
            routeProgressMeters = 100.0,
            guidanceTargetDistanceFromStartMeters = 180.0,
        )

        assertEquals(
            expected = listOf(110.0, 150.0),
            actual = visiblePointEvents.map { pointEvent -> pointEvent.distanceFromStartMeters },
        )
    }

    @Test
    fun visiblePointEventsKeepsNonTrafficLightEventsBeyondGuidanceTargetDistance() {
        val route = routeDetail(
            pointEvents = listOf(
                pointEvent(
                    kind = RoutePointEventKind.TRAFFIC_LIGHT,
                    distanceFromStartMeters = 110.0,
                ),
                pointEvent(
                    kind = RoutePointEventKind.STOP_LINE,
                    distanceFromStartMeters = 230.0,
                ),
                pointEvent(
                    kind = RoutePointEventKind.TRAFFIC_LIGHT,
                    distanceFromStartMeters = 240.0,
                ),
            ),
        )

        val visiblePointEvents = RoutePointEventVisibility.visiblePointEvents(
            route = route,
            routeProgressMeters = 100.0,
            guidanceTargetDistanceFromStartMeters = 180.0,
        )

        assertEquals(
            expected = listOf(
                RoutePointEventKind.TRAFFIC_LIGHT,
                RoutePointEventKind.STOP_LINE,
            ),
            actual = visiblePointEvents.map { pointEvent -> pointEvent.kind },
        )
    }

    @Test
    fun visiblePointEventsIncludesTargetDistanceTolerance() {
        val route = routeDetail(
            pointEvents = listOf(
                pointEvent(distanceFromStartMeters = 180.5),
                pointEvent(distanceFromStartMeters = 181.5),
            ),
        )

        val visiblePointEvents = RoutePointEventVisibility.visiblePointEvents(
            route = route,
            routeProgressMeters = 100.0,
            guidanceTargetDistanceFromStartMeters = 180.0,
        )

        assertEquals(
            expected = listOf(180.5),
            actual = visiblePointEvents.map { pointEvent -> pointEvent.distanceFromStartMeters },
        )
    }

    @Test
    fun visiblePointEventsFallsBackWhenGuidanceTargetDistanceIsBehindProgress() {
        val route = routeDetail(
            pointEvents = listOf(
                pointEvent(distanceFromStartMeters = 120.0),
                pointEvent(distanceFromStartMeters = 210.0),
                pointEvent(distanceFromStartMeters = 230.0),
            ),
        )

        val visiblePointEvents = RoutePointEventVisibility.visiblePointEvents(
            route = route,
            routeProgressMeters = 200.0,
            guidanceTargetDistanceFromStartMeters = 150.0,
        )

        assertEquals(
            expected = listOf(210.0, 230.0),
            actual = visiblePointEvents.map { pointEvent -> pointEvent.distanceFromStartMeters },
        )
    }

    private fun routeDetail(pointEvents: List<RoutePointEvent>): RouteDetail {
        return RouteDetail(
            id = "route",
            origin = routePoint(),
            destination = routePoint(),
            intermediateWaypoints = persistentListOf(),
            geometry = persistentListOf(),
            distanceMeters = 1_000.0,
            durationSeconds = 600.0,
            steps = persistentListOf(),
            pointEvents = pointEvents.toImmutableList(),
        )
    }

    private fun pointEvent(
        kind: RoutePointEventKind = RoutePointEventKind.TRAFFIC_LIGHT,
        distanceFromStartMeters: Double,
    ): RoutePointEvent {
        return RoutePointEvent(
            kind = kind,
            location = routePoint(),
            distanceFromStartMeters = distanceFromStartMeters,
            polylinePointIndex = 0,
        )
    }

    private fun routePoint(): RoutePoint {
        return RoutePoint(
            latitude = 0.0,
            longitude = 0.0,
        )
    }
}
