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
    fun `location at start keeps progress near zero`() {
        val tracker = ExtNavGuidanceTracker()
        val guidance = buildGuidance()
        tracker.attach(guidance)

        val start = guidance.polyline.first()
        tracker.onLocation(start.latDegrees, start.lonDegrees)

        val snapshot = assertNotNull(tracker.state.value)
        assertTrue(snapshot.progressedMetres < 5.0)
        assertTrue(snapshot.nearestIntersectionDistanceMetres < 5.0)
        assertTrue(snapshot.remainingMetres > 0.0)
    }

    @Test
    fun `moving along polyline advances progressed metres`() {
        val tracker = ExtNavGuidanceTracker()
        val guidance = buildGuidance()
        tracker.attach(guidance)

        val start = guidance.polyline.first()
        tracker.onLocation(start.latDegrees, start.lonDegrees)
        val startProgress = assertNotNull(tracker.state.value).progressedMetres

        val middle = guidance.polyline[guidance.polyline.size / 2]
        tracker.onLocation(middle.latDegrees, middle.lonDegrees)
        val midProgress = assertNotNull(tracker.state.value).progressedMetres

        assertTrue(
            midProgress > startProgress + 100.0,
            "progress should advance; start=$startProgress mid=$midProgress",
        )
    }

    @Test
    fun `monotonic clamp rejects large backward jump`() {
        val tracker = ExtNavGuidanceTracker()
        val guidance = buildGuidance()
        tracker.attach(guidance)

        val far = guidance.polyline.last()
        tracker.onLocation(far.latDegrees, far.lonDegrees)
        val farProgress = assertNotNull(tracker.state.value).progressedMetres

        val start = guidance.polyline.first()
        tracker.onLocation(start.latDegrees, start.lonDegrees)
        val afterBackward = assertNotNull(tracker.state.value).progressedMetres

        assertEquals(farProgress, afterBackward, 0.001)
    }

    @Test
    fun `next intersection advances as progress passes each one`() {
        val tracker = ExtNavGuidanceTracker()
        val guidance = buildGuidance()
        tracker.attach(guidance)

        tracker.onLocation(
            guidance.polyline.first().latDegrees,
            guidance.polyline.first().lonDegrees,
        )
        val firstSnapshot = assertNotNull(tracker.state.value)
        assertEquals(guidance.intersections.first().id, firstSnapshot.nextIntersection?.id)

        val afterFirstIntersection = guidance.intersections[0].position
        tracker.onLocation(
            afterFirstIntersection.latDegrees + 0.0005,
            afterFirstIntersection.lonDegrees,
        )
        val secondSnapshot = assertNotNull(tracker.state.value)
        assertEquals(guidance.intersections[1].id, secondSnapshot.nextIntersection?.id)
    }

    @Test
    fun `next guidance point tracks progress`() {
        val tracker = ExtNavGuidanceTracker()
        val guidance = buildGuidance()
        tracker.attach(guidance)

        tracker.onLocation(
            guidance.polyline.first().latDegrees,
            guidance.polyline.first().lonDegrees,
        )
        val initial = assertNotNull(tracker.state.value).nextGuidancePoint
        assertEquals(0, initial?.index)

        val threeQuarters = guidance.polyline[(guidance.polyline.size * 3) / 4]
        tracker.onLocation(threeQuarters.latDegrees, threeQuarters.lonDegrees)
        val later = assertNotNull(tracker.state.value).nextGuidancePoint
        assertTrue((later?.index ?: 0) >= 2, "next gp should advance; got=${later?.index}")
    }

    private fun buildGuidance(): Guidance {
        val intersections = listOf(
            intersection(id = 1, lat = 35.6812, lng = 139.7671),
            intersection(id = 2, lat = 35.6912, lng = 139.7671),
            intersection(id = 3, lat = 35.7012, lng = 139.7671),
            intersection(id = 4, lat = 35.7112, lng = 139.7671),
        ).toImmutableList()

        // intersections を等間隔補間した polyline を 40 点作る (≒ 110m 間隔)
        val polyline = buildList {
            val steps = 40
            for (pointIndex in 0..steps) {
                val ratio = pointIndex.toDouble() / steps
                val lat = intersections.first().position.latDegrees +
                    ratio * (intersections.last().position.latDegrees -
                        intersections.first().position.latDegrees)
                add(Coord.fromDegrees(lat, 139.7671))
            }
        }.toImmutableList()

        val guidancePoints = listOf(
            guidancePoint(index = 0, distanceFromStartMetres = 100),
            guidancePoint(index = 1, distanceFromStartMetres = 1500),
            guidancePoint(index = 2, distanceFromStartMetres = 2500),
            guidancePoint(index = 3, distanceFromStartMetres = 3500),
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
            polyline = polyline,
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
