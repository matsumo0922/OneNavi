package me.matsumo.onenavi.feature.home.di

import me.matsumo.onenavi.feature.home.HomeViewModel
import me.matsumo.onenavi.feature.home.map.HomeMapViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val homeMapModule: Module = module {
    viewModelOf(::HomeMapViewModel)
}

val homeModule = module {
    includes(homeMapModule)
    viewModelOf(::HomeViewModel)
}
