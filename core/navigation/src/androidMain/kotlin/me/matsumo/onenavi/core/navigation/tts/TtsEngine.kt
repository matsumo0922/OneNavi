package me.matsumo.onenavi.core.navigation.tts

import kotlinx.coroutines.flow.StateFlow

interface TtsEngine {
    val isReady: StateFlow<Boolean>

    fun speak(
        text: String,
        utteranceId: String,
        queueMode: SpeechQueueMode,
    )

    fun stop()

    fun shutdown()
}

