package me.matsumo.onenavi.core.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import me.matsumo.onenavi.core.model.AppConfig
import me.matsumo.onenavi.core.model.AppSetting

val LocalAppSetting = staticCompositionLocalOf {
    AppSetting.DEFAULT
}

val LocalAppConfig = staticCompositionLocalOf<AppConfig> {
    error("No AppConfig provided")
}
