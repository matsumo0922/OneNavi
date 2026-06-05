package me.matsumo.onenavi.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import me.matsumo.onenavi.core.model.AppConfig
import me.matsumo.onenavi.core.model.AppSetting
import me.matsumo.onenavi.core.model.Theme
import me.matsumo.onenavi.core.ui.utils.rememberColorScheme
import org.koin.compose.koinInject

@Suppress("ModifierMissing")
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun OneNaviTheme(
    appSetting: AppSetting = AppSetting.DEFAULT,
    appConfig: AppConfig = koinInject(),
    drawBackground: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = rememberColorScheme(
        useDynamicColor = appSetting.useDynamicColor,
        seedColor = appSetting.seedColor,
        isDark = shouldUseDarkTheme(appSetting.theme),
    )

    CompositionLocalProvider(
        LocalAppSetting provides appSetting,
        LocalAppConfig provides appConfig,
    ) {
        MaterialExpressiveTheme(
            colorScheme = colorScheme,
        ) {
            if (drawBackground) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    content = content,
                )
            } else {
                content()
            }
        }
    }
}

@Composable
fun shouldUseDarkTheme(theme: Theme): Boolean {
    return when (theme) {
        Theme.System -> isSystemInDarkTheme()
        Theme.Light -> false
        Theme.Dark -> true
    }
}
