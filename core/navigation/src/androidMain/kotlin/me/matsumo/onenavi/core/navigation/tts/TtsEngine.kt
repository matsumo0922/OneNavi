package me.matsumo.onenavi.core.navigation.tts

import kotlinx.coroutines.flow.StateFlow

/**
 * TTS への入力。プレーンテキストと SSML を型で区別する。
 */
sealed interface TtsInput {
    data class Plain(val text: String) : TtsInput
    data class Ssml(val ssml: String) : TtsInput
}

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

    /**
     * SSML / プレーンテキストを区別して合成する。
     * SSML 対応は Cloud TTS のみ。Android 内蔵 TTS は SSML を plaintext 化してから喋る。
     */
    fun speak(
        input: TtsInput,
        utteranceId: String,
        queueMode: SpeechQueueMode,
    ): Boolean = when (input) {
        is TtsInput.Plain -> speak(input.text, utteranceId, queueMode)
        is TtsInput.Ssml -> speak(PhonemeConverter.toPlainText(input.ssml), utteranceId, queueMode)
    }

    fun stop()

    fun shutdown()
}
