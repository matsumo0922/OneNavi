package me.matsumo.onenavi.core.navigation.di

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import me.matsumo.drive.supporter.api.core.model.LogLevel
import me.matsumo.onenavi.core.datasource.AppSettingDataSource
import me.matsumo.onenavi.core.datasource.RouteDataSource
import me.matsumo.onenavi.core.model.AppConfig
import me.matsumo.onenavi.core.model.DeveloperFeature
import me.matsumo.onenavi.core.navigation.extnav.ExtNavAuthGateway
import me.matsumo.onenavi.core.navigation.extnav.ExtNavClientProvider
import me.matsumo.onenavi.core.navigation.extnav.ExtNavGuidanceTracker
import me.matsumo.onenavi.core.navigation.extnav.ExtNavGuideImageGateway
import me.matsumo.onenavi.core.navigation.extnav.ExtNavRerouteDetector
import me.matsumo.onenavi.core.navigation.extnav.ExtNavRoadTypeGateway
import me.matsumo.onenavi.core.navigation.extnav.ExtNavRouteDataSource
import me.matsumo.onenavi.core.navigation.extnav.ExtNavRouteRegistry
import me.matsumo.onenavi.core.navigation.newguidance.NewGuidanceManager
import me.matsumo.onenavi.core.navigation.newguidance.NewRouteManager
import me.matsumo.onenavi.core.navigation.server.GuidanceApiClient
import me.matsumo.onenavi.core.navigation.server.GuidanceApiConfig
import me.matsumo.onenavi.core.navigation.server.GuidanceMigrationStage
import me.matsumo.onenavi.core.navigation.server.GuidanceProviderConfig
import me.matsumo.onenavi.core.navigation.server.GuidanceRouteDataSourceSelector
import me.matsumo.onenavi.core.navigation.server.HttpGuidanceApiClient
import me.matsumo.onenavi.core.navigation.server.ServerRouteDataSource
import me.matsumo.onenavi.core.navigation.tts.CachedGoogleCloudTtsSynthesizer
import me.matsumo.onenavi.core.navigation.tts.DefaultMilestoneAnnouncementProvider
import me.matsumo.onenavi.core.navigation.tts.DefaultOpeningAnnouncementProvider
import me.matsumo.onenavi.core.navigation.tts.GoogleCloudTtsApi
import me.matsumo.onenavi.core.navigation.tts.GoogleCloudTtsSynthesisConfig
import me.matsumo.onenavi.core.navigation.tts.GoogleCloudTtsVoiceAnnouncementDispatcher
import me.matsumo.onenavi.core.navigation.tts.GuidanceChimePlayer
import me.matsumo.onenavi.core.navigation.tts.NavigationAudioChannelResolver
import me.matsumo.onenavi.core.navigation.tts.PcmAudioPlayer
import me.matsumo.onenavi.core.navigation.tts.SpeedAdaptiveGainProvider
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
import me.matsumo.onenavi.core.repository.AppSettingRepository
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
        val appSettingRepository = get<AppSettingRepository>()
        val currentLocationDataSource =
            get<me.matsumo.onenavi.core.datasource.location.CurrentLocationDataSource>()
        val speedAdaptiveGainProvider = SpeedAdaptiveGainProvider(
            settingProvider = { appSettingRepository.setting.value },
            speedStateProvider = { currentLocationDataSource.vehicleSpeedState.value },
        )
        GoogleCloudTtsVoiceAnnouncementDispatcher(
            synthesizer = CachedGoogleCloudTtsSynthesizer(
                backend = GoogleCloudTtsApi(
                    httpClient = get(named("googleCloudTts")),
                    apiKey = appConfig.googleCloudTtsApiKey,
                    packageName = context.packageName,
                    signatureSha1 = TtsSigningCertificate.resolveSha1(context),
                ),
                cache = TtsAudioFileCache(
                    directory = context.cacheDir.resolve(GoogleCloudTtsSynthesisConfig.CACHE_SCHEMA_VERSION),
                ),
                synthesisConfigProvider = {
                    GoogleCloudTtsSynthesisConfig(volumeGainDb = appSettingRepository.setting.value.ttsVolumeGainDb)
                },
                apiKey = appConfig.googleCloudTtsApiKey,
            ),
            audioPlayer = audioPlayer,
            chimePlayer = GuidanceChimePlayer(
                context = context,
                audioPlayer = audioPlayer,
            ),
            audioFocusManager = TtsAudioFocusManager(context),
            audioChannelResolver = NavigationAudioChannelResolver(appSettingRepository = get()),
            speedAdaptiveGainProvider = speedAdaptiveGainProvider,
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
            config = get(),
        )
    }
    single {
        VoiceAnnouncementPrefetcher(
            contentRenderer = get(),
            dispatcher = get(),
        )
    }
    single {
        val appSettingRepository = get<AppSettingRepository>()
        VoiceAnnouncementSpeechRunner(
            scheduler = get(),
            dispatcher = get(),
            openingAnnouncementProvider = DefaultOpeningAnnouncementProvider(),
            milestoneAnnouncementProvider = DefaultMilestoneAnnouncementProvider(),
            // 同期参照しかできない category gate / TTS 合成設定が未読込の DEFAULT を観測しないよう、
            // 発話処理を設定の初回読了後に開始する。
            awaitSettingsReady = { appSettingRepository.awaitInitialLoad() },
        )
    }
    single { VoiceTickFactory() }
    single {
        val appSettingRepository = get<AppSettingRepository>()
        VoiceAnnouncementController(
            planBuilder = get(),
            tickFactory = get(),
            speechRunner = get(),
            prefetcher = get(),
            config = get(),
            isDebugSnapshotEnabled = {
                appSettingRepository.setting.value.isDeveloperFeatureEnabled(
                    me.matsumo.onenavi.core.model.DeveloperFeature.TTS_SCHEDULE_DEBUG_CARD,
                )
            },
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
    single<HttpClient>(qualifier = named("serverRoute")) {
        HttpClient(OkHttp) {
            install(HttpTimeout) {
                connectTimeoutMillis = 5_000
                requestTimeoutMillis = 15_000
                socketTimeoutMillis = 15_000
            }
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        isLenient = true
                        coerceInputValues = true
                        encodeDefaults = true
                    },
                )
            }
        }
    }
    single {
        val appConfig = get<AppConfig>()
        GuidanceApiConfig(
            baseUrl = appConfig.serverRouteBaseUrl,
            cloudflareAccessClientIdHeader = appConfig.serverRouteCfAccessClientIdHeader,
            cloudflareAccessClientSecretHeader = appConfig.serverRouteCfAccessClientSecretHeader,
        )
    }
    single {
        val appConfig = get<AppConfig>()
        GuidanceProviderConfig(
            stage = GuidanceMigrationStage.S1,
            forceExistingSource = appConfig.serverRouteForceExistingSource,
        )
    }
    single<GuidanceApiClient> {
        HttpGuidanceApiClient(
            httpClient = get(named("serverRoute")),
            config = get(),
        )
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
    single {
        ExtNavRoadTypeGateway(
            clientProvider = get(),
            authGateway = get(),
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
    single<RouteDataSource>(qualifier = named("existingRouteDataSource")) {
        ExtNavRouteDataSource(
            clientProvider = get(),
            authGateway = get(),
            registry = get(),
            roadTypeGateway = get(),
        )
    }
    single<RouteDataSource>(qualifier = named("serverRouteDataSource")) {
        ServerRouteDataSource(
            apiClient = get(),
            registry = get(),
        )
    }
    single<RouteDataSource> {
        val appSettingDataSource = get<AppSettingDataSource>()
        GuidanceRouteDataSourceSelector(
            existingSource = get(named("existingRouteDataSource")),
            serverSource = get(named("serverRouteDataSource")),
            providerConfig = get(),
            apiConfig = get(),
            serverRouteEnabledProvider = {
                appSettingDataSource.setting.value.isDeveloperFeatureEnabled(DeveloperFeature.USE_SERVER_ROUTE_SOURCE)
            },
        )
    }
}
