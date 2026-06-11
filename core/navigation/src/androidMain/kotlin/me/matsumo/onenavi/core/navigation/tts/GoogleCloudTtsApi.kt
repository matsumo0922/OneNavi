package me.matsumo.onenavi.core.navigation.tts

import android.util.Base64
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess

/**
 * Google Cloud TTS への合成失敗を表す例外。HTTP まで到達した場合は [statusCode] を保持する。
 *
 * @property statusCode HTTP ステータスコード (ネットワーク到達前の失敗では null)
 */
internal class GoogleCloudTtsException(
    val statusCode: Int?,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Google Cloud TTS の合成処理を差し替え可能にする backend。
 */
internal interface GoogleCloudTtsSynthesizerBackend {

    /**
     * SSML を合成し、WAV (LINEAR16) バイト列を返す。
     *
     * @param ssml 変換済み SSML (`<speak>` で囲み済み)
     * @param synthesisConfig 合成リクエストに使う音声設定
     * @return base64 デコード済みの WAV バイト列
     */
    suspend fun synthesize(
        ssml: String,
        synthesisConfig: GoogleCloudTtsSynthesisConfig,
    ): ByteArray
}

/**
 * Google Cloud Text-to-Speech REST API (`text:synthesize`) を叩く薄いクライアント。
 *
 * API キーは Android アプリ制限を発動させるため URL クエリではなくヘッダ
 * (`x-goog-api-key` / `X-Android-Package` / `X-Android-Cert`) で送る。
 *
 * @property httpClient named("googleCloudTts") の Ktor クライアント
 * @property apiKey Google Cloud TTS の API キー
 * @property packageName 実行中アプリのパッケージ名 (`X-Android-Package`)
 * @property signatureSha1 実行中 APK 署名の SHA-1 (`X-Android-Cert`、`:` 区切り大文字)
 */
internal class GoogleCloudTtsApi(
    private val httpClient: HttpClient,
    private val apiKey: String,
    private val packageName: String,
    private val signatureSha1: String,
) : GoogleCloudTtsSynthesizerBackend {

    /**
     * SSML を合成し、WAV (LINEAR16) バイト列を返す。
     *
     * @param ssml 変換済み SSML (`<speak>` で囲み済み)
     * @param synthesisConfig 合成リクエストに使う音声設定
     * @return base64 デコード済みの WAV バイト列 (先頭 44byte は WAV ヘッダ)
     */
    override suspend fun synthesize(
        ssml: String,
        synthesisConfig: GoogleCloudTtsSynthesisConfig,
    ): ByteArray {
        val request = SynthesizeRequest(
            input = SynthesizeInput(ssml = ssml),
            voice = SynthesizeVoice(
                languageCode = synthesisConfig.languageCode,
                name = synthesisConfig.voiceName,
            ),
            audioConfig = SynthesizeAudioConfig(
                audioEncoding = synthesisConfig.audioEncoding,
                sampleRateHertz = synthesisConfig.sampleRateHertz,
                speakingRate = synthesisConfig.speakingRate,
                pitch = synthesisConfig.pitch,
                volumeGainDb = synthesisConfig.resolvedVolumeGainDb(),
            ),
        )

        val response = httpClient.post(SYNTHESIZE_ENDPOINT) {
            header(HEADER_API_KEY, apiKey)
            header(HEADER_ANDROID_PACKAGE, packageName)
            header(HEADER_ANDROID_CERT, signatureSha1)
            contentType(ContentType.Application.Json)
            setBody(request)
        }

        if (!response.status.isSuccess()) {
            val errorBody = runCatching { response.bodyAsText() }.getOrDefault("")
            throw GoogleCloudTtsException(
                statusCode = response.status.value,
                message = "synthesize failed: HTTP ${response.status.value} body=$errorBody",
            )
        }

        val audioContent = response.body<SynthesizeResponse>().audioContent
        return runCatching { Base64.decode(audioContent, Base64.DEFAULT) }
            .getOrElse { error ->
                throw GoogleCloudTtsException(
                    statusCode = response.status.value,
                    message = "audioContent base64 decode failed",
                    cause = error,
                )
            }
    }

    private companion object {

        /** 合成エンドポイント。 */
        const val SYNTHESIZE_ENDPOINT = "https://texttospeech.googleapis.com/v1/text:synthesize"

        /** API キーヘッダ名。 */
        const val HEADER_API_KEY = "x-goog-api-key"

        /** 実行中パッケージ名ヘッダ名。 */
        const val HEADER_ANDROID_PACKAGE = "X-Android-Package"

        /** 署名 SHA-1 ヘッダ名。 */
        const val HEADER_ANDROID_CERT = "X-Android-Cert"
    }
}
