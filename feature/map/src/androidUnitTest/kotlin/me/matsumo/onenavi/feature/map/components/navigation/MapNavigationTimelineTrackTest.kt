package me.matsumo.onenavi.feature.map.components.navigation

import kotlinx.collections.immutable.persistentListOf
import me.matsumo.onenavi.core.model.CongestionSegment
import me.matsumo.onenavi.core.model.CongestionSeverity
import me.matsumo.onenavi.feature.map.components.routeCongestionBodyColorOf
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [MapNavigationTimelineTrack] に渡す渋滞帯の区間変換テスト。
 */
class MapNavigationTimelineTrackTest {

    @Test
    fun 渋滞区間は行内fractionへ変換される() {
        val bands = buildTimelineCongestionBands(
            congestionSegments = persistentListOf(
                congestionSegment(
                    startDistanceMeters = 125.0,
                    endDistanceMeters = 175.0,
                    severity = CongestionSeverity.SLOW,
                ),
            ),
            rowStartMeters = 100.0,
            rowEndMeters = 300.0,
        )

        assertEquals(1, bands.size)
        assertFractionEquals(0.125f, bands.single().startFraction)
        assertFractionEquals(0.375f, bands.single().endFraction)
        assertEquals(routeCongestionBodyColorOf(CongestionSeverity.SLOW), bands.single().color)
    }

    @Test
    fun 逆方向の行でも行開始側を上端としてfractionへ変換される() {
        val bands = buildTimelineCongestionBands(
            congestionSegments = persistentListOf(
                congestionSegment(
                    startDistanceMeters = 250.0,
                    endDistanceMeters = 275.0,
                    severity = CongestionSeverity.TRAFFIC_JAM,
                ),
            ),
            rowStartMeters = 300.0,
            rowEndMeters = 100.0,
        )

        assertEquals(1, bands.size)
        assertFractionEquals(0.125f, bands.single().startFraction)
        assertFractionEquals(0.25f, bands.single().endFraction)
        assertEquals(routeCongestionBodyColorOf(CongestionSeverity.TRAFFIC_JAM), bands.single().color)
    }

    @Test
    fun 行と交差する区間だけが切り出される() {
        val bands = buildTimelineCongestionBands(
            congestionSegments = persistentListOf(
                congestionSegment(
                    startDistanceMeters = 50.0,
                    endDistanceMeters = 150.0,
                    severity = CongestionSeverity.SLOW,
                ),
                congestionSegment(
                    startDistanceMeters = 250.0,
                    endDistanceMeters = 350.0,
                    severity = CongestionSeverity.TRAFFIC_JAM,
                ),
            ),
            rowStartMeters = 100.0,
            rowEndMeters = 300.0,
        )

        assertEquals(2, bands.size)
        assertFractionEquals(0f, bands[0].startFraction)
        assertFractionEquals(0.25f, bands[0].endFraction)
        assertFractionEquals(0.75f, bands[1].startFraction)
        assertFractionEquals(1f, bands[1].endFraction)
    }

    @Test
    fun 通常と不明の渋滞レベルは色帯にしない() {
        val bands = buildTimelineCongestionBands(
            congestionSegments = persistentListOf(
                congestionSegment(
                    startDistanceMeters = 120.0,
                    endDistanceMeters = 140.0,
                    severity = CongestionSeverity.NORMAL,
                ),
                congestionSegment(
                    startDistanceMeters = 160.0,
                    endDistanceMeters = 180.0,
                    severity = CongestionSeverity.UNKNOWN,
                ),
            ),
            rowStartMeters = 100.0,
            rowEndMeters = 300.0,
        )

        assertTrue(bands.isEmpty())
    }

    @Test
    fun 点状の渋滞区間も最小高さ描画用に同一fraction帯として残す() {
        val bands = buildTimelineCongestionBands(
            congestionSegments = persistentListOf(
                congestionSegment(
                    startDistanceMeters = 150.0,
                    endDistanceMeters = 150.0,
                    severity = CongestionSeverity.SLOW,
                ),
            ),
            rowStartMeters = 100.0,
            rowEndMeters = 300.0,
        )

        assertEquals(1, bands.size)
        assertFractionEquals(0.25f, bands.single().startFraction)
        assertFractionEquals(0.25f, bands.single().endFraction)
    }

    private fun congestionSegment(
        startDistanceMeters: Double,
        endDistanceMeters: Double,
        severity: CongestionSeverity,
    ): CongestionSegment {
        return CongestionSegment(
            startPolylinePointIndex = 0,
            endPolylinePointIndex = 0,
            severity = severity,
            startDistanceMeters = startDistanceMeters,
            endDistanceMeters = endDistanceMeters,
            congestionDistanceMeters = endDistanceMeters - startDistanceMeters,
        )
    }

    private fun assertFractionEquals(expected: Float, actual: Float) {
        assertTrue(abs(expected - actual) <= FRACTION_TOLERANCE)
    }

    private companion object {
        /** fraction 比較に使う許容誤差。 */
        private const val FRACTION_TOLERANCE: Float = 0.0001f
    }
}
