package me.matsumo.onenavi.feature.home.di

import me.matsumo.onenavi.feature.home.map.HomeMapNavigationManager
import me.matsumo.onenavi.feature.home.map.HomeMapViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

actual val homeMapModule: Module = module {
    single { HomeMapNavigationManager() }
    viewModelOf(::HomeMapViewModel)
}
