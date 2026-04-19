package me.matsumo.onenavi.core.navigation.tts

import android.util.Base64
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess

/**
 * Google Cloud Text-to-Speech REST API の薄いクライアント。
 *
 * 認証は API キー + Android アプリ制限 (パッケージ名 + SHA-1) で行う。
 */
internal class GoogleCloudTtsApi(
    private val httpClient: HttpClient,
    private val config: GoogleCloudTtsConfig,
) {

    /**
     * `text:synthesize` に POST して、デコード済みの音声バイト列を返す。
     *
     * @throws GoogleCloudTtsException HTTP 非 2xx または body 不正時
     */
    suspend fun synthesize(text: String): ByteArray {
        val request = SynthesizeRequest(
            input = SynthesisInput(text = text),
            voice = VoiceSelectionParams(
                languageCode = config.languageCode,
                name = config.voiceName,
            ),
            audioConfig = AudioConfig(
                audioEncoding = "LINEAR16",
                sampleRateHertz = SAMPLE_RATE_HZ,
                speakingRate = config.speakingRate,
                pitch = config.pitch,
            ),
        )

        val response = httpClient.post(ENDPOINT) {
            header("x-goog-api-key", config.apiKey)
            header("X-Android-Package", config.androidPackageName)
            header("X-Android-Cert", config.androidCertSha1)
            contentType(ContentType.Application.Json)
            setBody(request)
        }

        if (!response.status.isSuccess()) {
            val snippet = runCatching { response.bodyAsText() }.getOrNull()?.take(MAX_ERROR_BODY)
            throw GoogleCloudTtsException(
                status = response.status,
                message = "TTS synthesize failed: status=${response.status.value} body=$snippet",
            )
        }

        val body: SynthesizeResponse = response.body()
        val encoded = body.audioContent
        if (encoded.isEmpty()) {
            throw GoogleCloudTtsException(
                status = response.status,
                message = "TTS synthesize returned empty audioContent",
            )
        }
        return Base64.decode(encoded, Base64.DEFAULT)
    }

    private companion object {
        private const val ENDPOINT = "https://texttospeech.googleapis.com/v1/text:synthesize"
        private const val SAMPLE_RATE_HZ = 24_000
        private const val MAX_ERROR_BODY = 500
    }
}

/**
 * Google Cloud TTS へのリクエストに必要な設定値。
 *
 * @param apiKey Cloud Console で発行した API キー
 * @param androidPackageName 実行中アプリのパッケージ名 (例: `me.matsumo.onenavi.debug`)
 * @param androidCertSha1 APK 署名証明書の SHA-1 (大文字・`:` 区切り)
 * @param voiceName 使用する voice (Chirp 3 HD Laomedeia がデフォルト)
 * @param languageCode BCP-47 言語コード
 * @param speakingRate 発話速度 (1.0 で等速)
 * @param pitch 音高補正 (0.0 で無補正)
 */
internal data class GoogleCloudTtsConfig(
    val apiKey: String,
    val androidPackageName: String,
    val androidCertSha1: String,
    val voiceName: String = "ja-JP-Chirp3-HD-Laomedeia",
    val languageCode: String = "ja-JP",
    val speakingRate: Double = 1.0,
    val pitch: Double = 0.0,
)

/**
 * Google Cloud TTS API 呼び出しで発生したエラー。
 */
internal class GoogleCloudTtsException(
    val status: HttpStatusCode,
    message: String,
) : RuntimeException(message)
