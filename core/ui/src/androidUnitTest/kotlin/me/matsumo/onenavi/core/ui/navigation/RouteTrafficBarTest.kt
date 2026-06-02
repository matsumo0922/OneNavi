package me.matsumo.onenavi.core.ui.navigation

import kotlinx.collections.immutable.persistentListOf
import me.matsumo.onenavi.core.model.CongestionSegment
import me.matsumo.onenavi.core.model.CongestionSeverity
import me.matsumo.onenavi.core.model.RoadClass
import me.matsumo.onenavi.core.model.RoadClassSegment
import me.matsumo.onenavi.core.model.RoutePoint
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [RouteTrafficBar] の描画用線分計算テスト。
 */
class RouteTrafficBarTest {

    @Test
    fun calculateRouteTrafficBarLayout_whenRoadClassSegmentsAreEmpty_fallsBackToOrdinary() {
        val geometry = equatorGeometry(pointCount = 3)
        val currentMeters = equatorStepMeters(stepCount = 1)

        val actual = calculateRouteTrafficBarLayout(
            geometry = geometry,
            currentCumulativeMeters = currentMeters,
            roadClassSegments = persistentListOf(),
            congestionSegments = persistentListOf(),
        )

        assertApproximatelyEquals(0.5f, actual.markerRatio)
        assertEquals(
            listOf(RouteTrafficBarSegmentKind.PASSED, RouteTrafficBarSegmentKind.ORDINARY),
            actual.segments.map { segment -> segment.kind },
        )
        assertSegment(
            segment = actual.segments[0],
            startRatio = 0f,
            endRatio = 0.5f,
            kind = RouteTrafficBarSegmentKind.PASSED,
        )
        assertSegment(
            segment = actual.segments[1],
            startRatio = 0.5f,
            endRatio = 1f,
            kind = RouteTrafficBarSegmentKind.ORDINARY,
        )
    }

    @Test
    fun calculateRouteTrafficBarLayout_mapsRoadClassSegmentsToRouteRatios() {
        val geometry = equatorGeometry(pointCount = 4)

        val actual = calculateRouteTrafficBarLayout(
            geometry = geometry,
            currentCumulativeMeters = 0.0,
            roadClassSegments = persistentListOf(
                RoadClassSegment(
                    startPointIndex = 0,
                    endPointIndex = 1,
                    roadClass = RoadClass.ORDINARY,
                ),
                RoadClassSegment(
                    startPointIndex = 1,
                    endPointIndex = 3,
                    roadClass = RoadClass.HIGHWAY,
                ),
            ),
            congestionSegments = persistentListOf(),
        )

        assertEquals(
            listOf(RouteTrafficBarSegmentKind.ORDINARY, RouteTrafficBarSegmentKind.HIGHWAY),
            actual.segments.map { segment -> segment.kind },
        )
        assertSegment(
            segment = actual.segments[0],
            startRatio = 0f,
            endRatio = 1f / 3f,
            kind = RouteTrafficBarSegmentKind.ORDINARY,
        )
        assertSegment(
            segment = actual.segments[1],
            startRatio = 1f / 3f,
            endRatio = 1f,
            kind = RouteTrafficBarSegmentKind.HIGHWAY,
        )
    }

    @Test
    fun calculateRouteTrafficBarLayout_clipsTrafficSegmentsToCurrentPosition() {
        val geometry = equatorGeometry(pointCount = 5)
        val currentMeters = equatorStepMeters(stepCount = 2)

        val actual = calculateRouteTrafficBarLayout(
            geometry = geometry,
            currentCumulativeMeters = currentMeters,
            roadClassSegments = persistentListOf(),
            congestionSegments = persistentListOf(
                congestionSegment(
                    severity = CongestionSeverity.SLOW,
                    startStep = 1,
                    endStep = 3,
                ),
                congestionSegment(
                    severity = CongestionSeverity.TRAFFIC_JAM,
                    startStep = 3,
                    endStep = 4,
                ),
                congestionSegment(
                    severity = CongestionSeverity.NORMAL,
                    startStep = 2,
                    endStep = 4,
                ),
            ),
        )

        assertEquals(
            listOf(
                RouteTrafficBarSegmentKind.PASSED,
                RouteTrafficBarSegmentKind.ORDINARY,
                RouteTrafficBarSegmentKind.SLOW,
                RouteTrafficBarSegmentKind.TRAFFIC_JAM,
            ),
            actual.segments.map { segment -> segment.kind },
        )
        assertSegment(
            segment = actual.segments[2],
            startRatio = 0.5f,
            endRatio = 0.75f,
            kind = RouteTrafficBarSegmentKind.SLOW,
        )
        assertSegment(
            segment = actual.segments[3],
            startRatio = 0.75f,
            endRatio = 1f,
            kind = RouteTrafficBarSegmentKind.TRAFFIC_JAM,
        )
    }

