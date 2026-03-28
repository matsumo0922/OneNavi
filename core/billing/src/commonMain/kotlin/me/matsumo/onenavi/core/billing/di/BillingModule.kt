package me.matsumo.onenavi.core.billing.di

import me.matsumo.onenavi.core.billing.BillingDataSource
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val billingModule = module {
    singleOf(::BillingDataSource)
}
