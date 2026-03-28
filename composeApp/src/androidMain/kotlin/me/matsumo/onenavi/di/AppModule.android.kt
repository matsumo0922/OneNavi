package me.matsumo.onenavi.di

import me.matsumo.onenavi.MainViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

internal actual val appModulePlatform: Module = module {
    viewModelOf(::MainViewModel)
}
