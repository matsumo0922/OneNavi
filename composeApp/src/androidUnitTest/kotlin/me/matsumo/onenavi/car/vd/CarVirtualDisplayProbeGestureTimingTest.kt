package me.matsumo.onenavi.car.vd

import kotlin.test.Test
import kotlin.test.assertEquals

/** VD click gesture の synthetic event timing を検証する。 */
class CarVirtualDisplayProbeGestureTimingTest {

    @Test
    fun `click up delay is shortened to one frame`() {
        assertEquals(
            expected = 16L,
            actual = CLICK_UP_DELAY_MS,
        )
    }
}
