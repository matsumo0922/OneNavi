package me.matsumo.onenavi.core.navigation.extnav

import me.matsumo.onenavi.core.navigation.tts.PhonemeConverter
import me.matsumo.onenavi.core.navigation.tts.SpeechQueueMode
import me.matsumo.onenavi.core.navigation.tts.TtsEngine
import me.matsumo.onenavi.core.navigation.tts.TtsInput

/**
 * drive-supporter-api の SSML を [TtsEngine] に投げる薄いラッパー。
 *
 * - Cloud TTS 側には W3C 互換 SSML を渡し (`PhonemeConverter.toGoogleCloudSsml`)、
 *   Android TTS 側には plaintext に落として読ませる
 * - utteranceId は呼び出し側が一意に決める (GP index + phrase index の組み合わせ等)
 */
class ExtNavSsmlSpeaker(
    private val engine: TtsEngine,
) {
    /**
     * SSML 入力で発話。空文字・タグのみの場合は無視して false を返す。
     */
    fun speakSsml(
        ssml: String,
        utteranceId: String,
        queueMode: SpeechQueueMode = SpeechQueueMode.ADD,
    ): Boolean {
        val trimmed = ssml.trim()
        if (trimmed.isEmpty()) return false
        if (PhonemeConverter.toPlainText(trimmed).isBlank()) return false
        val normalised = PhonemeConverter.toGoogleCloudSsml(trimmed)
        return engine.speak(
            input = TtsInput.Ssml(normalised),
            utteranceId = utteranceId,
            queueMode = queueMode,
        )
    }

    /**
     * セッション境界 (「ルート案内を開始します」など) のプレーン発話。
     */
    fun speakPlain(
        text: String,
        utteranceId: String,
        queueMode: SpeechQueueMode = SpeechQueueMode.ADD,
    ): Boolean {
        if (text.isBlank()) return false
        return engine.speak(
            input = TtsInput.Plain(text),
            utteranceId = utteranceId,
            queueMode = queueMode,
        )
    }

    fun stop() {
        engine.stop()
    }
}
