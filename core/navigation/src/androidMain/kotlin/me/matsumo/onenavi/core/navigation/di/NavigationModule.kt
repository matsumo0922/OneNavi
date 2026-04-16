package me.matsumo.onenavi.core.navigation.di

import me.matsumo.onenavi.core.navigation.CameraManager
import me.matsumo.onenavi.core.navigation.GuidanceSessionManager
import me.matsumo.onenavi.core.navigation.NavigationSdkManager
import me.matsumo.onenavi.core.navigation.RouteManager
import org.koin.android.ext.koin.androidApplication
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

actual val navigationModule: Module = module {
    single { RouteManager() }
    single { NavigationSdkManager(androidApplication(), get()) }
    single { CameraManager(androidContext(), get()) }
    single { GuidanceSessionManager(androidContext(), get(), get(), get()) }
}