    @Test
    fun calculateRouteTrafficBarLayout_handlesInvalidGeometrySafely() {
        val actual = calculateRouteTrafficBarLayout(
            geometry = persistentListOf(RoutePoint(latitude = 0.0, longitude = 0.0)),
            currentCumulativeMeters = Double.NaN,
            roadClassSegments = persistentListOf(),
            congestionSegments = persistentListOf(),
        )

        assertEquals(0f, actual.markerRatio)
        assertTrue(actual.segments.isEmpty())
    }

    @Test
    fun calculateRouteTrafficBarLayout_fillsRoadClassGapsAsOrdinary() {
        val geometry = equatorGeometry(pointCount = 4)

        val actual = calculateRouteTrafficBarLayout(
            geometry = geometry,
            currentCumulativeMeters = 0.0,
            roadClassSegments = persistentListOf(
                RoadClassSegment(
                    startPointIndex = 1,
                    endPointIndex = 2,
                    roadClass = RoadClass.HIGHWAY,
                ),
            ),
            congestionSegments = persistentListOf(),
        )

        assertEquals(
            listOf(
                RouteTrafficBarSegmentKind.ORDINARY,
                RouteTrafficBarSegmentKind.HIGHWAY,
                RouteTrafficBarSegmentKind.ORDINARY,
            ),
            actual.segments.map { segment -> segment.kind },
        )
        assertSegment(
            segment = actual.segments[0],
            startRatio = 0f,
            endRatio = 1f / 3f,
            kind = RouteTrafficBarSegmentKind.ORDINARY,
        )
        assertSegment(
            segment = actual.segments[1],
            startRatio = 1f / 3f,
            endRatio = 2f / 3f,
            kind = RouteTrafficBarSegmentKind.HIGHWAY,
        )
        assertSegment(
            segment = actual.segments[2],
            startRatio = 2f / 3f,
            endRatio = 1f,
            kind = RouteTrafficBarSegmentKind.ORDINARY,
        )
    }

    private fun congestionSegment(
        severity: CongestionSeverity,
        startStep: Int,
        endStep: Int,
    ): CongestionSegment = CongestionSegment(
        startPolylinePointIndex = startStep,
        endPolylinePointIndex = endStep,
        severity = severity,
        startDistanceMeters = equatorStepMeters(stepCount = startStep),
        endDistanceMeters = equatorStepMeters(stepCount = endStep),
        congestionDistanceMeters = equatorStepMeters(stepCount = endStep - startStep),
    )

    private fun equatorGeometry(pointCount: Int) = persistentListOf(
        *List(pointCount) { index ->
            RoutePoint(
                latitude = 0.0,
                longitude = index * EquatorStepLongitudeDegrees,
            )
        }.toTypedArray(),
    )

    private fun equatorStepMeters(stepCount: Int): Double {
        val stepRadians = Math.toRadians(EquatorStepLongitudeDegrees)
        return EarthRadiusMeters * stepRadians * stepCount
    }

    private fun assertSegment(
        segment: RouteTrafficBarSegment,
        startRatio: Float,
        endRatio: Float,
        kind: RouteTrafficBarSegmentKind,
    ) {
        assertApproximatelyEquals(startRatio, segment.startRatio)
        assertApproximatelyEquals(endRatio, segment.endRatio)
        assertEquals(kind, segment.kind)
    }

    private fun assertApproximatelyEquals(
        expected: Float,
        actual: Float,
    ) {
        assertTrue(
            abs(expected - actual) < RatioTolerance,
            "Expected $expected but was $actual",
        )
    }
}

/** テスト用 route point の経度差。 */
private const val EquatorStepLongitudeDegrees = 0.001

/** テスト用 haversine 距離計算で使う地球半径メートル。 */
private const val EarthRadiusMeters = 6_371_000.0

/** 比率比較の許容誤差。 */
private const val RatioTolerance = 0.0001f
