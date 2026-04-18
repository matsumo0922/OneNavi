package me.matsumo.onenavi.core.navigation.tts

import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * 発話キューの制御と完了通知を扱うクラス。
 *
 * 発話内容の生成や重複排除、優先度判定は行わず、呼び出し側から渡されたテキストをそのまま
 * TTS エンジンへ流す。発話内容の加工は呼び出し側（GuidanceSessionManager 等）の責務とする。
 */
class SpeechOrchestrator(
    private val ttsEngine: TtsEngine,
) {

    private val completionCallbacks = ConcurrentHashMap<String, () -> Unit>()

    var muted: Boolean = false
        private set

    init {
        ttsEngine.onUtteranceCompleted = ::handleUtteranceCompleted
    }

    fun setMuted(
        muted: Boolean,
        stopCurrent: Boolean = true,
    ) {
        this.muted = muted
        if (muted && stopCurrent) {
            ttsEngine.stop()
        }
    }

    fun enqueue(
        text: String,
        flush: Boolean = false,
        onComplete: (() -> Unit)? = null,
    ): Boolean {
        if (muted || text.isBlank()) return false
        if (!ttsEngine.isReady.value) return false

        val queueMode = if (flush) SpeechQueueMode.FLUSH else SpeechQueueMode.ADD
        val utteranceId = UUID.randomUUID().toString()
        if (flush) {
            completionCallbacks.clear()
        }
        val spoken = ttsEngine.speak(
            text = text,
            utteranceId = utteranceId,
            queueMode = queueMode,
        )
        if (!spoken) return false

        if (onComplete != null) {
            completionCallbacks[utteranceId] = onComplete
        }
        return true
    }

    fun shutdown() {
        completionCallbacks.clear()
        ttsEngine.shutdown()
    }

    private fun handleUtteranceCompleted(utteranceId: String) {
        completionCallbacks.remove(utteranceId)?.invoke()
    }
}
