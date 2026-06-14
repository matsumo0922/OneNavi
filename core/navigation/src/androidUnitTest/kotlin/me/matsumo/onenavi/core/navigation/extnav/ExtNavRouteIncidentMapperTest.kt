package me.matsumo.onenavi.core.navigation.extnav

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import me.matsumo.drive.supporter.api.core.model.Coord
import me.matsumo.drive.supporter.api.guidance.domain.DsrRouteSummary
import me.matsumo.drive.supporter.api.guidance.domain.RouteGuidance
import me.matsumo.onenavi.core.model.RouteIncidentMarkerCategory
import me.matsumo.onenavi.core.model.RoutePoint
import kotlin.test.Test
import kotlin.test.assertEquals
import me.matsumo.drive.supporter.api.guidance.domain.RouteIncident as ExtNavRouteIncident
import me.matsumo.drive.supporter.api.guidance.domain.RouteIncidentCategory as ExtNavRouteIncidentCategory

/**
 * [ExtNavRouteIncidentMapper] のルートインシデント変換テスト。
 */
class ExtNavRouteIncidentMapperTest {

    @Test
    fun `インシデントの種別と座標を中立モデルへ変換する`() {
        val routeGuidance = routeGuidanceWithIncidents(
            incidents = listOf(
                extNavIncident(
                    category = ExtNavRouteIncidentCategory.Accident,
                    coord = Coord.fromDegrees(35.1, 139.1),
                    displayText = "事故",
                    distanceFromStartMetres = 120,
                    polylinePointIndex = 0,
                    placeName = "○○IC",
                    roadNumbering = "E1",
                ),
                extNavIncident(
                    category = ExtNavRouteIncidentCategory.Regulation,
                    coord = Coord.fromDegrees(35.2, 139.2),
                    displayText = "工事",
                    distanceFromStartMetres = 240,
                    polylinePointIndex = 1,
                    placeName = null,
                    roadNumbering = null,
                ),
            ),
        )

        val geometry = routeGuidance.polyline.toRouteGeometry()

        val incidents = ExtNavRouteIncidentMapper.map(
            routeGuidance = routeGuidance,
            geometry = geometry,
        )

        assertEquals(2, incidents.size)
        assertEquals(RouteIncidentMarkerCategory.Accident, incidents[0].category)
        assertEquals(RouteIncidentMarkerCategory.Regulation, incidents[1].category)
        assertEquals(RoutePoint(latitude = 35.1, longitude = 139.1), incidents[0].coord)
        assertEquals("事故", incidents[0].displayText)
        assertEquals(120, incidents[0].distanceFromStartMeters)
        assertEquals(0, incidents[0].polylinePointIndex)
        assertEquals("○○IC", incidents[0].placeName)
        assertEquals("E1", incidents[0].roadNumbering)
        assertEquals(null, incidents[1].placeName)
        assertEquals(null, incidents[1].roadNumbering)
    }

    @Test
    fun `origin が追加された geometry では polyline index を補正する`() {
        val routeGuidance = routeGuidanceWithIncidents(
            incidents = listOf(
                extNavIncident(
                    category = ExtNavRouteIncidentCategory.Accident,
                    coord = Coord.fromDegrees(35.1, 139.1),
                    displayText = "事故",
                    distanceFromStartMetres = 120,
                    polylinePointIndex = 1,
                    placeName = null,
                    roadNumbering = null,
                ),
            ),
        )

        val prependedOrigin = RoutePoint(latitude = 35.0, longitude = 139.0)
        val geometry = (
            listOf(prependedOrigin) +
                routeGuidance.polyline.map { coord -> RoutePoint(coord.latDegrees, coord.lonDegrees) }
            ).toImmutableList()

        val incidents = ExtNavRouteIncidentMapper.map(
            routeGuidance = routeGuidance,
            geometry = geometry,
        )

        assertEquals(2, incidents.single().polylinePointIndex)
    }

    @Test
    fun `origin が追加された geometry ではインシデント距離を補正する`() {
        val routeGuidance = routeGuidanceWithIncidents(
            incidents = listOf(
                extNavIncident(
                    category = ExtNavRouteIncidentCategory.Regulation,
                    coord = Coord.fromDegrees(35.2, 139.2),
                    displayText = "工事",
                    distanceFromStartMetres = 500,
                    polylinePointIndex = 1,
                    placeName = null,
                    roadNumbering = null,
                ),
            ),
        )

        val prependedOrigin = RoutePoint(latitude = 35.0, longitude = 139.0)
        val geometry = (
            listOf(prependedOrigin) +
                routeGuidance.polyline.map { coord -> RoutePoint(coord.latDegrees, coord.lonDegrees) }
            ).toImmutableList()

        val incidents = ExtNavRouteIncidentMapper.map(
            routeGuidance = routeGuidance,
            geometry = geometry,
        )

        val cumulativeMetres = RouteGeometryMath.cumulativeMetres(geometry)
        val sourceStartMetres = cumulativeMetres[1]
        val expectedDistanceMeters = (sourceStartMetres + 500.0).toInt()

        assertEquals(expectedDistanceMeters, incidents.single().distanceFromStartMeters)
    }

    @Test
    fun `インシデントが無い場合は空リストを返す`() {
        val routeGuidance = routeGuidanceWithIncidents(incidents = emptyList())

        val incidents = ExtNavRouteIncidentMapper.map(
            routeGuidance = routeGuidance,
            geometry = persistentListOf(RoutePoint(latitude = 35.0, longitude = 139.0)),
        )

        assertEquals(0, incidents.size)
    }

    @Test
    fun `空文字の placeName と roadNumbering は null に変換される`() {
        val routeGuidance = routeGuidanceWithIncidents(
            incidents = listOf(
                extNavIncident(
                    category = ExtNavRouteIncidentCategory.Regulation,
                    coord = Coord.fromDegrees(35.1, 139.1),
                    displayText = "規制",
                    distanceFromStartMetres = 100,
                    polylinePointIndex = 0,
                    placeName = "",
                    roadNumbering = "  ",
                ),
            ),
        )

        val geometry = routeGuidance.polyline.toRouteGeometry()

        val incidents = ExtNavRouteIncidentMapper.map(
            routeGuidance = routeGuidance,
            geometry = geometry,
        )

        assertEquals(null, incidents.single().placeName)
        assertEquals(null, incidents.single().roadNumbering)
    }

    private fun routeGuidanceWithIncidents(incidents: List<ExtNavRouteIncident>): RouteGuidance {
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
            pointEvents = persistentListOf(),
            routeIncidents = incidents.toImmutableList(),
        )
    }

    private fun extNavIncident(
        category: ExtNavRouteIncidentCategory,
        coord: Coord,
        displayText: String,
        distanceFromStartMetres: Int,
        polylinePointIndex: Int,
        placeName: String?,
        roadNumbering: String?,
    ): ExtNavRouteIncident {
        return ExtNavRouteIncident(
            category = category,
            displayText = displayText,
            coord = coord,
            distanceFromStartMetres = distanceFromStartMetres,
            polylinePointIndex = polylinePointIndex,
            placeName = placeName,
            roadNumbering = roadNumbering,
            regulationDistanceHintMetres = 0,
        )
    }

    private fun List<Coord>.toRouteGeometry() = map { coord -> RoutePoint(coord.latDegrees, coord.lonDegrees) }.toImmutableList()
}
