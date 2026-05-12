package me.matsumo.onenavi.feature.map.camera

import me.matsumo.onenavi.feature.map.camera.VanWijkZoomPath.Viewport
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VanWijkZoomPathTest {

    private val tolerance = 1e-6

    @Test
    fun `始点と終点では渡したビューポートに一致する`() {
        val start = Viewport(worldX = 10.0, worldY = 20.0, viewportWidthWorldPx = 4.0)
        val end = Viewport(worldX = 60.0, worldY = 20.0, viewportWidthWorldPx = 8.0)
        val path = VanWijkZoomPath.of(start, end)

        assertViewportEquals(start, path.at(0.0))
        assertViewportEquals(end, path.at(1.0))
    }

    @Test
    fun `退化ケースはパンなしの幾何平均ズームになる`() {
        val start = Viewport(worldX = 100.0, worldY = 100.0, viewportWidthWorldPx = 4.0)
        val end = Viewport(worldX = 100.0, worldY = 100.0, viewportWidthWorldPx = 16.0)
        val path = VanWijkZoomPath.of(start, end)

        val mid = path.at(0.5)
        assertEquals(100.0, mid.worldX, tolerance)
        assertEquals(100.0, mid.worldY, tolerance)
        // 4 と 16 の幾何平均 = sqrt(64) = 8。ρ には依存しない。
        assertEquals(8.0, mid.viewportWidthWorldPx, tolerance)
    }

    @Test
    fun `退化ケースの弧長は log(w1 div w0) div rho`() {
        val path = VanWijkZoomPath.of(
            start = Viewport(worldX = 5.0, worldY = 5.0, viewportWidthWorldPx = 4.0),
            end = Viewport(worldX = 5.0, worldY = 5.0, viewportWidthWorldPx = 16.0),
            rho = 2.0,
        )
        assertEquals(ln(16.0 / 4.0) / 2.0, path.arcLength, tolerance)
    }

    @Test
    fun `遠距離移動では途中でズームアウトの弧を描く`() {
        val start = Viewport(worldX = 0.0, worldY = 0.0, viewportWidthWorldPx = 1.0)
        val end = Viewport(worldX = 1000.0, worldY = 0.0, viewportWidthWorldPx = 1.0)
        val path = VanWijkZoomPath.of(start, end)

        val mid = path.at(0.5)
        assertTrue(
            mid.viewportWidthWorldPx > start.viewportWidthWorldPx,
            "中間点のビューポート幅 ${mid.viewportWidthWorldPx} は始点より大きいはず",
        )
        assertTrue(mid.worldX in 0.0..1000.0)
        assertEquals(0.0, mid.worldY, tolerance)
    }

    @Test
    fun `ビューポートに対して近い移動では弧がほぼ消える`() {
        val start = Viewport(worldX = 0.0, worldY = 0.0, viewportWidthWorldPx = 100.0)
        val end = Viewport(worldX = 1.0, worldY = 0.0, viewportWidthWorldPx = 100.0)
        val path = VanWijkZoomPath.of(start, end)

        val mid = path.at(0.5)
        assertTrue(
            abs(mid.viewportWidthWorldPx - 100.0) < 1.0,
            "中間点のビューポート幅 ${mid.viewportWidthWorldPx} はほぼ 100 のはず",
        )
    }

    @Test
    fun `at は範囲外の t を 0 から 1 にクランプする`() {
        val path = VanWijkZoomPath.of(
            start = Viewport(worldX = 0.0, worldY = 0.0, viewportWidthWorldPx = 1.0),
            end = Viewport(worldX = 10.0, worldY = 0.0, viewportWidthWorldPx = 2.0),
        )
        assertEquals(path.at(0.0), path.at(-1.0))
        assertEquals(path.at(1.0), path.at(5.0))
    }

    @Test
    fun `所要時間は正で 遠いほど長くなる`() {
        val origin = Viewport(worldX = 0.0, worldY = 0.0, viewportWidthWorldPx = 100.0)
        val nearPath = VanWijkZoomPath.of(origin, origin.copy(worldX = 1.0))
        val farPath = VanWijkZoomPath.of(origin, origin.copy(worldX = 5000.0))

        assertTrue(nearPath.naturalDurationMs() > 0.0)
        assertTrue(farPath.naturalDurationMs() > nearPath.naturalDurationMs())
        assertEquals(
            abs(farPath.arcLength) * 1000.0 * farPath.rho / sqrt(2.0),
            farPath.naturalDurationMs(),
            tolerance,
        )
    }

    @Test
    fun `rho に 0 以下を渡すと既定値にフォールバックする`() {
        val start = Viewport(worldX = 0.0, worldY = 0.0, viewportWidthWorldPx = 1.0)
        val end = Viewport(worldX = 10.0, worldY = 0.0, viewportWidthWorldPx = 1.0)

        assertEquals(VanWijkZoomPath.DEFAULT_RHO, VanWijkZoomPath.of(start, end, rho = 0.0).rho)
        assertEquals(VanWijkZoomPath.DEFAULT_RHO, VanWijkZoomPath.of(start, end, rho = -1.0).rho)
        assertEquals(2.0, VanWijkZoomPath.of(start, end, rho = 2.0).rho)
    }

    private fun assertViewportEquals(expected: Viewport, actual: Viewport) {
        assertEquals(expected.worldX, actual.worldX, tolerance)
        assertEquals(expected.worldY, actual.worldY, tolerance)
        assertEquals(expected.viewportWidthWorldPx, actual.viewportWidthWorldPx, tolerance)
    }
}
