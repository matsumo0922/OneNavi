package me.matsumo.onenavi.core.navigation.tts

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * primary (Google Cloud TTS) を優先し、失敗時に fallback (Android 標準 TTS) へ切り替える合成 TtsEngine。
 *
 * - primary が `isReady == false` / `speak()` が false を返した場合に fallback を呼ぶ
 * - `isReady` は primary または fallback のいずれかが ready なら true
 * - どちらの完了通知も上位の `onUtteranceCompleted` に転送する
 */
internal class FallbackTtsEngine(
    private val primary: TtsEngine,
    private val fallback: TtsEngine,
) : TtsEngine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val readyState = MutableStateFlow(primary.isReady.value || fallback.isReady.value)
    private val readyJob: Job

    override val isReady: StateFlow<Boolean> = readyState.asStateFlow()
    override var onUtteranceCompleted: ((String) -> Unit)? = null
        set(value) {
            field = value
            primary.onUtteranceCompleted = value
            fallback.onUtteranceCompleted = value
        }

    init {
        primary.onUtteranceCompleted = { onUtteranceCompleted?.invoke(it) }
        fallback.onUtteranceCompleted = { onUtteranceCompleted?.invoke(it) }

        readyJob = scope.launch {
            combine(primary.isReady, fallback.isReady) { primaryReady, fallbackReady ->
                primaryReady || fallbackReady
            }.collect { ready ->
                readyState.value = ready
            }
        }
    }

    override fun speak(
        text: String,
        utteranceId: String,
        queueMode: SpeechQueueMode,
    ): Boolean = speak(TtsInput.Plain(text), utteranceId, queueMode)

    override fun speak(
        input: TtsInput,
        utteranceId: String,
        queueMode: SpeechQueueMode,
    ): Boolean {
        if (primary.isReady.value) {
            val spoken = primary.speak(
                input = input,
                utteranceId = utteranceId,
                queueMode = queueMode,
            )
            if (spoken) return true
        }
        return fallback.speak(
            input = input,
            utteranceId = utteranceId,
            queueMode = queueMode,
        )
    }

    override fun stop() {
        primary.stop()
        fallback.stop()
    }

    override fun shutdown() {
        readyJob.cancel()
        scope.cancel()
        primary.shutdown()
        fallback.shutdown()
    }
}
