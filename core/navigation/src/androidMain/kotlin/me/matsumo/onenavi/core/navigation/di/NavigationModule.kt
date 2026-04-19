package me.matsumo.onenavi.core.navigation.di

import me.matsumo.onenavi.core.navigation.CameraManager
import me.matsumo.onenavi.core.navigation.GuidanceSessionManager
import me.matsumo.onenavi.core.navigation.NavigationSdkManager
import me.matsumo.onenavi.core.navigation.RouteManager
import me.matsumo.onenavi.core.navigation.tts.AndroidTtsEngine
import me.matsumo.onenavi.core.navigation.tts.AudioFocusManager
import me.matsumo.onenavi.core.navigation.tts.TtsEngine
import org.koin.android.ext.koin.androidApplication
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

actual val navigationModule: Module = module {
    single { RouteManager() }
    single { NavigationSdkManager(androidApplication(), get()) }
    single { CameraManager(get()) }
    single {
        val context = androidContext()
        GuidanceSessionManager(
            cameraManager = get(),
            routeManager = get(),
            navigationSdkManager = get(),
            ttsEngineFactory = {
                AndroidTtsEngine(
                    context = context,
                    audioFocusManager = AudioFocusManager(context),
                ) as TtsEngine
            },
        )
    }
}
