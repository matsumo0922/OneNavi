package me.matsumo.onenavi.core.repository.di

import me.matsumo.onenavi.core.repository.AppSettingRepository
import me.matsumo.onenavi.core.repository.BillingRepository
import org.koin.core.module.dsl.singleOf
import org.koin.core.qualifier.named
import org.koin.dsl.module

val repositoryModule = module {
    singleOf(::AppSettingRepository)
    singleOf(::BillingRepository)
}
