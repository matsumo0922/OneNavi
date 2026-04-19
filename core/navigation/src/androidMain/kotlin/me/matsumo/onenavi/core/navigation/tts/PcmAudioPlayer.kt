package me.matsumo.onenavi.core.navigation.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred

/**
 * LINEAR16 PCM の生データを AudioTrack で再生するラッパー。
 *
 * 発話ごとに新しい AudioTrack を生成し、write 完了後 MODE_STATIC で一括再生する。
 * marker で完了を検知し、呼び出し側のコルーチンがキャンセルされた場合は必ず release する。
 */
class PcmAudioPlayer {

    /**
     * LINEAR16 PCM (+44 byte WAV ヘッダ) を再生して、完了まで suspend する。
     *
     * @param audio Google Cloud TTS から返却されたバイト列 (WAV ヘッダ + PCM)
     */
    suspend fun playAndAwait(audio: ByteArray) {
        if (audio.size <= WAV_HEADER_BYTES) {
            Napier.w(tag = TAG) { "Audio too small to contain PCM payload: size=${audio.size}" }
            return
        }
        val pcm = audio.copyOfRange(WAV_HEADER_BYTES, audio.size)
        val track = buildAudioTrack(bufferSize = pcm.size)
        try {
            val written = track.write(pcm, 0, pcm.size)
            if (written <= 0) {
                Napier.w(tag = TAG) { "AudioTrack.write returned $written" }
                return
            }
            val totalFrames = written / BYTES_PER_FRAME
            val completion = CompletableDeferred<Unit>()
            track.setPlaybackPositionUpdateListener(
                object : AudioTrack.OnPlaybackPositionUpdateListener {
                    override fun onMarkerReached(target: AudioTrack?) {
                        completion.complete(Unit)
                    }

                    override fun onPeriodicNotification(target: AudioTrack?) = Unit
                },
            )
            track.notificationMarkerPosition = totalFrames
            track.play()
            try {
                completion.await()
            } catch (cancel: CancellationException) {
                runCatching { track.pause() }
                runCatching { track.flush() }
                throw cancel
            }
        } finally {
            runCatching { track.setPlaybackPositionUpdateListener(null) }
            runCatching { track.release() }
        }
    }

    private fun buildAudioTrack(bufferSize: Int): AudioTrack {
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE_HZ)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
    }

    private companion object {
        private const val TAG = "PcmAudioPlayer"
        private const val WAV_HEADER_BYTES = 44
        private const val SAMPLE_RATE_HZ = 24_000
        private const val BYTES_PER_FRAME = 2
    }
}
