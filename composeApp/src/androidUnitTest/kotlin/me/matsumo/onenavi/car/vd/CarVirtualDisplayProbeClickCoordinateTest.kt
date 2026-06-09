package me.matsumo.onenavi.car.vd

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** VD click 座標変換の回帰テスト。 */
class CarVirtualDisplayProbeClickCoordinateTest {

    @Test
    fun resolveClickDispatchCoordinateReturnsSurfaceCoordinateWhenViewportIsFullWidth() {
        val viewport = createTestViewport()
        val inputState = createClickInputState(
            viewport = viewport,
            surfaceX = 510f,
            surfaceY = 41f,
        )

        val coordinate = inputState.resolveCarVirtualDisplayProbeClickDispatchCoordinate(viewport)

        assertEquals(
            expected = CLICK_COORDINATE_SURFACE_LABEL,
            actual = coordinate?.label,
        )
        assertEquals(
            expected = 510f,
            actual = coordinate?.point?.x,
        )
        assertEquals(
            expected = 41f,
            actual = coordinate?.point?.y,
        )
        assertTrue(
            actual = requireNotNull(coordinate).let(viewport::containsClickDispatchCoordinate),
        )
    }

    @Test
    fun resolveClickDispatchCoordinateAddsObservedFrameLeftWhenVisibleAreaIsSplit() {
        val viewport = createTestViewport().withVisibleBounds(
            visibleLeft = 444,
            visibleTop = 88,
            visibleRight = 1166,
            visibleBottom = 688,
        )
        val inputState = createClickInputState(
            viewport = viewport,
            surfaceX = 510f,
            surfaceY = 41f,
        )

        val coordinate = inputState.resolveCarVirtualDisplayProbeClickDispatchCoordinate(viewport)

        assertEquals(
            expected = CLICK_COORDINATE_OBSERVED_OFFSET_LABEL,
            actual = coordinate?.label,
        )
        assertEquals(
            expected = viewport.observedFrame.left + 510f,
            actual = coordinate?.point?.x,
        )
        assertEquals(
            expected = 41f,
            actual = coordinate?.point?.y,
        )
        assertTrue(
            actual = requireNotNull(coordinate).let(viewport::containsClickDispatchCoordinate),
        )
    }

    @Test
    fun resolveClickDispatchCoordinateKeepsVerticalCoordinateIndependentFromVisibleTop() {
        val lowTopViewport = createTestViewport().withVisibleBounds(
            visibleLeft = 444,
            visibleTop = 24,
            visibleRight = 1166,
            visibleBottom = 688,
        )
        val highTopViewport = createTestViewport().withVisibleBounds(
            visibleLeft = 444,
            visibleTop = 88,
            visibleRight = 1166,
            visibleBottom = 688,
        )

        val lowTopCoordinate = createClickInputState(
            viewport = lowTopViewport,
            surfaceX = 510f,
            surfaceY = 41f,
        ).resolveCarVirtualDisplayProbeClickDispatchCoordinate(lowTopViewport)
        val highTopCoordinate = createClickInputState(
            viewport = highTopViewport,
            surfaceX = 510f,
            surfaceY = 41f,
        ).resolveCarVirtualDisplayProbeClickDispatchCoordinate(highTopViewport)

        assertEquals(
            expected = CLICK_COORDINATE_OBSERVED_OFFSET_LABEL,
            actual = lowTopCoordinate?.label,
        )
        assertEquals(
            expected = CLICK_COORDINATE_OBSERVED_OFFSET_LABEL,
            actual = highTopCoordinate?.label,
        )
        assertEquals(
            expected = 41f,
            actual = lowTopCoordinate?.point?.y,
        )
        assertEquals(
            expected = 41f,
            actual = highTopCoordinate?.point?.y,
        )
    }

    private fun createTestViewport(): CarVirtualDisplayProbeViewport {
        return createCarVirtualDisplayProbeViewport(
            surfaceWidth = 1280,
            surfaceHeight = 720,
            densityDpi = 240,
        )
    }

    private fun CarVirtualDisplayProbeViewport.withVisibleBounds(
        visibleLeft: Int,
        visibleTop: Int,
        visibleRight: Int,
        visibleBottom: Int,
    ): CarVirtualDisplayProbeViewport {
        return copy(
            visibleLeft = visibleLeft,
            visibleTop = visibleTop,
            visibleRight = visibleRight,
            visibleBottom = visibleBottom,
        )
    }

    private fun createClickInputState(
        viewport: CarVirtualDisplayProbeViewport,
        surfaceX: Float,
        surfaceY: Float,
    ): CarVirtualDisplayProbeInputState {
        return createCarVirtualDisplayProbeClickInputState(
            sequence = 1L,
            viewport = viewport,
            hostInputX = surfaceX,
            hostInputY = surfaceY,
            isInPanMode = false,
        )
    }
}
