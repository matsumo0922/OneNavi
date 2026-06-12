package me.matsumo.onenavi.core.navigation.tts

import kotlin.math.absoluteValue
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * PCM16 音声へクライアント側の追加ゲインを掛ける processor。
 */
internal object PcmAudioGainProcessor {

    /** 1 サンプルあたりのバイト数。 */
    private const val BYTES_PER_SAMPLE = 2

    /** 1 バイトのビット数。 */
    private const val BYTE_BITS = 8

    /** 符号なしバイト化のマスク。 */
    private const val BYTE_MASK = 0xFF

    /** dB から線形倍率へ変換するときの底。 */
    private const val DB_TO_LINEAR_BASE = 10.0

    /** 音圧 dB を線形倍率へ変換するときの比率。 */
    private const val DECIBEL_POWER_RATIO = 20.0

    /** PCM16 正側の最大振幅。 */
    private const val PCM_POSITIVE_PEAK = 32_767.0

    /** PCM16 負側の最大振幅。 */
    private const val PCM_NEGATIVE_PEAK = 32_768.0

    /** 正規化 PCM の最大振幅。 */
    private const val PCM_NORMALIZED_PEAK = 1.0

    /** ソフトリミッタを開始する正規化振幅。 */
    private const val SOFT_LIMIT_START = 0.92

    /** ソフトリミッタで圧縮する残りヘッドルーム。 */
    private const val SOFT_LIMIT_HEADROOM = PCM_NORMALIZED_PEAK - SOFT_LIMIT_START

    /**
     * PCM16 little-endian のバイト列へ dB 指定の追加ゲインを適用する。
     *
     * @param pcm WAV data チャンクから取り出した PCM16 little-endian バイト列
     * @param clientGainDb 再生直前に追加するゲイン
     * @return 追加ゲインを適用した PCM16 little-endian バイト列
     */
    fun applyClientGainDb(pcm: ByteArray, clientGainDb: Double): ByteArray {
        val shouldBypass = clientGainDb == 0.0 || pcm.size < BYTES_PER_SAMPLE || !clientGainDb.isFinite()
        if (shouldBypass) return pcm

        val linearGain = DB_TO_LINEAR_BASE.pow(clientGainDb / DECIBEL_POWER_RATIO)
        val amplifiedPcm = pcm.copyOf()
        var pcmIndex = 0

        while (pcmIndex + 1 < amplifiedPcm.size) {
            val sample = littleEndianPcm16Sample(pcm, pcmIndex)
            val scaledSample = scaleAndLimitSample(sample, linearGain)

            writeLittleEndianPcm16Sample(
                pcm = amplifiedPcm,
                offset = pcmIndex,
                sample = scaledSample,
            )

            pcmIndex += BYTES_PER_SAMPLE
        }

        return amplifiedPcm
    }

    private fun scaleAndLimitSample(sample: Int, linearGain: Double): Int {
        val normalizedSample = sample.toDouble() / PCM_NEGATIVE_PEAK
        val gainedSample = normalizedSample * linearGain
        val limitedSample = softLimit(gainedSample)

        return pcm16Sample(limitedSample)
    }

    private fun softLimit(sample: Double): Double {
        val absoluteSample = sample.absoluteValue
        if (absoluteSample <= SOFT_LIMIT_START) return sample

        val normalizedExcess = (absoluteSample - SOFT_LIMIT_START) / SOFT_LIMIT_HEADROOM
        val compressedExcess = normalizedExcess / (1.0 + normalizedExcess)
        val limitedAbsoluteSample = SOFT_LIMIT_START + SOFT_LIMIT_HEADROOM * compressedExcess

        return if (sample < 0.0) {
            -limitedAbsoluteSample.coerceAtMost(PCM_NORMALIZED_PEAK)
        } else {
            limitedAbsoluteSample.coerceAtMost(PCM_NORMALIZED_PEAK)
        }
    }

    private fun pcm16Sample(normalizedSample: Double): Int {
        val pcmPeak = if (normalizedSample < 0.0) PCM_NEGATIVE_PEAK else PCM_POSITIVE_PEAK

        return (normalizedSample * pcmPeak)
            .roundToInt()
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
    }

    private fun littleEndianPcm16Sample(pcm: ByteArray, offset: Int): Int {
        val lowByte = pcm[offset].toInt() and BYTE_MASK
        val highByte = pcm[offset + 1].toInt()

        return (highByte shl BYTE_BITS) or lowByte
    }

    private fun writeLittleEndianPcm16Sample(pcm: ByteArray, offset: Int, sample: Int) {
        pcm[offset] = (sample and BYTE_MASK).toByte()
        pcm[offset + 1] = (sample shr BYTE_BITS).toByte()
    }
}
