package me.matsumo.onenavi.core.navigation.extnav

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import me.matsumo.drive.supporter.api.core.model.Coord
import me.matsumo.drive.supporter.api.guidance.domain.DsrRouteSummary
import me.matsumo.drive.supporter.api.guidance.domain.Guidance
import me.matsumo.drive.supporter.api.guidance.domain.GuidancePoint
import me.matsumo.drive.supporter.api.guidance.domain.Intersection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExtNavGuidanceTrackerTest {

    @Test
    fun `attach then no location returns null snapshot`() {
        val tracker = ExtNavGuidanceTracker()
        tracker.attach(buildGuidance())
        assertNull(tracker.state.value)
    }

    @Test
    fun `nearest intersection at start position picks index 0`() {
        val tracker = ExtNavGuidanceTracker()
        val guidance = buildGuidance()
        tracker.attach(guidance)

        val first = guidance.intersections.first().position
        tracker.onLocation(first.latDegrees, first.lonDegrees)

        val snapshot = assertNotNull(tracker.state.value)
        assertEquals(0, snapshot.nearestIntersectionIndex)
        assertTrue(snapshot.nearestIntersectionDistanceMetres < 5.0)
        assertEquals(0.0, snapshot.progressedMetres)
        assertTrue(snapshot.remainingMetres > 0.0)
    }

    @Test
    fun `nearest intersection advances progressed distance`() {
        val tracker = ExtNavGuidanceTracker()
        val guidance = buildGuidance()
        tracker.attach(guidance)

        val third = guidance.intersections[2].position
        tracker.onLocation(third.latDegrees, third.lonDegrees)

        val snapshot = assertNotNull(tracker.state.value)
        assertEquals(2, snapshot.nearestIntersectionIndex)
        assertTrue(snapshot.progressedMetres > 0.0)
        assertTrue(snapshot.remainingMetres < guidance.summary.distanceMetres.toDouble())
    }

    @Test
    fun `upcoming guidance point is the next one ahead of progress`() {
        val tracker = ExtNavGuidanceTracker()
        val guidance = buildGuidance()
        tracker.attach(guidance)

        val first = guidance.intersections.first().position
        tracker.onLocation(first.latDegrees, first.lonDegrees)

        val snapshot = assertNotNull(tracker.state.value)
        val nextGp = assertNotNull(snapshot.nextGuidancePoint)
        assertEquals(guidance.guidancePoints.first().index, nextGp.index)
    }

    private fun buildGuidance(): Guidance {
        // 東京駅付近の 4 点を 0.01 度 (≒ 1.1 km) 刻みで並べたダミールート
        val intersections = listOf(
            intersection(id = 1, lat = 35.6812, lng = 139.7671),
            intersection(id = 2, lat = 35.6912, lng = 139.7671),
            intersection(id = 3, lat = 35.7012, lng = 139.7671),
            intersection(id = 4, lat = 35.7112, lng = 139.7671),
        ).toImmutableList()

        val guidancePoints = listOf(
            guidancePoint(index = 0, distanceFromStartMetres = 100),
            guidancePoint(index = 1, distanceFromStartMetres = 1500),
            guidancePoint(index = 2, distanceFromStartMetres = 3000),
        ).toImmutableList()

        return Guidance(
            summary = DsrRouteSummary(
                depth = 1,
                distanceMetres = 4400,
                timeSeconds = 600,
                fuelLitres = 0f,
                tollYen = 0,
                tollDetails = persistentListOf(),
                streets = persistentListOf(),
                priority = 0,
                trafficCongestionAvoidanceRate = 0f,
            ),
            guidancePoints = guidancePoints,
            intersections = intersections,
            imageIds = persistentListOf(),
        )
    }

    private fun intersection(id: Int, lat: Double, lng: Double): Intersection = Intersection(
        id = id,
        name = "point-$id",
        nameRuby = "",
        roadName = "",
        directionSignA = "",
        directionSignAKana = "",
        directionSignB = "",
        directionSignBKana = "",
        position = Coord.fromDegrees(lat, lng),
        approachAngle = 0,
        imageRefs = persistentListOf(),
    )

    private fun guidancePoint(index: Int, distanceFromStartMetres: Int): GuidancePoint = GuidancePoint(
        index = index,
        gpType = 0,
        distanceFromPrevMetres = 0,
        distanceFromStartMetres = distanceFromStartMetres,
        phrases = persistentListOf(),
        imageRefs = persistentListOf(),
    )
}
