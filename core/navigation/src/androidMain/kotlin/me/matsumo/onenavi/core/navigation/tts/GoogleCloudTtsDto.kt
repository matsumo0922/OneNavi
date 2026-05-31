package me.matsumo.onenavi.core.navigation.tts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Google Cloud Text-to-Speech `text:synthesize` のリクエスト body。
 *
 * @property input 読み上げ対象 (text または ssml の排他)
 * @property voice 使用する音声 (言語・voice 名)
 * @property audioConfig 出力音声フォーマット
 */
@Serializable
internal data class SynthesizeRequest(
    val input: SynthesizeInput,
    val voice: SynthesizeVoice = SynthesizeVoice(),
    @SerialName("audioConfig") val audioConfig: SynthesizeAudioConfig = SynthesizeAudioConfig(),
)

/**
 * 読み上げ入力。`text` と `ssml` は排他で、SSML がある場合は `ssml` のみを詰める。
 *
 * @property text プレーンテキスト入力
 * @property ssml SSML 入力 (`<speak>...</speak>`)
 */
@Serializable
internal data class SynthesizeInput(
    val text: String? = null,
    val ssml: String? = null,
)

/**
 * 使用する音声。Chirp 3 HD の日本語 voice を既定とする。
 *
 * @property languageCode 言語コード
 * @property name voice 名
 */
@Serializable
internal data class SynthesizeVoice(
    @SerialName("languageCode") val languageCode: String = DEFAULT_LANGUAGE_CODE,
    val name: String = DEFAULT_VOICE_NAME,
) {

    private companion object {

        /** 既定の言語コード (日本語)。 */
        const val DEFAULT_LANGUAGE_CODE = "ja-JP"

        /** 既定の voice 名 (Chirp 3 HD / Despina)。 */
        const val DEFAULT_VOICE_NAME = "ja-JP-Chirp3-HD-Despina"
    }
}

/**
 * 出力音声フォーマット。AudioTrack で即時再生するため LINEAR16 (24kHz mono) を既定とする。
 *
 * @property audioEncoding エンコーディング
 * @property sampleRateHertz サンプリングレート
 * @property speakingRate 話速 (1.0 が等速)
 * @property pitch ピッチ (0.0 が無調整)
 */
@Serializable
internal data class SynthesizeAudioConfig(
    @SerialName("audioEncoding") val audioEncoding: String = DEFAULT_AUDIO_ENCODING,
    @SerialName("sampleRateHertz") val sampleRateHertz: Int = DEFAULT_SAMPLE_RATE_HERTZ,
    @SerialName("speakingRate") val speakingRate: Double = DEFAULT_SPEAKING_RATE,
    val pitch: Double = DEFAULT_PITCH,
) {

    private companion object {

        /** 既定のエンコーディング (WAV ヘッダ付き LINEAR16 PCM)。 */
        const val DEFAULT_AUDIO_ENCODING = "LINEAR16"

        /** 既定のサンプリングレート。 */
        const val DEFAULT_SAMPLE_RATE_HERTZ = 24_000

        /** 既定の話速。 */
        const val DEFAULT_SPEAKING_RATE = 1.0

        /** 既定のピッチ。 */
        const val DEFAULT_PITCH = 0.0
    }
}

/**
 * `text:synthesize` のレスポンス body。
 *
 * @property audioContent base64 エンコードされた音声データ (LINEAR16 は 44byte WAV ヘッダ + PCM)
 */
@Serializable
internal data class SynthesizeResponse(
    @SerialName("audioContent") val audioContent: String,
)
