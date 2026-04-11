package me.matsumo.onenavi.core.navigation.tts

import kotlinx.coroutines.flow.StateFlow

/**
 * 音声合成エンジンの差し替えポイント。
 */
interface TtsEngine {
    val isReady: StateFlow<Boolean>
    var onUtteranceCompleted: ((String) -> Unit)?

    fun speak(
        text: String,
        utteranceId: String,
        queueMode: SpeechQueueMode,
    ): Boolean

    fun stop()

    fun shutdown()
}
