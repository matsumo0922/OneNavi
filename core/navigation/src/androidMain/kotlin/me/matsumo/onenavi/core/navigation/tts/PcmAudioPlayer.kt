package me.matsumo.onenavi.core.navigation.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CompletableDeferred

/**
 * WAV コンテナの LINEAR16 PCM を [AudioTrack] で再生するプレイヤー。
 *
 * 発話ごとに `MODE_STATIC` の [AudioTrack] を新規生成し、再生完了マーカーで完了を検知する。
 * coroutine が cancel された場合 (barge-in) も `finally` で確実に release する。
 */
internal class PcmAudioPlayer {

    /**
     * WAV (LINEAR16) バイト列を再生し、完了まで suspend する。
     *
     * @param audio WAV ヘッダ付きの PCM バイト列
     * @param contentType 再生する音の種別
     * @param channel 出力する音声チャンネル (usage)
     */
    suspend fun playAndAwait(
        audio: ByteArray,
        contentType: Int = AudioAttributes.CONTENT_TYPE_SPEECH,
        channel: NavigationAudioChannel = NavigationAudioChannel.Guidance,
    ) {
        val wavAudio = WavPcm16Audio.parse(audio).getOrElse { return }

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(channel.usage)
                    .setContentType(contentType)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(wavAudio.sampleRate)
                    .setChannelMask(wavAudio.channelMask)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(wavAudio.pcm.size)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        try {
            track.write(wavAudio.pcm, 0, wavAudio.pcm.size)

            val totalFrames = wavAudio.pcm.size / wavAudio.bytesPerFrame
            val completion = CompletableDeferred<Unit>()

            track.setNotificationMarkerPosition(totalFrames)
            track.setPlaybackPositionUpdateListener(
                object : AudioTrack.OnPlaybackPositionUpdateListener {
                    override fun onMarkerReached(track: AudioTrack?) {
                        completion.complete(Unit)
                    }

                    override fun onPeriodicNotification(track: AudioTrack?) = Unit
                },
            )

            track.play()
            completion.await()
        } finally {
            runCatching { track.pause() }
            runCatching { track.flush() }
            track.release()
        }
    }

