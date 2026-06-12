package me.matsumo.onenavi.core.navigation.tts

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [SpeedAdaptiveGainCalculator] の折れ線マッピングを検証する。
 */
class SpeedAdaptiveGainCalculatorTest {

    @Test
    fun `disabled setting returns zero gain`() {
        val actual = SpeedAdaptiveGainCalculator.gainDbFor(
            speedMps = kmhToMps(100.0),
            isEnabled = false,
            maxGainDb = 6.0,
        )

        assertEquals(0.0, actual)
    }

    @Test
    fun `missing speed returns zero gain`() {
        val actual = SpeedAdaptiveGainCalculator.gainDbFor(
            speedMps = null,
            isEnabled = true,
            maxGainDb = 6.0,
        )

        assertEquals(0.0, actual)
    }

    @Test
    fun `start speed returns zero gain`() {
        val actual = SpeedAdaptiveGainCalculator.gainDbFor(
            speedMps = kmhToMps(60.0),
            isEnabled = true,
            maxGainDb = 6.0,
        )

        assertEquals(0.0, actual)
    }

    @Test
    fun `middle speed linearly interpolates gain`() {
        val actual = SpeedAdaptiveGainCalculator.gainDbFor(
            speedMps = kmhToMps(80.0),
            isEnabled = true,
            maxGainDb = 6.0,
        )

        assertEquals(
            expected = 3.0,
            actual = actual,
            absoluteTolerance = 0.001,
        )
    }

    @Test
    fun `max speed returns configured gain`() {
        val actual = SpeedAdaptiveGainCalculator.gainDbFor(
            speedMps = kmhToMps(100.0),
            isEnabled = true,
            maxGainDb = 6.0,
        )

        assertEquals(6.0, actual)
    }

    private fun kmhToMps(speedKmh: Double): Float {
        return (speedKmh / KMH_PER_MPS).toFloat()
    }

    /** テストで使う変換係数。 */
    private companion object {

        /** m/s から km/h への変換係数。 */
        const val KMH_PER_MPS = 3.6
    }
}
