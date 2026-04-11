package me.matsumo.onenavi.core.navigation.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Android 標準 TextToSpeech を利用する TTS エンジン実装。
 */
class AndroidTtsEngine(
    context: Context,
    private val audioFocusManager: AudioFocusManager,
) : TtsEngine {

    private var textToSpeech: TextToSpeech? = null
    private val _isReady = MutableStateFlow(false)
    private var isShutdown = false

    override val isReady: StateFlow<Boolean> = _isReady.asStateFlow()
    override var onUtteranceCompleted: ((String) -> Unit)? = null

    var onReadyChanged: ((Boolean) -> Unit)? = null

    init {
        textToSpeech = TextToSpeech(context) { status ->
            val tts = textToSpeech?.takeUnless { isShutdown } ?: return@TextToSpeech
            if (status == TextToSpeech.SUCCESS) {
                tts.setOnUtteranceProgressListener(createUtteranceProgressListener())

                val languageResult = tts.setLanguage(Locale.JAPAN)
                val ready = languageResult != TextToSpeech.LANG_MISSING_DATA &&
                    languageResult != TextToSpeech.LANG_NOT_SUPPORTED
                _isReady.value = ready
                onReadyChanged?.invoke(ready)
                Napier.d(tag = TAG) { "TTS initialized: ready=$ready" }
            } else {
                _isReady.value = false
                onReadyChanged?.invoke(false)
                Napier.w(tag = TAG) { "TTS initialization failed: status=$status" }
            }
        }
    }

    override fun speak(
        text: String,
        utteranceId: String,
        queueMode: SpeechQueueMode,
    ): Boolean {
        if (!isReady.value) return false

        audioFocusManager.request()
        val result = textToSpeech?.speak(
            text,
            when (queueMode) {
                SpeechQueueMode.FLUSH -> TextToSpeech.QUEUE_FLUSH
                SpeechQueueMode.ADD -> TextToSpeech.QUEUE_ADD
            },
            null,
            utteranceId,
        ) ?: TextToSpeech.ERROR

        val spoken = result == TextToSpeech.SUCCESS
        if (!spoken) {
            audioFocusManager.abandon()
        }
        return spoken
    }

    override fun stop() {
        textToSpeech?.stop()
        audioFocusManager.abandon()
    }

    override fun shutdown() {
        isShutdown = true
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        _isReady.value = false
        audioFocusManager.abandon()
    }

    private fun createUtteranceProgressListener(): UtteranceProgressListener {
        return object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                audioFocusManager.abandon()
                utteranceId?.let { onUtteranceCompleted?.invoke(it) }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                audioFocusManager.abandon()
                utteranceId?.let { onUtteranceCompleted?.invoke(it) }
            }
        }
    }

    /**
     * ログ出力用の定数。
     */
    private companion object {
        private const val TAG = "AndroidTtsEngine"
    }
}
