package me.matsumo.onenavi.core.datasource.di

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import me.matsumo.onenavi.core.datasource.AppSettingDataSource
import me.matsumo.onenavi.core.datasource.GooglePlacesSearchDataSource
import me.matsumo.onenavi.core.datasource.SearchDataSource
import me.matsumo.onenavi.core.datasource.SearchHistoryDataSource
import me.matsumo.onenavi.core.datasource.helper.PreferenceHelper
import me.matsumo.onenavi.core.datasource.helper.PreferenceHelperImpl
import me.matsumo.onenavi.core.datasource.location.CurrentLocationDataSource
import me.matsumo.onenavi.core.model.AppConfig
import org.koin.core.module.dsl.createdAtStart
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

// NOTE: [RouteDataSource] の Android バインディングは core/navigation 側 (NavigationModule) で
// 外部ナビ API ライブラリベースの実装 (ExtNavRouteDataSource) を登録する。
val dataSourceModule = module {
    // UI を経由しないプロセス起動でも DataStore の読み込みが Koin 起動時に始まるよう即時生成する。
    singleOf(::AppSettingDataSource) { createdAtStart() }
    singleOf(::SearchHistoryDataSource)
    singleOf(::CurrentLocationDataSource)

    single {
        HttpClient {
            install(HttpTimeout) {
                connectTimeoutMillis = 15_000
                requestTimeoutMillis = 30_000
                socketTimeoutMillis = 30_000
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
}
