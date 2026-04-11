package me.matsumo.onenavi.core.navigation.tts

import me.matsumo.onenavi.core.navigation.guidance.GuidanceEvent
import me.matsumo.onenavi.core.navigation.guidance.GuidancePriority
import me.matsumo.onenavi.core.navigation.guidance.GuidanceSpeechHistory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 発話の優先度、重複抑制、完了通知をまとめて制御するクラス。
 */
class SpeechOrchestrator(
    private val ttsEngine: TtsEngine,
    private val speechHistory: GuidanceSpeechHistory,
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
        event: GuidanceEvent,
        text: String,
        onComplete: (() -> Unit)? = null,
    ): Boolean {
        if (muted || text.isBlank()) return false
        if (!ttsEngine.isReady.value) return false
        if (speechHistory.hasSpoken(event.id)) return false

        val queueMode = when (event.priority) {
            GuidancePriority.CRITICAL,
            GuidancePriority.HIGH,
            -> SpeechQueueMode.FLUSH
            GuidancePriority.NORMAL,
            GuidancePriority.LOW,
            -> SpeechQueueMode.ADD
        }

        val utteranceId = UUID.randomUUID().toString()
        if (queueMode == SpeechQueueMode.FLUSH) {
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
        speechHistory.markSpoken(event.id)
        return true
    }

    fun shutdown() {
        completionCallbacks.clear()
        ttsEngine.shutdown()
        speechHistory.clear()
    }

    private fun handleUtteranceCompleted(utteranceId: String) {
        completionCallbacks.remove(utteranceId)?.invoke()
    }
}
