package me.matsumo.onenavi.di

import me.matsumo.onenavi.core.billing.di.billingModule
import me.matsumo.onenavi.core.common.di.commonModule
import me.matsumo.onenavi.core.datasource.di.dataSourceModule
import me.matsumo.onenavi.core.navigation.di.navigationModule
import me.matsumo.onenavi.core.repository.di.repositoryModule
import me.matsumo.onenavi.feature.billing.di.billingFeatureModule
import me.matsumo.onenavi.feature.home.di.homeModule
import me.matsumo.onenavi.feature.map.di.mapModule
import me.matsumo.onenavi.feature.setting.di.settingModule
import org.koin.core.KoinApplication

fun KoinApplication.applyModules() {
    modules(appModule)

    modules(commonModule)
    modules(billingModule)
    modules(dataSourceModule)
    modules(navigationModule)
    modules(repositoryModule)

    modules(homeModule)
    modules(mapModule)
    modules(settingModule)
    modules(billingFeatureModule)
}
