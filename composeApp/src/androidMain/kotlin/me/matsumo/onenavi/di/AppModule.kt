package me.matsumo.onenavi.di

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import me.matsumo.onenavi.BuildKonfig
import me.matsumo.onenavi.MainViewModel
import me.matsumo.onenavi.car.CarGuidanceSessionReleaser
import me.matsumo.onenavi.car.MainActivityPhoneDestinationSearchLauncher
import me.matsumo.onenavi.car.hardware.CarHardwareDataSource
import me.matsumo.onenavi.car.navigation.CarNavigationSessionPublisher
import me.matsumo.onenavi.car.navigation.GuidanceCarTripMapper
import me.matsumo.onenavi.core.common.car.PhoneDestinationSearchLauncher
import me.matsumo.onenavi.core.model.AppConfig
import me.matsumo.onenavi.core.navigation.newguidance.NewGuidanceManager
import me.matsumo.onenavi.guidance.GuidanceForegroundController
import me.matsumo.onenavi.guidance.GuidanceForegroundService
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val appModule = module {
    single<CoroutineDispatcher> {
        Dispatchers.IO.limitedParallelism(24)
    }

    single<CoroutineScope> {
        CoroutineScope(SupervisorJob() + get<CoroutineDispatcher>())
    }

    single {
        AppConfig(
            versionName = BuildKonfig.VERSION_NAME,
            versionCode = BuildKonfig.VERSION_CODE.toInt(),
            developerPin = BuildKonfig.DEVELOPER_PIN,
            googleApiKey = BuildKonfig.GOOGLE_API_KEY,
            googleCloudTtsApiKey = BuildKonfig.GOOGLE_CLOUD_TTS_API_KEY,
            serverRouteBaseUrl = BuildKonfig.SERVER_ROUTE_BASE_URL,
            serverRouteForceExistingSource = BuildKonfig.SERVER_ROUTE_FORCE_EXISTING_SOURCE.toBooleanStrictOrNull() ?: true,
            serverRouteCfAccessClientIdHeader = BuildKonfig.SERVER_ROUTE_CF_ACCESS_CLIENT_ID_HEADER,
            serverRouteCfAccessClientSecretHeader = BuildKonfig.SERVER_ROUTE_CF_ACCESS_CLIENT_SECRET_HEADER,
            adMobAppId = BuildKonfig.ADMOB_ANDROID_APP_ID,
            adMobBannerAdUnitId = BuildKonfig.ADMOB_ANDROID_BANNER_AD_UNIT_ID,
            adMobInterstitialAdUnitId = BuildKonfig.ADMOB_ANDROID_INTERSTITIAL_AD_UNIT_ID,
            adMobRewardedAdUnitId = BuildKonfig.ADMOB_ANDROID_REWARDED_AD_UNIT_ID,
            purchaseAndroidApiKey = BuildKonfig.PURCHASE_ANDROID_API_KEY.takeIf { it.isNotBlank() },
            purchaseIosApiKey = null,
            extNavLoginId = BuildKonfig.EXT_NAV_LOGIN_ID,
            extNavPassword = BuildKonfig.EXT_NAV_PASSWORD,
        )
    }

    single<PhoneDestinationSearchLauncher> {
        MainActivityPhoneDestinationSearchLauncher(
            context = androidContext(),
            carPhoneSessionCoordinator = get(),
        )
    }

    single {
        val newGuidanceManager = get<NewGuidanceManager>()
        CarGuidanceSessionReleaser(
            carPhoneSessionCoordinator = get(),
            guidanceState = newGuidanceManager.state,
            releaseGuidanceSession = newGuidanceManager::release,
            scope = get(),
        )
    }

    single {
        val applicationContext = androidContext()
        val newGuidanceManager = get<NewGuidanceManager>()
        GuidanceForegroundController(
            guidanceState = newGuidanceManager.state,
            startService = { GuidanceForegroundService.start(applicationContext) },
            stopService = { GuidanceForegroundService.stop(applicationContext) },
            scope = get(),
        )
    }

    single {
        GuidanceCarTripMapper()
    }

    single {
        CarHardwareDataSource()
    }

    single {
        val newGuidanceManager = get<NewGuidanceManager>()
        CarNavigationSessionPublisher(
            guidanceState = newGuidanceManager.state,
            stopGuidance = newGuidanceManager::stopGuidance,
            scope = get(),
            tripMapper = get(),
        )
    }

    viewModelOf(::MainViewModel)
}
