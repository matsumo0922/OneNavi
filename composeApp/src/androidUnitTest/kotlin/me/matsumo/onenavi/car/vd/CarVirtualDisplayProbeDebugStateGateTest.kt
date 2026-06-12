package me.matsumo.onenavi.car.vd

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [CarVirtualDisplayProbeDebugStateGate] の入力 state publish 判定を検証する。 */
class CarVirtualDisplayProbeDebugStateGateTest {

    @Test
    fun `debug overlay enables input state publishing`() {
        val actual = CarVirtualDisplayProbeDebugStateGate.shouldPublishInputState(isDebugOverlayEnabled = true)

        assertTrue(actual)
    }

    @Test
    fun `disabled debug overlay skips input state publishing`() {
        val actual = CarVirtualDisplayProbeDebugStateGate.shouldPublishInputState(isDebugOverlayEnabled = false)

        assertFalse(actual)
    }
}
