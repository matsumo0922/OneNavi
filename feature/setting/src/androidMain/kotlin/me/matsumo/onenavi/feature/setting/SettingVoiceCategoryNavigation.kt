package me.matsumo.onenavi.feature.setting

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import me.matsumo.onenavi.core.ui.screen.Destination

fun EntryProviderScope<NavKey>.settingVoiceCategoryEntry() {
    entry<Destination.Setting.VoiceCategory> {
        SettingVoiceCategoryScreen(
            modifier = Modifier.fillMaxSize(),
        )
    }
}
