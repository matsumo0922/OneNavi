package me.matsumo.onenavi.feature.map.di

import me.matsumo.onenavi.feature.map.MapViewModel
import me.matsumo.onenavi.feature.map.location.VehicleLocationDataSource
import org.koin.core.module.Module
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val mapModule: Module = module {
    singleOf(::VehicleLocationDataSource)
    viewModelOf(::MapViewModel)
}
