package me.matsumo.onenavi

import android.app.Application
import com.mapbox.bindgen.Value
import com.mapbox.common.MapboxCommonSettings
import com.mapbox.common.SettingsServiceFactory
import com.mapbox.common.SettingsServiceStorageType
import com.mapbox.navigation.base.options.NavigationOptions
import com.mapbox.navigation.core.lifecycle.MapboxNavigationApp
import io.github.aakira.napier.DebugAntilog
import io.github.aakira.napier.Napier
import me.matsumo.onenavi.di.applyModules
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.androix.startup.KoinStartup
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.dsl.koinConfiguration
import java.util.*

@OptIn(KoinExperimentalAPI::class)
class OneNaviApplication : Application(), KoinStartup {
    override fun onCreate() {
        super.onCreate()

        if (BuildConfig.DEBUG) {
            // StrictMode.enableDefaults()
            Napier.base(DebugAntilog())
        }

        setupMapboxLanguage()
        setupMapboxNavigation()
    }

    private fun setupMapboxLanguage() {
        val settingsService = SettingsServiceFactory.getInstance(
            SettingsServiceStorageType.PERSISTENT,
        )

        settingsService.set(
            MapboxCommonSettings.LANGUAGE,
            Value.valueOf(Locale.JAPAN.toLanguageTag()),
        )
        settingsService.set(
            MapboxCommonSettings.WORLDVIEW,
            Value.valueOf("JP"),
        )
    }

    private fun setupMapboxNavigation() {
        if (!MapboxNavigationApp.isSetup()) {
            MapboxNavigationApp.setup(
                NavigationOptions.Builder(this)
                    .isDebugLoggingEnabled(BuildConfig.DEBUG)
                    .build(),
            )
        }
    }

    override fun onKoinStartup() = koinConfiguration {
        androidContext(this@OneNaviApplication)
        androidLogger()
        applyModules()
    }
}
