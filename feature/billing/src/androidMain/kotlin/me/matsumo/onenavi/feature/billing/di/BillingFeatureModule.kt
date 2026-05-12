package me.matsumo.onenavi.feature.billing.di

import me.matsumo.onenavi.feature.billing.PaywallViewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val billingFeatureModule = module {
    viewModelOf(::PaywallViewModel)
}
