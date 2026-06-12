package me.matsumo.onenavi.core.navigation.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

/**
 * 発話中だけ他アプリの音声を一時的に下げる (duck) ための AudioFocus ラッパー。
 *
 * scheduler 側がキューを持つため本実装は発話ごとに request/abandon する。割り込み発話では
 * 後続発話の focus を先行発話の finally が解放しないよう、世代トークンで要求元を照合する。
 */
internal class TtsAudioFocusManager(
    context: Context,
) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val focusLock = Any()
    private val generation = TtsAudioFocusGeneration()
    private var focusRequest: AudioFocusRequest? = null

    /**
     * AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK で focus を要求する。
     *
     * @param channel 発話に使う音声チャンネル。focus 要求の usage も揃えて他アプリを duck させる。
     * @return [abandon] で要求元を照合する世代トークン
     */
    fun request(channel: NavigationAudioChannel): Int {
        val newRequest = buildFocusRequest(channel)

        return synchronized(focusLock) {
            val focusToken = generation.issueToken()
            focusRequest = newRequest
            runCatching { audioManager.requestAudioFocus(newRequest) }

            focusToken
        }
    }

    /** [request] が返した [focusToken] が現役世代の場合だけ focus を解放する。 */
    fun abandon(focusToken: Int) {
        synchronized(focusLock) {
            if (!generation.ownsFocus(focusToken)) return

            val currentRequest = focusRequest ?: return
            runCatching { audioManager.abandonAudioFocusRequest(currentRequest) }
            focusRequest = null
        }
    }

    private fun buildFocusRequest(channel: NavigationAudioChannel): AudioFocusRequest {
        val attributes = AudioAttributes.Builder()
            .setUsage(channel.usage)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        return AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .build()
    }
}

/**
 * AudioFocus 要求の世代トークンを採番し、現役世代かどうかを判定する状態ホルダー。
 */
internal class TtsAudioFocusGeneration {

    private var currentToken = 0

    /** 新しい AudioFocus 要求に対応する世代トークンを発行する。 */
    fun issueToken(): Int {
        currentToken += 1

        return currentToken
    }

    /** [focusToken] が最新の AudioFocus 要求に対応している場合に true を返す。 */
    fun ownsFocus(focusToken: Int): Boolean {
        return focusToken == currentToken
    }
}