    /**
     * WAV から取り出した PCM16 再生情報。
     *
     * @property sampleRate WAV のサンプリングレート
     * @property channelMask [AudioTrack] に渡すチャンネルマスク
     * @property bytesPerFrame 1 フレームあたりのバイト数
     * @property pcm WAV の data チャンクから取り出した PCM バイト列
     */
    private data class WavPcm16Audio(
        val sampleRate: Int,
        val channelMask: Int,
        val bytesPerFrame: Int,
        val pcm: ByteArray,
    ) {

        companion object {

            /** WAV バイト列から PCM16 再生情報を取り出す。 */
            fun parse(audio: ByteArray): Result<WavPcm16Audio> = runCatching {
                require(audio.size >= RIFF_HEADER_BYTES)
                require(chunkId(audio, 0) == RIFF)
                require(chunkId(audio, 8) == WAVE)

                var offset = RIFF_HEADER_BYTES
                var audioFormat: Int? = null
                var channelCount: Int? = null
                var sampleRate: Int? = null
                var bitsPerSample: Int? = null
                var dataOffset: Int? = null
                var dataSize: Int? = null

                while (offset + CHUNK_HEADER_BYTES <= audio.size) {
                    val id = chunkId(audio, offset)
                    val chunkSize = littleEndianInt(audio, offset + CHUNK_SIZE_OFFSET)
                    require(chunkSize >= 0)

                    val payloadOffset = offset + CHUNK_HEADER_BYTES
                    val nextOffset = payloadOffset + chunkSize + chunkSize.mod(CHUNK_PADDING)
                    require(payloadOffset + chunkSize <= audio.size)

                    when (id) {
                        FMT -> {
                            require(chunkSize >= MIN_FMT_CHUNK_BYTES)
                            audioFormat = littleEndianShort(audio, payloadOffset)
                            channelCount = littleEndianShort(audio, payloadOffset + FMT_CHANNEL_COUNT_OFFSET)
                            sampleRate = littleEndianInt(audio, payloadOffset + FMT_SAMPLE_RATE_OFFSET)
                            bitsPerSample = littleEndianShort(audio, payloadOffset + FMT_BITS_PER_SAMPLE_OFFSET)
                        }
                        DATA -> {
                            dataOffset = payloadOffset
                            dataSize = chunkSize
                        }
                    }

                    offset = nextOffset
                }

                val resolvedChannelCount = requireNotNull(channelCount)
                val resolvedSampleRate = requireNotNull(sampleRate)
                val resolvedBitsPerSample = requireNotNull(bitsPerSample)
                val resolvedDataOffset = requireNotNull(dataOffset)
                val resolvedDataSize = requireNotNull(dataSize)

                require(audioFormat == PCM_FORMAT)
                require(resolvedBitsPerSample == PCM_16_BITS)

                val channelMask = when (resolvedChannelCount) {
                    MONO_CHANNEL_COUNT -> AudioFormat.CHANNEL_OUT_MONO
                    STEREO_CHANNEL_COUNT -> AudioFormat.CHANNEL_OUT_STEREO
                    else -> error("Unsupported WAV channel count: $resolvedChannelCount")
                }
                val bytesPerFrame = resolvedChannelCount * BYTES_PER_SAMPLE
                val pcm = audio.copyOfRange(resolvedDataOffset, resolvedDataOffset + resolvedDataSize)

                WavPcm16Audio(
                    sampleRate = resolvedSampleRate,
                    channelMask = channelMask,
                    bytesPerFrame = bytesPerFrame,
                    pcm = pcm,
                )
            }

            private fun chunkId(audio: ByteArray, offset: Int): String =
                String(audio, offset, CHUNK_ID_BYTES, Charsets.US_ASCII)

            private fun littleEndianShort(audio: ByteArray, offset: Int): Int =
                byteAt(audio, offset) or (byteAt(audio, offset + 1) shl BYTE_BITS)

            private fun littleEndianInt(audio: ByteArray, offset: Int): Int =
                byteAt(audio, offset) or
                    (byteAt(audio, offset + 1) shl BYTE_BITS) or
                    (byteAt(audio, offset + 2) shl (BYTE_BITS * 2)) or
                    (byteAt(audio, offset + 3) shl (BYTE_BITS * 3))

            private fun byteAt(audio: ByteArray, offset: Int): Int =
                audio[offset].toInt() and BYTE_MASK
        }
    }

    private companion object {

        /** RIFF ヘッダのバイト数。 */
        const val RIFF_HEADER_BYTES = 12

        /** WAV チャンクヘッダのバイト数。 */
        const val CHUNK_HEADER_BYTES = 8

        /** チャンク ID のバイト数。 */
        const val CHUNK_ID_BYTES = 4

        /** チャンクサイズのチャンク先頭からのオフセット。 */
        const val CHUNK_SIZE_OFFSET = 4

        /** WAV チャンクは偶数バイト境界に詰められる。 */
        const val CHUNK_PADDING = 2

        /** fmt チャンクの最小バイト数。 */
        const val MIN_FMT_CHUNK_BYTES = 16

        /** fmt チャンク内のチャンネル数オフセット。 */
        const val FMT_CHANNEL_COUNT_OFFSET = 2

        /** fmt チャンク内のサンプリングレートオフセット。 */
        const val FMT_SAMPLE_RATE_OFFSET = 4

        /** fmt チャンク内の量子化ビット数オフセット。 */
        const val FMT_BITS_PER_SAMPLE_OFFSET = 14

        /** PCM フォーマット ID。 */
        const val PCM_FORMAT = 1

        /** 16bit PCM の量子化ビット数。 */
        const val PCM_16_BITS = 16

        /** 1 サンプルあたりのバイト数。 */
        const val BYTES_PER_SAMPLE = 2

        /** モノラルのチャンネル数。 */
        const val MONO_CHANNEL_COUNT = 1

        /** ステレオのチャンネル数。 */
        const val STEREO_CHANNEL_COUNT = 2

        /** 1 バイトのビット数。 */
        const val BYTE_BITS = 8

        /** 符号なしバイト化のマスク。 */
        const val BYTE_MASK = 0xFF

        /** RIFF チャンク ID。 */
        const val RIFF = "RIFF"

        /** WAVE フォーマット ID。 */
        const val WAVE = "WAVE"

        /** fmt チャンク ID。 */
        const val FMT = "fmt "

        /** data チャンク ID。 */
        const val DATA = "data"
    }
}
