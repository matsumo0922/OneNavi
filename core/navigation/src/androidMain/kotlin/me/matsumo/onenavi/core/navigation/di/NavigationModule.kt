package me.matsumo.onenavi.core.navigation.di

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import me.matsumo.onenavi.core.model.AppConfig
import me.matsumo.onenavi.core.navigation.CameraManager
import me.matsumo.onenavi.core.navigation.GuidanceSessionManager
import me.matsumo.onenavi.core.navigation.NavigationSdkManager
import me.matsumo.onenavi.core.navigation.RouteManager
import me.matsumo.onenavi.core.navigation.tts.AndroidTtsEngine
import me.matsumo.onenavi.core.navigation.tts.AudioFocusManager
import me.matsumo.onenavi.core.navigation.tts.FallbackTtsEngine
import me.matsumo.onenavi.core.navigation.tts.GoogleCloudTtsApi
import me.matsumo.onenavi.core.navigation.tts.GoogleCloudTtsConfig
import me.matsumo.onenavi.core.navigation.tts.GoogleCloudTtsEngine
import me.matsumo.onenavi.core.navigation.tts.PcmAudioPlayer
import me.matsumo.onenavi.core.navigation.tts.SpeechQueueMode
import me.matsumo.onenavi.core.navigation.tts.TtsAudioCache
import me.matsumo.onenavi.core.navigation.tts.TtsEngine
import me.matsumo.onenavi.core.navigation.tts.fetchSigningCertSha1
import org.koin.android.ext.koin.androidApplication
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

actual val navigationModule: Module = module {
    single { RouteManager() }
    single { NavigationSdkManager(androidApplication(), get()) }
    single { CameraManager(get()) }
    single<HttpClient>(qualifier = org.koin.core.qualifier.named("googleCloudTts")) {
        HttpClient(OkHttp) {
            install(HttpTimeout) {
                connectTimeoutMillis = 3_000
                requestTimeoutMillis = 8_000
                socketTimeoutMillis = 5_000
            }
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        isLenient = true
                    },
                )
            }
        }
    }
    single {
        val context = androidContext()
        GuidanceSessionManager(
            cameraManager = get(),
            routeManager = get(),
            navigationSdkManager = get(),
            ttsEngineFactory = {
                createTtsEngine(context, get(), get(qualifier = org.koin.core.qualifier.named("googleCloudTts")))
            },
        )
    }
}

private fun createTtsEngine(
    context: android.content.Context,
    appConfig: AppConfig,
    httpClient: HttpClient,
): TtsEngine {
    val androidEngine = AndroidTtsEngine(
        context = context,
        audioFocusManager = AudioFocusManager(context),
    )
    val apiKey = appConfig.googleCloudTtsApiKey
    if (apiKey.isBlank()) return androidEngine

    val ttsConfig = GoogleCloudTtsConfig(
        apiKey = apiKey,
        androidPackageName = context.packageName,
        androidCertSha1 = context.fetchSigningCertSha1(),
    )
    val googleEngine = GoogleCloudTtsEngine(
        api = GoogleCloudTtsApi(httpClient = httpClient, config = ttsConfig),
        audioPlayer = PcmAudioPlayer(),
        audioFocusManager = AudioFocusManager(context),
        audioCache = TtsAudioCache(),
        apiKey = apiKey,
    )
    googleEngine.onSynthesisFailed = { text, utteranceId ->
        androidEngine.speak(
            text = text,
            utteranceId = utteranceId,
            queueMode = SpeechQueueMode.ADD,
        )
    }
    return FallbackTtsEngine(primary = googleEngine, fallback = androidEngine)
}
