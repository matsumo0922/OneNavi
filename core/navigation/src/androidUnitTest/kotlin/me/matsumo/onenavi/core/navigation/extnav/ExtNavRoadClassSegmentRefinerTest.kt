package me.matsumo.onenavi.core.navigation.extnav

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import me.matsumo.onenavi.core.model.RoadClass
import me.matsumo.onenavi.core.model.RoadClassSegment
import me.matsumo.onenavi.core.model.RoutePoint
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [ExtNavRoadClassSegmentRefiner] の unit test。
 */
class ExtNavRoadClassSegmentRefinerTest {

    @Test
    fun `短距離 route は座標道路種別が全て一般道なら一般道へ補正する`() {
        val geometry = shortGeometry()
        val segments = persistentListOf(
            RoadClassSegment(
                startPointIndex = 0,
                endPointIndex = geometry.lastIndex,
                roadClass = RoadClass.HIGHWAY,
            ),
        )

        val refined = ExtNavRoadClassSegmentRefiner.refineShortRoute(
            routeDistanceMeters = 120.0,
            geometry = geometry,
            roadClassSegments = segments,
            roadClassSamples = listOf(RoadClass.ORDINARY, RoadClass.ORDINARY, RoadClass.ORDINARY),
        )

        assertEquals(1, refined.size)
        assertEquals(RoadClass.ORDINARY, refined.first().roadClass)
        assertEquals(0, refined.first().startPointIndex)
        assertEquals(geometry.lastIndex, refined.first().endPointIndex)
    }

    @Test
    fun `座標道路種別が一致しない場合は既存セグメントを維持する`() {
        val geometry = shortGeometry()
        val segments = persistentListOf(
            RoadClassSegment(
                startPointIndex = 0,
                endPointIndex = geometry.lastIndex,
                roadClass = RoadClass.HIGHWAY,
            ),
        )

        val refined = ExtNavRoadClassSegmentRefiner.refineShortRoute(
            routeDistanceMeters = 120.0,
            geometry = geometry,
            roadClassSegments = segments,
            roadClassSamples = listOf(RoadClass.ORDINARY, RoadClass.HIGHWAY, RoadClass.ORDINARY),
        )

        assertEquals(segments, refined)
    }

    @Test
    fun `短距離でない route は既存セグメントを維持する`() {
        val geometry = shortGeometry()
        val segments = persistentListOf(
            RoadClassSegment(
                startPointIndex = 0,
                endPointIndex = geometry.lastIndex,
                roadClass = RoadClass.HIGHWAY,
            ),
        )

        val refined = ExtNavRoadClassSegmentRefiner.refineShortRoute(
            routeDistanceMeters = 501.0,
            geometry = geometry,
            roadClassSegments = segments,
            roadClassSamples = listOf(RoadClass.ORDINARY, RoadClass.ORDINARY, RoadClass.ORDINARY),
        )

        assertEquals(segments, refined)
    }

    private fun shortGeometry() = listOf(
        RoutePoint(latitude = 35.0, longitude = 139.0),
        RoutePoint(latitude = 35.0, longitude = 139.0005),
        RoutePoint(latitude = 35.0, longitude = 139.001),
    ).toImmutableList()
}
