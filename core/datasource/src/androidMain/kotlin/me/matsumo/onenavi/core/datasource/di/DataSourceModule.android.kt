package me.matsumo.onenavi.core.datasource.di

import me.matsumo.onenavi.core.datasource.MapboxSearchDataSource
import me.matsumo.onenavi.core.datasource.SearchDataSource
import me.matsumo.onenavi.core.datasource.helper.PreferenceHelper
import me.matsumo.onenavi.core.datasource.helper.PreferenceHelperImpl
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
        MapboxSearchDataSource()
    }
}
