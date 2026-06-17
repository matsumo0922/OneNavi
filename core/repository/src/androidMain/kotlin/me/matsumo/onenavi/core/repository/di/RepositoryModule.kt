package me.matsumo.onenavi.core.repository.di

import me.matsumo.onenavi.core.repository.AppSettingRepository
import me.matsumo.onenavi.core.repository.BillingRepository
import me.matsumo.onenavi.core.repository.RouteRepository
import me.matsumo.onenavi.core.repository.SavedPlaceRepository
import me.matsumo.onenavi.core.repository.SearchRepository
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val repositoryModule = module {
    singleOf(::AppSettingRepository)
    singleOf(::BillingRepository)
    singleOf(::RouteRepository)
    single {
        SavedPlaceRepository(
            dataSource = get(),
        )
    }
    singleOf(::SearchRepository)
}
