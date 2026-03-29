package me.matsumo.onenavi.core.datasource.di

import me.matsumo.onenavi.core.datasource.AppSettingDataSource
import me.matsumo.onenavi.core.datasource.SearchHistoryDataSource
import org.koin.core.module.Module
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val dataSourceModule = module {
    singleOf(::AppSettingDataSource)
    singleOf(::SearchHistoryDataSource)
    includes(dataSourcePlatformModule)
}

internal expect val dataSourcePlatformModule: Module
