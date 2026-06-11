package me.matsumo.onenavi.di

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import me.matsumo.onenavi.BuildKonfig
import me.matsumo.onenavi.MainViewModel
import me.matsumo.onenavi.car.CarGuidanceSessionReleaser
import me.matsumo.onenavi.car.MainActivityPhoneDestinationSearchLauncher
import me.matsumo.onenavi.core.common.car.PhoneDestinationSearchLauncher
import me.matsumo.onenavi.core.model.AppConfig
import me.matsumo.onenavi.core.navigation.newguidance.NewGuidanceManager
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
            releaseGuidanceSession = newGuidanceManager::release,
            scope = get(),
        )
    }

    viewModelOf(::MainViewModel)
}
