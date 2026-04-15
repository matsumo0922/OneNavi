package me.matsumo.onenavi

import android.app.Application
import io.github.aakira.napier.DebugAntilog
import io.github.aakira.napier.Napier
import me.matsumo.onenavi.debug.DevTools
import me.matsumo.onenavi.di.applyModules
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.androix.startup.KoinStartup
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.dsl.koinConfiguration

@OptIn(KoinExperimentalAPI::class)
class OneNaviApplication : Application(), KoinStartup {
    override fun onCreate() {
        super.onCreate()

        if (BuildConfig.DEBUG) {
            // StrictMode.enableDefaults()
            Napier.base(DebugAntilog())
        }

        DevTools.initialize(this)
    }

    override fun onKoinStartup() = koinConfiguration {
        androidContext(this@OneNaviApplication)
        androidLogger()
        applyModules()
    }
}
