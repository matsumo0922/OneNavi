package me.matsumo.onenavi.feature.map

import kotlinx.collections.immutable.persistentListOf
import me.matsumo.onenavi.core.model.RouteWaypoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** ルート waypoint 追加計算のテスト。 */
class MapRouteWaypointPlannerTest {

    @Test
    fun addWaypointToRoutePreviewInsertsWaypointBetweenOriginAndDestination() {
        val originWaypoint = RouteWaypoint.CurrentLocation(
            latitude = 0.0,
            longitude = 0.0,
        )
        val destinationWaypoint = place(
            name = "destination",
            latitude = 0.0,
            longitude = 10.0,
        )
        val addedWaypoint = place(
            name = "added",
            latitude = 0.0,
            longitude = 5.0,
        )
        val waypoints = persistentListOf<RouteWaypoint>(
            originWaypoint,
            destinationWaypoint,
        )

        val result = MapRouteWaypointPlanner.addWaypointToRoutePreview(
            waypoints = waypoints,
            waypoint = addedWaypoint,
        )

        assertEquals(
            expected = listOf("origin", "added", "destination"),
            actual = result?.displayNames(),
        )
    }

    @Test
    fun addWaypointToRoutePreviewInsertsWaypointIntoMinimalDetourSegment() {
        val originWaypoint = RouteWaypoint.CurrentLocation(
            latitude = 0.0,
            longitude = 0.0,
        )
        val existingWaypoint = place(
            name = "existing",
            latitude = 0.0,
            longitude = 3.0,
        )
        val destinationWaypoint = place(
            name = "destination",
            latitude = 0.0,
            longitude = 10.0,
        )
        val addedWaypoint = place(
            name = "added",
            latitude = 0.0,
            longitude = 8.0,
        )
        val waypoints = persistentListOf<RouteWaypoint>(
            originWaypoint,
            existingWaypoint,
            destinationWaypoint,
        )

        val result = MapRouteWaypointPlanner.addWaypointToRoutePreview(
            waypoints = waypoints,
            waypoint = addedWaypoint,
        )

        assertEquals(
            expected = listOf("origin", "existing", "added", "destination"),
            actual = result?.displayNames(),
        )
    }

    @Test
    fun addWaypointToRoutePreviewReturnsNullWhenWaypointsAreFull() {
        val waypoints = persistentListOf<RouteWaypoint>(
            RouteWaypoint.CurrentLocation(
                latitude = 0.0,
                longitude = 0.0,
            ),
            place(
                name = "first",
                latitude = 0.0,
                longitude = 1.0,
            ),
            place(
                name = "second",
                latitude = 0.0,
                longitude = 2.0,
            ),
            place(
                name = "third",
                latitude = 0.0,
                longitude = 3.0,
            ),
            place(
                name = "destination",
                latitude = 0.0,
                longitude = 4.0,
            ),
        )
        val addedWaypoint = place(
            name = "added",
            latitude = 0.0,
            longitude = 2.5,
        )

        val result = MapRouteWaypointPlanner.addWaypointToRoutePreview(
            waypoints = waypoints,
            waypoint = addedWaypoint,
        )

        assertNull(result)
    }

    private fun place(
        name: String,
        latitude: Double,
        longitude: Double,
    ): RouteWaypoint.Place {
        return RouteWaypoint.Place(
            name = name,
            latitude = latitude,
            longitude = longitude,
        )
    }

    private fun List<RouteWaypoint>.displayNames(): List<String> {
        return map { waypoint ->
            when (waypoint) {
                is RouteWaypoint.CurrentLocation -> "origin"
                is RouteWaypoint.Place -> waypoint.name
            }
        }
    }
}
