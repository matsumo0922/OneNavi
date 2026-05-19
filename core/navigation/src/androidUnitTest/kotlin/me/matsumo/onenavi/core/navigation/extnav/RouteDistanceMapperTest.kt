package me.matsumo.onenavi.core.navigation.extnav

import kotlin.test.Test
import kotlin.test.assertEquals

class RouteDistanceMapperTest {

    @Test
    fun `empty anchors maps to zero`() {
        val mapper = RouteDistanceMapper(anchors = emptyList())

        assertEquals(0.0, mapper.mapSourceToGeometry(100.0))
    }

    @Test
    fun `source distance is clamped to first and last anchors`() {
        val mapper = RouteDistanceMapper(
            anchors = listOf(
                DistanceAnchor(sourceMetres = 100.0, geometryMetres = 200.0),
                DistanceAnchor(sourceMetres = 1_000.0, geometryMetres = 2_000.0),
            ),
        )

        assertEquals(200.0, mapper.mapSourceToGeometry(0.0))
        assertEquals(2_000.0, mapper.mapSourceToGeometry(2_000.0))
    }

    @Test
    fun `distance between two anchors is linearly interpolated`() {
        val mapper = RouteDistanceMapper(
            anchors = listOf(
                DistanceAnchor(sourceMetres = 0.0, geometryMetres = 0.0),
                DistanceAnchor(sourceMetres = 1_000.0, geometryMetres = 2_000.0),
            ),
        )

        assertEquals(500.0, mapper.mapSourceToGeometry(250.0), absoluteTolerance = 0.001)
        assertEquals(1_000.0, mapper.mapSourceToGeometry(500.0), absoluteTolerance = 0.001)
    }

    @Test
    fun `intermediate anchor changes local scale`() {
        val mapper = RouteDistanceMapper(
            anchors = listOf(
                DistanceAnchor(sourceMetres = 0.0, geometryMetres = 0.0),
                DistanceAnchor(sourceMetres = 500.0, geometryMetres = 700.0),
                DistanceAnchor(sourceMetres = 1_000.0, geometryMetres = 1_000.0),
            ),
        )

        assertEquals(350.0, mapper.mapSourceToGeometry(250.0), absoluteTolerance = 0.001)
        assertEquals(850.0, mapper.mapSourceToGeometry(750.0), absoluteTolerance = 0.001)
    }

    @Test
    fun `anchors are sorted deduplicated and filtered to monotonic pairs`() {
        val mapper = RouteDistanceMapper(
            anchors = listOf(
                DistanceAnchor(sourceMetres = 1_000.0, geometryMetres = 1_000.0),
                DistanceAnchor(sourceMetres = 500.0, geometryMetres = 900.0),
                DistanceAnchor(sourceMetres = 700.0, geometryMetres = 650.0),
                DistanceAnchor(sourceMetres = 500.0, geometryMetres = 700.0),
                DistanceAnchor(sourceMetres = 0.0, geometryMetres = 0.0),
            ),
        )

        assertEquals(850.0, mapper.mapSourceToGeometry(750.0), absoluteTolerance = 0.001)
    }
}
