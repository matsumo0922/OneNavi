package me.matsumo.onenavi.core.navigation.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

/**
 * ナビゲーション音声案内用の AudioFocus を取得、解放するクラス。
 */
class AudioFocusManager(
    context: Context,
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    fun request() {
        val manager = audioManager ?: return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .build()
        audioFocusRequest = request
        manager.requestAudioFocus(request)
    }

    fun abandon() {
        val manager = audioManager ?: return
        audioFocusRequest?.let(manager::abandonAudioFocusRequest)
        audioFocusRequest = null
    }
}
