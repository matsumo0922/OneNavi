package me.matsumo.onenavi.core.datasource.di

import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import me.matsumo.onenavi.core.datasource.AppSettingDataSource
import me.matsumo.onenavi.core.datasource.MapboxRouteDataSource
import me.matsumo.onenavi.core.datasource.RouteDataSource
import me.matsumo.onenavi.core.datasource.SearchHistoryDataSource
import me.matsumo.onenavi.core.model.AppConfig
import org.koin.core.module.Module
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val dataSourceModule = module {
    singleOf(::AppSettingDataSource)
    singleOf(::SearchHistoryDataSource)

    single {
        HttpClient {
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

    single<RouteDataSource> {
        MapboxRouteDataSource(
            httpClient = get(),
            accessToken = get<AppConfig>().mapBoxToken,
        )
    }

    includes(dataSourcePlatformModule)
}

internal expect val dataSourcePlatformModule: Module
