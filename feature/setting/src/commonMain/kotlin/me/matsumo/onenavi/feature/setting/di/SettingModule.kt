package me.matsumo.onenavi.feature.setting.di

import me.matsumo.onenavi.feature.setting.SettingViewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val settingModule = module {
    viewModelOf(::SettingViewModel)
}
