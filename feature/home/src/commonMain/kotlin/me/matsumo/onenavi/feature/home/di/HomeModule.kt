package me.matsumo.onenavi.feature.home.di

import me.matsumo.onenavi.feature.home.HomeViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

expect val homeMapModule: Module

val homeModule = module {
    includes(homeMapModule)
    viewModelOf(::HomeViewModel)
}
