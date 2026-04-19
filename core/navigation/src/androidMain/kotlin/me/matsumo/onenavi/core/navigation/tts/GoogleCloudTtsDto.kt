package me.matsumo.onenavi.core.navigation.tts

import kotlinx.serialization.Serializable

/**
 * Google Cloud Text-to-Speech `text:synthesize` API のリクエスト JSON。
 */
@Serializable
internal data class SynthesizeRequest(
    val input: SynthesisInput,
    val voice: VoiceSelectionParams,
    val audioConfig: AudioConfig,
)

/**
 * 合成対象となる入力テキスト。現状プレーンテキストのみ対応。
 */
@Serializable
internal data class SynthesisInput(
    val text: String,
)

/**
 * 合成に使う声の指定。Chirp 3 HD の voice name を指定する。
 */
@Serializable
internal data class VoiceSelectionParams(
    val languageCode: String,
    val name: String,
)

/**
 * 合成後の音声フォーマット設定。LINEAR16 PCM を指定する。
 */
@Serializable
internal data class AudioConfig(
    val audioEncoding: String,
    val sampleRateHertz: Int,
    val speakingRate: Double,
    val pitch: Double,
)

/**
 * `text:synthesize` API のレスポンス JSON。
 *
 * @param audioContent base64 エンコードされた音声バイト列。LINEAR16 では WAV ヘッダ (44 byte) + PCM。
 */
@Serializable
internal data class SynthesizeResponse(
    val audioContent: String,
)
