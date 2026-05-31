package me.matsumo.onenavi.core.navigation.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CompletableDeferred

/**
 * LINEAR16 PCM を [AudioTrack] で再生するプレイヤー。
 *
 * 発話ごとに `MODE_STATIC` の [AudioTrack] を新規生成し、再生完了マーカーで完了を検知する。
 * coroutine が cancel された場合 (barge-in) も `finally` で確実に release する。
 */
internal class PcmAudioPlayer {

    /**
     * WAV (LINEAR16) バイト列を再生し、完了まで suspend する。
     *
     * @param audio 先頭 44byte が WAV ヘッダの PCM バイト列
     */
    suspend fun playAndAwait(audio: ByteArray) {
        if (audio.size <= WAV_HEADER_BYTES) return
        val pcm = audio.copyOfRange(WAV_HEADER_BYTES, audio.size)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE_HERTZ)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(pcm.size)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        try {
            track.write(pcm, 0, pcm.size)

            val totalFrames = pcm.size / BYTES_PER_FRAME
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

    private companion object {

        /** LINEAR16 のレスポンスに付く WAV ヘッダのバイト数。 */
        const val WAV_HEADER_BYTES = 44

        /** 16bit mono の 1 フレームあたりのバイト数。 */
        const val BYTES_PER_FRAME = 2

        /** 再生サンプリングレート ([SynthesizeAudioConfig] と一致させる)。 */
        const val SAMPLE_RATE_HERTZ = 24_000
    }
}
