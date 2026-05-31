package me.matsumo.onenavi.core.navigation.di

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import me.matsumo.drive.supporter.api.core.model.LogLevel
import me.matsumo.onenavi.core.datasource.RouteDataSource
import me.matsumo.onenavi.core.navigation.NavigationSdkManager
import me.matsumo.onenavi.core.navigation.RouteManager
import me.matsumo.onenavi.core.navigation.extnav.ExtNavAuthGateway
import me.matsumo.onenavi.core.navigation.extnav.ExtNavClientProvider
import me.matsumo.onenavi.core.navigation.extnav.ExtNavGuidanceTracker
import me.matsumo.onenavi.core.navigation.extnav.ExtNavRerouteDetector
import me.matsumo.onenavi.core.navigation.extnav.ExtNavRouteDataSource
import me.matsumo.onenavi.core.navigation.extnav.ExtNavRouteRegistry
import me.matsumo.onenavi.core.navigation.newguidance.NewGuidanceManager
import me.matsumo.onenavi.core.navigation.newguidance.NewRouteManager
import me.matsumo.onenavi.core.navigation.voice.config.VoiceAnnouncementConfig
import me.matsumo.onenavi.core.navigation.voice.dispatch.LoggingVoiceAnnouncementDispatcher
import me.matsumo.onenavi.core.navigation.voice.dispatch.VoiceAnnouncementContentRenderer
import me.matsumo.onenavi.core.navigation.voice.dispatch.VoiceAnnouncementDispatcher
import me.matsumo.onenavi.core.navigation.voice.plan.VoiceAnnouncementPlanBuilder
import me.matsumo.onenavi.core.navigation.voice.scheduler.VoiceAnnouncementController
import me.matsumo.onenavi.core.navigation.voice.scheduler.VoiceAnnouncementScheduler
import me.matsumo.onenavi.core.navigation.voice.scheduler.VoiceAnnouncementSpeechRunner
import me.matsumo.onenavi.core.navigation.voice.scheduler.VoiceTickFactory
import me.matsumo.onenavi.core.navigation.voice.selector.VoiceAnnouncementSelector
import me.matsumo.onenavi.core.navigation.voice.suppression.VoiceAnnouncementSelectionPolicy
import org.koin.android.ext.koin.androidApplication
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

val navigationModule: Module = module {
    single { RouteManager() }
    single { NavigationSdkManager(androidApplication(), get()) }
    single { NewRouteManager(routeRepository = get()) }
    single { ExtNavGuidanceTracker() }
    single { ExtNavRerouteDetector() }
    single { VoiceAnnouncementConfig() }
    single { VoiceAnnouncementPlanBuilder() }
    single { VoiceAnnouncementSelector(config = get()) }
    single { VoiceAnnouncementSelectionPolicy() }
    single<VoiceAnnouncementDispatcher> { LoggingVoiceAnnouncementDispatcher() }
    single {
        VoiceAnnouncementContentRenderer(
            categoryGate = get<VoiceAnnouncementConfig>().categoryGates,
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
        VoiceAnnouncementSpeechRunner(
            scheduler = get(),
            dispatcher = get(),
        )
    }
    single { VoiceTickFactory() }
    single {
        VoiceAnnouncementController(
            planBuilder = get(),
            tickFactory = get(),
            speechRunner = get(),
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
    single<RouteDataSource> {
        ExtNavRouteDataSource(
            clientProvider = get(),
            authGateway = get(),
            registry = get(),
        )
    }
}
