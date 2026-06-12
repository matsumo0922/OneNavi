package me.matsumo.onenavi.core.navigation.tts

import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.matsumo.onenavi.core.model.AppSetting
import me.matsumo.onenavi.core.model.DeveloperFeature

/**
 * Google Cloud TTS 合成設定。
 *
 * 音声キャッシュキーにも使うため、リクエスト body に入る値を 1 か所に集約する。
 *
 * @property languageCode 言語コード
 * @property voiceName voice 名
 * @property audioEncoding エンコーディング
 * @property sampleRateHertz サンプリングレート
 * @property speakingRate 話速
 * @property pitch ピッチ
 * @property volumeGainDb 音量ゲイン
 */
@Immutable
internal data class GoogleCloudTtsSynthesisConfig(
    val languageCode: String = DEFAULT_LANGUAGE_CODE,
    val voiceName: String = DEFAULT_VOICE_NAME,
    val audioEncoding: String = DEFAULT_AUDIO_ENCODING,
    val sampleRateHertz: Int = DEFAULT_SAMPLE_RATE_HERTZ,
    val speakingRate: Double = DEFAULT_SPEAKING_RATE,
    val pitch: Double = DEFAULT_PITCH,
    val volumeGainDb: Double = DEFAULT_VOLUME_GAIN_DB,
) {

    /** Google Cloud TTS に渡せる範囲へ丸めた音量ゲインを返す。 */
    fun resolvedVolumeGainDb(): Double =
        volumeGainDb.coerceIn(
            minimumValue = MIN_VOLUME_GAIN_DB,
            maximumValue = MAX_VOLUME_GAIN_DB,
        )

    /**
     * 指定 SSML と現在の音声設定から安定したキャッシュキーを作る。
     *
     * @param ssml 合成対象 SSML
     * @return 音声設定と SSML を含むキャッシュキー
     */
    fun cacheKeyOf(ssml: String): String = listOf(
        CACHE_SCHEMA_VERSION,
        languageCode,
        voiceName,
        audioEncoding,
        sampleRateHertz.toString(),
        speakingRate.toString(),
        pitch.toString(),
        resolvedVolumeGainDb().toString(),
        ssml,
    ).joinToString(separator = CACHE_KEY_SEPARATOR)

    /** Google Cloud TTS 合成設定の既定値。 */
    internal companion object {

        /** キャッシュキーの互換性を切り替える schema version。 */
        const val CACHE_SCHEMA_VERSION = "tts-audio-v2"

        /** キャッシュキー内の区切り文字。SSML 本文と衝突しても SHA-256 化するため問題にならない。 */
        const val CACHE_KEY_SEPARATOR = "\u001F"

        /** 既定の言語コード (日本語)。 */
        const val DEFAULT_LANGUAGE_CODE = "ja-JP"

        /** 既定の voice 名 (Chirp 3 HD / Despina)。 */
        const val DEFAULT_VOICE_NAME = "ja-JP-Chirp3-HD-Despina"

        /** 既定のエンコーディング (WAV ヘッダ付き LINEAR16 PCM)。 */
        const val DEFAULT_AUDIO_ENCODING = "LINEAR16"

        /** 既定のサンプリングレート。 */
        const val DEFAULT_SAMPLE_RATE_HERTZ = 24_000

        /** 既定の話速。 */
        const val DEFAULT_SPEAKING_RATE = 1.0

        /** 既定のピッチ。 */
        const val DEFAULT_PITCH = 0.0

        /** 既定の音量ゲイン。 */
        const val DEFAULT_VOLUME_GAIN_DB = 0.0

        /** Google Cloud TTS が許容する最小音量ゲイン。 */
        const val MIN_VOLUME_GAIN_DB = -96.0

        /** Google Cloud TTS が許容する最大音量ゲイン。 */
        const val MAX_VOLUME_GAIN_DB = 16.0

        /**
         * 現在のアプリ設定から合成設定を作る。
         *
         * voice 名と話速は開発者向け機能が有効な場合だけ上書きし、通常時の既定 voice は変更しない。
         *
         * @param setting アプリ設定
         * @return 合成リクエストと cache key に使う TTS 設定
         */
        fun fromAppSetting(setting: AppSetting): GoogleCloudTtsSynthesisConfig {
            if (!setting.isDeveloperFeatureEnabled(DeveloperFeature.TTS_VOICE_OVERRIDE)) {
                return GoogleCloudTtsSynthesisConfig(volumeGainDb = setting.ttsVolumeGainDb)
            }

            return GoogleCloudTtsSynthesisConfig(
                voiceName = setting.resolvedTtsVoiceNameOverride(),
                speakingRate = setting.ttsSpeakingRateOverride.coerceIn(
                    minimumValue = AppSetting.TTS_SPEAKING_RATE_OVERRIDE_MIN,
                    maximumValue = AppSetting.TTS_SPEAKING_RATE_OVERRIDE_MAX,
                ),
                volumeGainDb = setting.ttsVolumeGainDb,
            )
        }
    }
}

private fun AppSetting.resolvedTtsVoiceNameOverride(): String {
    val resolvedVoiceName = ttsVoiceNameOverride.trim()

    return resolvedVoiceName.ifBlank { GoogleCloudTtsSynthesisConfig.DEFAULT_VOICE_NAME }
}

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
    @SerialName("languageCode") val languageCode: String = GoogleCloudTtsSynthesisConfig.DEFAULT_LANGUAGE_CODE,
    val name: String = GoogleCloudTtsSynthesisConfig.DEFAULT_VOICE_NAME,
)

/**
 * 出力音声フォーマット。AudioTrack で即時再生するため LINEAR16 (24kHz mono) を既定とする。
 *
 * @property audioEncoding エンコーディング
 * @property sampleRateHertz サンプリングレート
 * @property speakingRate 話速 (1.0 が等速)
 * @property pitch ピッチ (0.0 が無調整)
 * @property volumeGainDb 音量ゲイン (dB)
 */
@Serializable
internal data class SynthesizeAudioConfig(
    @SerialName("audioEncoding") val audioEncoding: String = GoogleCloudTtsSynthesisConfig.DEFAULT_AUDIO_ENCODING,
    @SerialName("sampleRateHertz") val sampleRateHertz: Int = GoogleCloudTtsSynthesisConfig.DEFAULT_SAMPLE_RATE_HERTZ,
    @SerialName("speakingRate") val speakingRate: Double = GoogleCloudTtsSynthesisConfig.DEFAULT_SPEAKING_RATE,
    val pitch: Double = GoogleCloudTtsSynthesisConfig.DEFAULT_PITCH,
    @SerialName("volumeGainDb") val volumeGainDb: Double = GoogleCloudTtsSynthesisConfig.DEFAULT_VOLUME_GAIN_DB,
)

/**
 * `text:synthesize` のレスポンス body。
 *
 * @property audioContent base64 エンコードされた音声データ (LINEAR16 は 44byte WAV ヘッダ + PCM)
 */
@Serializable
internal data class SynthesizeResponse(
    @SerialName("audioContent") val audioContent: String,
)
