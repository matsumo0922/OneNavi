package me.matsumo.onenavi.feature.map.components

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import me.matsumo.onenavi.core.model.ManeuverModifier
import me.matsumo.onenavi.core.model.ManeuverType
import me.matsumo.onenavi.core.model.RoadClass
import me.matsumo.onenavi.core.model.RoadClassSegment
import me.matsumo.onenavi.core.model.RoutePoint
import me.matsumo.onenavi.core.navigation.newguidance.presentation.ManeuverCallout
import me.matsumo.onenavi.feature.map.state.MapGeodesy
import me.matsumo.onenavi.feature.map.state.RouteMeterIndex
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [MapGuidanceManeuverArrowGeometry] の矢印 window 生成テスト。
 */
class MapGuidanceManeuverArrowGeometryTest {

    @Test
    fun specsは最大2件まで返す() {
        val routeMeterIndex = requireNotNull(RouteMeterIndex.from(routePoints()))
        val specs = MapGuidanceManeuverArrowGeometry.specs(
            routeId = "route",
            maneuvers = listOf(
                maneuver(distanceMeters = 100.0, guidancePointIndex = 1),
                maneuver(distanceMeters = 150.0, guidancePointIndex = 2),
                maneuver(distanceMeters = 200.0, guidancePointIndex = 3),
            ),
            routeMeterIndex = routeMeterIndex,
            currentCumulativeMeters = 0.0,
            roadClassSegments = persistentListOf(),
            fallbackRoadClass = RoadClass.ORDINARY,
        )

        assertEquals(2, specs.size)
        assertEquals("guidance-arrow-route-0-1", specs[0].id)
        assertEquals("guidance-arrow-route-1-2", specs[1].id)
    }

    @Test
    fun windowは現在位置から案内地点を通って先まで伸びる() {
        val routeMeterIndex = requireNotNull(RouteMeterIndex.from(routePoints()))
        val specs = MapGuidanceManeuverArrowGeometry.specs(
            routeId = "route",
            maneuvers = listOf(maneuver(distanceMeters = 120.0, guidancePointIndex = 1)),
            routeMeterIndex = routeMeterIndex,
            currentCumulativeMeters = 110.0,
            roadClassSegments = persistentListOf(),
            fallbackRoadClass = RoadClass.ORDINARY,
        )
        val points = specs.single().points

        assertApproximatelyEquals(
            expectedDistanceMeters = 110.0,
            actualDistanceMeters = distanceFromOrigin(points.first()),
        )
        assertTrue(
            points.any { point ->
                abs(distanceFromOrigin(point) - 120.0) < DISTANCE_TOLERANCE_METERS
            },
        )
        assertApproximatelyEquals(
            expectedDistanceMeters = 180.0,
            actualDistanceMeters = distanceFromOrigin(points.last()),
        )
    }

    @Test
    fun roadClassは案内地点少し先の道路種別を使う() {
        val routeMeterIndex = requireNotNull(RouteMeterIndex.from(routePoints()))
        val roadClassSegments = persistentListOf(
            RoadClassSegment(
                startPointIndex = 0,
                endPointIndex = 2,
                roadClass = RoadClass.ORDINARY,
            ),
            RoadClassSegment(
                startPointIndex = 2,
                endPointIndex = 5,
                roadClass = RoadClass.HIGHWAY,
            ),
        )
        val specs = MapGuidanceManeuverArrowGeometry.specs(
            routeId = "route",
            maneuvers = listOf(maneuver(distanceMeters = 95.0, guidancePointIndex = 1)),
            routeMeterIndex = routeMeterIndex,
            currentCumulativeMeters = 0.0,
            roadClassSegments = roadClassSegments,
            fallbackRoadClass = RoadClass.ORDINARY,
        )

        assertEquals(RoadClass.HIGHWAY, specs.single().roadClass)
    }

    @Test
    fun roadClassSegmentが無い場合はfallbackを使う() {
        val routeMeterIndex = requireNotNull(RouteMeterIndex.from(routePoints()))
        val specs = MapGuidanceManeuverArrowGeometry.specs(
            routeId = "route",
            maneuvers = listOf(maneuver(distanceMeters = 95.0, guidancePointIndex = 1)),
            routeMeterIndex = routeMeterIndex,
            currentCumulativeMeters = 0.0,
            roadClassSegments = persistentListOf(),
            fallbackRoadClass = RoadClass.HIGHWAY,
        )

        assertEquals(RoadClass.HIGHWAY, specs.single().roadClass)
    }

    private fun routePoints() = ROUTE_POINT_DISTANCES_METERS
        .map(::routePointAt)
        .toImmutableList()

    private fun routePointAt(distanceMeters: Double): RoutePoint {
        return MapGeodesy.destinationPoint(
            origin = ORIGIN,
            bearingDegrees = ROUTE_BEARING_DEGREES,
            distanceMeters = distanceMeters,
        )
    }

    private fun distanceFromOrigin(point: RoutePoint): Double {
        return MapGeodesy.haversineMeters(
            from = ORIGIN,
            to = point,
        )
    }

    private fun maneuver(
        distanceMeters: Double,
        guidancePointIndex: Int,
    ): ManeuverCallout {
        return ManeuverCallout(
            type = ManeuverType.TURN,
            modifier = ManeuverModifier.RIGHT,
            location = routePointAt(distanceMeters = distanceMeters),
            geometryDistanceFromStartMeters = distanceMeters,
            distanceToManeuverMeters = distanceMeters.toInt(),
            intersectionName = null,
            exitNumber = null,
            guidancePointIndex = guidancePointIndex,
        )
    }

    private fun assertApproximatelyEquals(
        expectedDistanceMeters: Double,
        actualDistanceMeters: Double,
    ) {
        assertTrue(
            actual = abs(expectedDistanceMeters - actualDistanceMeters) < DISTANCE_TOLERANCE_METERS,
            message = "expected=$expectedDistanceMeters actual=$actualDistanceMeters",
        )
    }

    /** テスト定数。 */
    private companion object {

        /** テスト route の始点。 */
        val ORIGIN = RoutePoint(
            latitude = 35.0,
            longitude = 139.0,
        )

        /** テスト route を東向きの直線にするための方位。 */
        const val ROUTE_BEARING_DEGREES = 90f

        /** Haversine / destinationPoint の丸め誤差を許容する距離。 */
        const val DISTANCE_TOLERANCE_METERS = 1.5

        /** テスト route を構成する始点からの距離列。 */
        val ROUTE_POINT_DISTANCES_METERS = listOf(
            0.0,
            50.0,
            100.0,
            150.0,
            200.0,
            250.0,
        )
    }
}
