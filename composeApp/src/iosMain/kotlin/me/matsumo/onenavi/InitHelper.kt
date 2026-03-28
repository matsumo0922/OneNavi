package me.matsumo.onenavi

import io.github.aakira.napier.DebugAntilog
import io.github.aakira.napier.Napier
import me.matsumo.onenavi.di.applyModules
import org.koin.core.context.startKoin

fun initKoin() {
    startKoin {
        applyModules()
    }
}

fun initNapier() {
    Napier.base(DebugAntilog())
}
