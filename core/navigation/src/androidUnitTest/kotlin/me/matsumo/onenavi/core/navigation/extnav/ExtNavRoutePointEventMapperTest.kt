package me.matsumo.onenavi.core.navigation.extnav

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import me.matsumo.drive.supporter.api.core.model.Coord
import me.matsumo.drive.supporter.api.guidance.domain.DsrRouteSummary
import me.matsumo.drive.supporter.api.guidance.domain.RouteGuidance
import me.matsumo.onenavi.core.model.RoutePoint
import me.matsumo.onenavi.core.model.RoutePointEventKind
import kotlin.test.Test
import kotlin.test.assertEquals
import me.matsumo.drive.supporter.api.guidance.domain.RoutePointEvent as ExtNavRoutePointEvent
import me.matsumo.drive.supporter.api.guidance.domain.RoutePointEventKind as ExtNavRoutePointEventKind

/**
 * [ExtNavRoutePointEventMapper] の地点イベント変換テスト。
 */
class ExtNavRoutePointEventMapperTest {

    @Test
    fun `地点イベントの種別と座標を中立モデルへ変換する`() {
        val routeGuidance = routeGuidanceWithPointEvents(
            pointEvents = listOf(
                extNavPointEvent(
                    kind = ExtNavRoutePointEventKind.TrafficLight,
                    coord = Coord.fromDegrees(35.1, 139.1),
                    distanceFromStartMetres = 120.0,
                    polylinePointIndex = 0,
                ),
                extNavPointEvent(
                    kind = ExtNavRoutePointEventKind.StopLine,
                    coord = Coord.fromDegrees(35.2, 139.2),
                    distanceFromStartMetres = 240.0,
                    polylinePointIndex = 1,
                ),
                extNavPointEvent(
                    kind = ExtNavRoutePointEventKind.RailwayCrossing,
                    coord = Coord.fromDegrees(35.3, 139.3),
                    distanceFromStartMetres = 360.0,
                    polylinePointIndex = 2,
                ),
            ),
        )

        val events = ExtNavRoutePointEventMapper.map(
            routeGuidance = routeGuidance,
            geometry = routeGuidance.polyline
                .map { coord -> RoutePoint(coord.latDegrees, coord.lonDegrees) }
                .toImmutableList(),
        )

        assertEquals(
            listOf(
                RoutePointEventKind.TRAFFIC_LIGHT,
                RoutePointEventKind.STOP_LINE,
                RoutePointEventKind.RAILWAY_CROSSING,
            ),
            events.map { event -> event.kind },
        )
        assertEquals(RoutePoint(latitude = 35.1, longitude = 139.1), events[0].location)
        assertEquals(240.0, events[1].distanceFromStartMeters)
        assertEquals(2, events[2].polylinePointIndex)
    }

    @Test
    fun `origin が追加された geometry では polyline index を補正する`() {
        val routeGuidance = routeGuidanceWithPointEvents(
            pointEvents = listOf(
                extNavPointEvent(
                    kind = ExtNavRoutePointEventKind.TrafficLight,
                    coord = Coord.fromDegrees(35.1, 139.1),
                    distanceFromStartMetres = 120.0,
                    polylinePointIndex = 1,
                ),
            ),
        )
        val prependedOrigin = RoutePoint(latitude = 35.0, longitude = 139.0)
        val geometry = (
            listOf(prependedOrigin) +
                routeGuidance.polyline.map { coord -> RoutePoint(coord.latDegrees, coord.lonDegrees) }
            ).toImmutableList()

        val events = ExtNavRoutePointEventMapper.map(
            routeGuidance = routeGuidance,
            geometry = geometry,
        )

        assertEquals(2, events.single().polylinePointIndex)
    }

    @Test
    fun `地点イベントが無い場合は空リストを返す`() {
        val routeGuidance = routeGuidanceWithPointEvents(pointEvents = emptyList())

        val events = ExtNavRoutePointEventMapper.map(
            routeGuidance = routeGuidance,
            geometry = persistentListOf(RoutePoint(latitude = 35.0, longitude = 139.0)),
        )

        assertEquals(0, events.size)
    }

    private fun routeGuidanceWithPointEvents(pointEvents: List<ExtNavRoutePointEvent>): RouteGuidance {
        return RouteGuidance(
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
            guidancePoints = persistentListOf(),
            intersections = persistentListOf(),
            imageIds = persistentListOf(),
            polyline = listOf(
                Coord.fromDegrees(35.1, 139.1),
                Coord.fromDegrees(35.2, 139.2),
                Coord.fromDegrees(35.3, 139.3),
            ).toImmutableList(),
            pointEvents = pointEvents.toImmutableList(),
        )
    }

    private fun extNavPointEvent(
        kind: ExtNavRoutePointEventKind,
        coord: Coord,
        distanceFromStartMetres: Double,
        polylinePointIndex: Int,
    ): ExtNavRoutePointEvent {
        return ExtNavRoutePointEvent(
            kind = kind,
            coord = coord,
            distanceFromStartMetres = distanceFromStartMetres,
            polylinePointIndex = polylinePointIndex,
        )
    }
}
