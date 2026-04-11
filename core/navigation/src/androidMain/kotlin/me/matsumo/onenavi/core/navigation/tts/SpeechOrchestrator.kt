package me.matsumo.onenavi.core.navigation.tts

import me.matsumo.onenavi.core.navigation.guidance.GuidanceEvent
import me.matsumo.onenavi.core.navigation.guidance.GuidancePriority
import me.matsumo.onenavi.core.navigation.guidance.GuidanceSpeechHistory
import java.util.UUID

class SpeechOrchestrator(
    private val ttsEngine: TtsEngine,
    private val speechHistory: GuidanceSpeechHistory,
) {

    var muted: Boolean = false
        private set

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
    ) {
        if (muted || text.isBlank()) return
        if (speechHistory.hasSpoken(event.id)) return

        val queueMode = when (event.priority) {
            GuidancePriority.CRITICAL,
            GuidancePriority.HIGH,
            -> SpeechQueueMode.FLUSH
            GuidancePriority.NORMAL,
            GuidancePriority.LOW,
            -> SpeechQueueMode.ADD
        }

        ttsEngine.speak(
            text = text,
            utteranceId = UUID.randomUUID().toString(),
            queueMode = queueMode,
        )
        speechHistory.markSpoken(event.id)
    }

    fun shutdown() {
        ttsEngine.shutdown()
        speechHistory.clear()
    }
}
