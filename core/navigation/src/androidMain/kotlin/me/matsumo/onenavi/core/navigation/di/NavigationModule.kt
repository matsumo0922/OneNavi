package me.matsumo.onenavi.core.navigation.di

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import me.matsumo.drive.supporter.api.core.model.LogLevel
import me.matsumo.onenavi.core.datasource.RouteDataSource
import me.matsumo.onenavi.core.model.AppConfig
import me.matsumo.onenavi.core.navigation.extnav.ExtNavAuthGateway
import me.matsumo.onenavi.core.navigation.extnav.ExtNavClientProvider
import me.matsumo.onenavi.core.navigation.extnav.ExtNavGuidanceTracker
import me.matsumo.onenavi.core.navigation.extnav.ExtNavGuideImageGateway
import me.matsumo.onenavi.core.navigation.extnav.ExtNavRerouteDetector
import me.matsumo.onenavi.core.navigation.extnav.ExtNavRouteDataSource
import me.matsumo.onenavi.core.navigation.extnav.ExtNavRouteRegistry
import me.matsumo.onenavi.core.navigation.newguidance.NewGuidanceManager
import me.matsumo.onenavi.core.navigation.newguidance.NewRouteManager
import me.matsumo.onenavi.core.navigation.tts.CachedGoogleCloudTtsSynthesizer
import me.matsumo.onenavi.core.navigation.tts.DefaultMilestoneAnnouncementProvider
import me.matsumo.onenavi.core.navigation.tts.DefaultOpeningAnnouncementProvider
import me.matsumo.onenavi.core.navigation.tts.GoogleCloudTtsApi
import me.matsumo.onenavi.core.navigation.tts.GoogleCloudTtsSynthesisConfig
import me.matsumo.onenavi.core.navigation.tts.GoogleCloudTtsVoiceAnnouncementDispatcher
import me.matsumo.onenavi.core.navigation.tts.GuidanceChimePlayer
import me.matsumo.onenavi.core.navigation.tts.NavigationAudioChannelResolver
import me.matsumo.onenavi.core.navigation.tts.PcmAudioPlayer
import me.matsumo.onenavi.core.navigation.tts.TtsAudioFileCache
import me.matsumo.onenavi.core.navigation.tts.TtsAudioFocusManager
import me.matsumo.onenavi.core.navigation.tts.TtsSigningCertificate
import me.matsumo.onenavi.core.navigation.voice.config.VoiceAnnouncementCategoryGateResolver
import me.matsumo.onenavi.core.navigation.voice.config.VoiceAnnouncementConfig
import me.matsumo.onenavi.core.navigation.voice.dispatch.VoiceAnnouncementContentRenderer
import me.matsumo.onenavi.core.navigation.voice.dispatch.VoiceAnnouncementDispatcher
import me.matsumo.onenavi.core.navigation.voice.plan.VoiceAnnouncementPlanBuilder
import me.matsumo.onenavi.core.navigation.voice.scheduler.VoiceAnnouncementController
import me.matsumo.onenavi.core.navigation.voice.scheduler.VoiceAnnouncementPrefetcher
import me.matsumo.onenavi.core.navigation.voice.scheduler.VoiceAnnouncementScheduler
import me.matsumo.onenavi.core.navigation.voice.scheduler.VoiceAnnouncementSpeechRunner
import me.matsumo.onenavi.core.navigation.voice.scheduler.VoiceTickFactory
import me.matsumo.onenavi.core.navigation.voice.selector.VoiceAnnouncementSelector
import me.matsumo.onenavi.core.navigation.voice.suppression.VoiceAnnouncementSelectionPolicy
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

val navigationModule: Module = module {
    single { NewRouteManager(routeRepository = get()) }
    single { ExtNavGuidanceTracker() }
    single { ExtNavRerouteDetector() }
    single { VoiceAnnouncementConfig() }
    single { VoiceAnnouncementPlanBuilder() }
    single { VoiceAnnouncementSelector(config = get()) }
    single { VoiceAnnouncementSelectionPolicy() }
    single<VoiceAnnouncementDispatcher> {
        val appConfig = get<AppConfig>()
        val context = androidContext()
        val audioPlayer = PcmAudioPlayer()
        val synthesisConfig = GoogleCloudTtsSynthesisConfig()
        GoogleCloudTtsVoiceAnnouncementDispatcher(
            synthesizer = CachedGoogleCloudTtsSynthesizer(
                backend = GoogleCloudTtsApi(
                    httpClient = get(named("googleCloudTts")),
                    apiKey = appConfig.googleCloudTtsApiKey,
                    packageName = context.packageName,
                    signatureSha1 = TtsSigningCertificate.resolveSha1(context),
                    synthesisConfig = synthesisConfig,
                ),
                cache = TtsAudioFileCache(
                    directory = context.cacheDir.resolve(GoogleCloudTtsSynthesisConfig.CACHE_SCHEMA_VERSION),
                ),
                synthesisConfig = synthesisConfig,
                apiKey = appConfig.googleCloudTtsApiKey,
            ),
            audioPlayer = audioPlayer,
            chimePlayer = GuidanceChimePlayer(
                context = context,
                audioPlayer = audioPlayer,
            ),
            audioFocusManager = TtsAudioFocusManager(context),
            audioChannelResolver = NavigationAudioChannelResolver(appSettingRepository = get()),
        )
    }
    single { VoiceAnnouncementCategoryGateResolver(appSettingRepository = get()) }
    single {
        val gateResolver = get<VoiceAnnouncementCategoryGateResolver>()
        VoiceAnnouncementContentRenderer(
            categoryGateProvider = gateResolver::resolve,
        )
    }
    single {
        VoiceAnnouncementScheduler(
            selector = get(),
            policy = get(),
            contentRenderer = get(),
        )
    }
    single {
        VoiceAnnouncementPrefetcher(
            contentRenderer = get(),
            dispatcher = get(),
        )
    }
    single {
        VoiceAnnouncementSpeechRunner(
            scheduler = get(),
            dispatcher = get(),
            openingAnnouncementProvider = DefaultOpeningAnnouncementProvider(),
            milestoneAnnouncementProvider = DefaultMilestoneAnnouncementProvider(),
        )
    }
    single { VoiceTickFactory() }
    single {
        VoiceAnnouncementController(
            planBuilder = get(),
            tickFactory = get(),
            speechRunner = get(),
            prefetcher = get(),
            config = get(),
        )
    }
    single {
        NewGuidanceManager(
            routeRegistry = get(),
            guidanceTracker = get(),
            locationDataSource = get(),
            voiceController = get(),
            rerouteDetector = get(),
            routeRepository = get(),
        )
    }
    single<HttpClient>(qualifier = named("googleCloudTts")) {
        HttpClient(OkHttp) {
            install(HttpTimeout) {
                connectTimeoutMillis = 5_000
                requestTimeoutMillis = 30_000
                socketTimeoutMillis = 30_000
            }
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        isLenient = true
                        encodeDefaults = true
                    },
                )
            }
        }
    }
    single {
        ExtNavClientProvider(
            context = androidContext(),
            appSettingDataSource = get(),
            logLevel = LogLevel.HEADERS,
        )
    }
    single {
        ExtNavAuthGateway(
            clientProvider = get(),
            appConfig = get(),
        )
    }
    single { ExtNavRouteRegistry() }
    single {
        ExtNavGuideImageGateway(
            clientProvider = get(),
            authGateway = get(),
            routeRegistry = get(),
        )
    }
    single<RouteDataSource> {
        ExtNavRouteDataSource(
            clientProvider = get(),
            authGateway = get(),
            registry = get(),
        )
    }
}
