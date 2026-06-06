package me.matsumo.onenavi.core.navigation.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

/**
 * 発話中だけ他アプリの音声を一時的に下げる (duck) ための AudioFocus ラッパー。
 *
 * [request] と [abandon] は 1:1 で対になるよう呼び出し側 (dispatcher の try/finally) が保証する。
 * scheduler 側がキューを持つため本実装は発話ごとに request/abandon する。連続発話で duck が
 * 往復するのが問題になれば、将来 focus を握り続ける最適化を検討する。
 */
internal class TtsAudioFocusManager(
    context: Context,
) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null

    /**
     * AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK で focus を要求する。
     *
     * @param channel 発話に使う音声チャンネル。focus 要求の usage も揃えて他アプリを duck させる。
     */
    fun request(channel: NavigationAudioChannel) {
        val attributes = AudioAttributes.Builder()
            .setUsage(channel.usage)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val newRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .build()

        focusRequest = newRequest
        runCatching { audioManager.requestAudioFocus(newRequest) }
    }

    /** 取得済みの focus を解放する。未取得なら何もしない。 */
    fun abandon() {
        val currentRequest = focusRequest ?: return
        runCatching { audioManager.abandonAudioFocusRequest(currentRequest) }
        focusRequest = null
    }
}
