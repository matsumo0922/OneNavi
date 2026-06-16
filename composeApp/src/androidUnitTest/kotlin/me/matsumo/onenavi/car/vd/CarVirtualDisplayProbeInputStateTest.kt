package me.matsumo.onenavi.car.vd

import me.matsumo.onenavi.core.common.car.CarDisplayInputTargetRect
import kotlin.test.Test
import kotlin.test.assertEquals

/** VD 入力 anchor の回帰テスト。 */
class CarVirtualDisplayProbeInputStateTest {

    @Test
    fun scrollUsesTargetRectCenterWhenPanModeIsOff() {
        val viewport = createWideViewport()
        val targetRect = createPanelTargetRect()

        val inputState = createCarVirtualDisplayProbeScrollInputState(
            sequence = 1L,
            viewport = viewport,
            distanceX = 0f,
            distanceY = 120f,
            isInPanMode = false,
            scrollTargetRect = targetRect,
        )

        assertEquals(
            expected = targetRect.centerX,
            actual = inputState.surfaceX,
        )
        assertEquals(
            expected = targetRect.centerY,
            actual = inputState.surfaceY,
        )
    }

    @Test
    fun scrollUsesFrameCenterWhenPanModeIsOn() {
        val viewport = createWideViewport()

        val inputState = createCarVirtualDisplayProbeScrollInputState(
            sequence = 1L,
            viewport = viewport,
            distanceX = 0f,
            distanceY = 120f,
            isInPanMode = true,
            scrollTargetRect = createPanelTargetRect(),
        )

        assertEquals(
            expected = viewport.surfaceWidth / 2f,
            actual = inputState.surfaceX,
        )
        assertEquals(
            expected = viewport.surfaceHeight / 2f,
            actual = inputState.surfaceY,
        )
    }

    @Test
    fun flingUsesTargetRectCenterWhenPanModeIsOff() {
        val viewport = createWideViewport()
        val targetRect = createPanelTargetRect()

        val inputState = createCarVirtualDisplayProbeFlingInputState(
            sequence = 1L,
            viewport = viewport,
            velocityX = 0f,
            velocityY = 2400f,
            isInPanMode = false,
            scrollTargetRect = targetRect,
        )

        assertEquals(
            expected = targetRect.centerX,
            actual = inputState.surfaceX,
        )
        assertEquals(
            expected = targetRect.centerY,
            actual = inputState.surfaceY,
        )
    }

    @Test
    fun scrollTargetRectCenterIsCoercedIntoObservedFrame() {
        val viewport = createWideViewport().copy(
            visibleLeft = 120,
            visibleRight = 1800,
        )

        val inputState = createCarVirtualDisplayProbeScrollInputState(
            sequence = 1L,
            viewport = viewport,
            distanceX = 0f,
            distanceY = 120f,
            isInPanMode = false,
            scrollTargetRect = CarDisplayInputTargetRect(
                left = 1900f,
                top = 40f,
                right = 2100f,
                bottom = 200f,
            ),
        )

        assertEquals(
            expected = 1918f,
            actual = inputState.surfaceX,
        )
        assertEquals(
            expected = 120f,
            actual = inputState.surfaceY,
        )
    }

    private fun createWideViewport(): CarVirtualDisplayProbeViewport {
        return createCarVirtualDisplayProbeViewport(
            surfaceWidth = 1920,
            surfaceHeight = 720,
            densityDpi = 240,
        )
    }

    private fun createPanelTargetRect(): CarDisplayInputTargetRect {
        return CarDisplayInputTargetRect(
            left = 1224f,
            top = 96f,
            right = 1824f,
            bottom = 384f,
        )
    }
}
