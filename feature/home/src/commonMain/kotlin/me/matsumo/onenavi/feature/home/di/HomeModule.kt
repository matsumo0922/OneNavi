package me.matsumo.onenavi.feature.home.di

import me.matsumo.onenavi.feature.home.HomeViewModel
import me.matsumo.onenavi.feature.home.map.HomeMapViewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val homeModule = module {
    viewModelOf(::HomeViewModel)
    viewModelOf(::HomeMapViewModel)
}
