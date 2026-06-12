package me.matsumo.onenavi.core.navigation.tts

import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [PcmAudioGainProcessor] の PCM16 スケーリングとリミッタを検証する。
 */
class PcmAudioGainProcessorTest {

    @Test
    fun `0dB gain leaves PCM unchanged`() {
        val pcm = pcmOf(-12_000, 0, 12_000)

        val actual = PcmAudioGainProcessor.applyClientGainDb(pcm, clientGainDb = 0.0)

        assertContentEquals(pcm, actual)
    }

    @Test
    fun `positive gain scales middle sample`() {
        val pcm = pcmOf(10_000)

        val actual = PcmAudioGainProcessor.applyClientGainDb(pcm, clientGainDb = 6.0)

        assertEquals(expectedPositiveSample(10_000, gainDb = 6.0), sampleAt(actual, sampleIndex = 0))
    }

    @Test
    fun `soft limiter avoids positive hard clipping`() {
        val pcm = pcmOf(30_000)

        val actual = PcmAudioGainProcessor.applyClientGainDb(pcm, clientGainDb = 12.0)
        val actualSample = sampleAt(actual, sampleIndex = 0)

        assertTrue(actualSample > 30_000)
        assertTrue(actualSample < Short.MAX_VALUE.toInt())
    }

    @Test
    fun `soft limiter avoids negative hard clipping`() {
        val pcm = pcmOf(-30_000)

        val actual = PcmAudioGainProcessor.applyClientGainDb(pcm, clientGainDb = 12.0)
        val actualSample = sampleAt(actual, sampleIndex = 0)

        assertTrue(actualSample < -30_000)
        assertTrue(actualSample > Short.MIN_VALUE.toInt())
    }

    private fun pcmOf(vararg samples: Int): ByteArray {
        val pcm = ByteArray(samples.size * BYTES_PER_SAMPLE)

        samples.forEachIndexed { sampleIndex, sample ->
            val offset = sampleIndex * BYTES_PER_SAMPLE
            pcm[offset] = (sample and BYTE_MASK).toByte()
            pcm[offset + 1] = (sample shr BYTE_BITS).toByte()
        }

        return pcm
    }

    private fun sampleAt(pcm: ByteArray, sampleIndex: Int): Int {
        val offset = sampleIndex * BYTES_PER_SAMPLE
        val lowByte = pcm[offset].toInt() and BYTE_MASK
        val highByte = pcm[offset + 1].toInt()

        return (highByte shl BYTE_BITS) or lowByte
    }

    private fun expectedPositiveSample(sample: Int, gainDb: Double): Int {
        val linearGain = DB_TO_LINEAR_BASE.pow(gainDb / DECIBEL_POWER_RATIO)
        val normalizedSample = sample.toDouble() / PCM_NEGATIVE_PEAK

        return (normalizedSample * linearGain * PCM_POSITIVE_PEAK).roundToInt()
    }

    /** テストで使う PCM16 定数群。 */
    private companion object {

        /** 1 サンプルあたりのバイト数。 */
        const val BYTES_PER_SAMPLE = 2

        /** 1 バイトのビット数。 */
        const val BYTE_BITS = 8

        /** 符号なしバイト化のマスク。 */
        const val BYTE_MASK = 0xFF

        /** dB から線形倍率へ変換するときの底。 */
        const val DB_TO_LINEAR_BASE = 10.0

        /** 音圧 dB を線形倍率へ変換するときの比率。 */
        const val DECIBEL_POWER_RATIO = 20.0

        /** PCM16 正側の最大振幅。 */
        const val PCM_POSITIVE_PEAK = 32_767.0

        /** PCM16 負側の最大振幅。 */
        const val PCM_NEGATIVE_PEAK = 32_768.0
    }
}
