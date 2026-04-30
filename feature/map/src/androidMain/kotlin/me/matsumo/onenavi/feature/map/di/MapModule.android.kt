package me.matsumo.onenavi.feature.map.di

import me.matsumo.onenavi.feature.map.MapViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

actual val mapModule: Module = module {
    viewModelOf(::MapViewModel)
}