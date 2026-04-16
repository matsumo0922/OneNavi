package me.matsumo.onenavi.core.datasource.di

import io.ktor.client.HttpClient
import me.matsumo.onenavi.core.datasource.GooglePlacesSearchDataSource
import me.matsumo.onenavi.core.datasource.GoogleRoutesDataSource
import me.matsumo.onenavi.core.datasource.RouteDataSource
import me.matsumo.onenavi.core.datasource.SearchDataSource
import me.matsumo.onenavi.core.datasource.helper.PreferenceHelper
import me.matsumo.onenavi.core.datasource.helper.PreferenceHelperImpl
import me.matsumo.onenavi.core.model.AppConfig
import org.koin.core.module.Module
import org.koin.dsl.module

internal actual val dataSourcePlatformModule: Module = module {
    single<PreferenceHelper> {
        PreferenceHelperImpl(
            context = get(),
            ioDispatcher = get(),
        )
    }

    single<SearchDataSource> {
        val appConfig: AppConfig = get()
        GooglePlacesSearchDataSource(
            context = get(),
            googleApiKey = appConfig.googleApiKey,
        )
    }

    single<RouteDataSource> {
        val appConfig: AppConfig = get()
        GoogleRoutesDataSource(
            context = get(),
            httpClient = get<HttpClient>(),
            googleApiKey = appConfig.googleApiKey,
        )
    }
}
