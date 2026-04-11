package me.matsumo.onenavi.core.navigation.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

class AndroidTtsEngine(
    context: Context,
    private val audioFocusManager: AudioFocusManager,
) : TtsEngine {

    private var textToSpeech: TextToSpeech? = null
    private val _isReady = MutableStateFlow(false)

    override val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    init {
        textToSpeech = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val languageResult = textToSpeech?.setLanguage(Locale.JAPAN)
                val ready = languageResult != TextToSpeech.LANG_MISSING_DATA &&
                    languageResult != TextToSpeech.LANG_NOT_SUPPORTED
                _isReady.value = ready
                Napier.d(tag = TAG) { "TTS initialized: ready=$ready" }
            } else {
                _isReady.value = false
                Napier.w(tag = TAG) { "TTS initialization failed: status=$status" }
            }
        }

        textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                audioFocusManager.abandon()
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                audioFocusManager.abandon()
            }
        })
    }

    override fun speak(
        text: String,
        utteranceId: String,
        queueMode: SpeechQueueMode,
    ) {
        if (!isReady.value) return

        audioFocusManager.request()
        textToSpeech?.speak(
            text,
            when (queueMode) {
                SpeechQueueMode.FLUSH -> TextToSpeech.QUEUE_FLUSH
                SpeechQueueMode.ADD -> TextToSpeech.QUEUE_ADD
            },
            null,
            utteranceId,
        )
    }

    override fun stop() {
        textToSpeech?.stop()
        audioFocusManager.abandon()
    }

    override fun shutdown() {
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        _isReady.value = false
        audioFocusManager.abandon()
    }

    private companion object {
        private const val TAG = "AndroidTtsEngine"
    }
}

