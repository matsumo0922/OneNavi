package me.matsumo.onenavi.core.datasource.di

import me.matsumo.onenavi.core.datasource.GooglePlacesSearchDataSource
import me.matsumo.onenavi.core.datasource.SearchDataSource
import me.matsumo.onenavi.core.datasource.helper.PreferenceHelper
import me.matsumo.onenavi.core.datasource.helper.PreferenceHelperImpl
import me.matsumo.onenavi.core.model.AppConfig
import org.koin.core.module.Module
import org.koin.dsl.module

// NOTE: [RouteDataSource] の Android バインディングは core/navigation 側 (NavigationModule) で
// drive-supporter-api ベースの実装 (ExtNavRouteDataSource) を登録する。
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
}
