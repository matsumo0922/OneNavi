package me.matsumo.onenavi.feature.setting.components.section

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.delay
import me.matsumo.onenavi.core.model.AppSetting
import me.matsumo.onenavi.core.model.Theme
import me.matsumo.onenavi.core.resource.Res
import me.matsumo.onenavi.core.resource.setting_dynamic_color
import me.matsumo.onenavi.core.resource.setting_dynamic_color_description
import me.matsumo.onenavi.core.resource.setting_dynamic_color_system
import me.matsumo.onenavi.core.resource.setting_dynamic_color_user
import me.matsumo.onenavi.core.resource.setting_theme
import me.matsumo.onenavi.core.resource.setting_theme_app
import me.matsumo.onenavi.core.resource.setting_theme_app_auto
import me.matsumo.onenavi.core.resource.setting_theme_app_dark
import me.matsumo.onenavi.core.resource.setting_theme_app_description
import me.matsumo.onenavi.core.resource.setting_theme_app_light
import me.matsumo.onenavi.core.ui.screen.view.ColorSlider
import me.matsumo.onenavi.core.ui.screen.view.SegmentedTabRow
import me.matsumo.onenavi.core.ui.utils.isSupportDynamicColor
import me.matsumo.onenavi.feature.setting.components.SettingTextItem
import me.matsumo.onenavi.feature.setting.components.SettingTitleItem
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun SettingThemeSection(
    setting: AppSetting,
    onThemeChanged: (Theme) -> Unit,
    onUseDynamicColorChanged: (Boolean) -> Unit,
    onSeedColorChanged: (Color) -> Unit,
    modifier: Modifier = Modifier,
) {
    val themes = Theme.entries
    var currentThemeIndex by remember(setting) { mutableStateOf(themes.indexOf(setting.theme)) }

    var currentDynamicColorProviderIndex by remember {
        mutableStateOf(if (setting.useDynamicColor && isSupportDynamicColor) 0 else 1)
    }
    val dynamicColorProviders = listOf(
        stringResource(Res.string.setting_dynamic_color_system),
        stringResource(Res.string.setting_dynamic_color_user),
    )

    LaunchedEffect(currentDynamicColorProviderIndex) {
        if (!isSupportDynamicColor) {
            delay(100)
            currentDynamicColorProviderIndex = 1
        }
    }

    Column(modifier) {
        SettingTitleItem(
            modifier = Modifier.fillMaxWidth(),
            text = Res.string.setting_theme,
        )

        SettingTextItem(
            modifier = Modifier.fillMaxWidth(),
            title = Res.string.setting_theme_app,
            description = Res.string.setting_theme_app_description,
            onClick = null,
        )

        SegmentedTabRow(
            modifier = Modifier
                .padding(16.dp, 8.dp)
                .fillMaxWidth(),
            items = themes.toImmutableList(),
            selectedIndex = currentThemeIndex,
            onSelect = { onThemeChanged.invoke(themes[it]) },
            itemContent = @Composable { item, _ ->
                Text(
                    text = when (item) {
                        Theme.System -> stringResource(Res.string.setting_theme_app_auto)
                        Theme.Light -> stringResource(Res.string.setting_theme_app_light)
                        Theme.Dark -> stringResource(Res.string.setting_theme_app_dark)
                    },
                    style = MaterialTheme.typography.labelLarge,
                )
            },
        )

        SettingTextItem(
            modifier = Modifier.fillMaxWidth(),
            title = Res.string.setting_dynamic_color,
            description = Res.string.setting_dynamic_color_description,
            onClick = null,
        )

        SegmentedTabRow(
            modifier = Modifier
                .padding(16.dp, 8.dp)
                .fillMaxWidth(),
            items = dynamicColorProviders.toImmutableList(),
            selectedIndex = currentDynamicColorProviderIndex,
            onSelect = {
                currentDynamicColorProviderIndex = it
                onUseDynamicColorChanged.invoke(it == 0)
            },
        )

        AnimatedVisibility(
            modifier = Modifier.fillMaxWidth(),
            visible = currentDynamicColorProviderIndex == 1,
        ) {
            ColorSlider(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .padding(16.dp, 8.dp)
                    .fillMaxWidth(),
                color = setting.seedColor,
                onColorChanged = onSeedColorChanged,
            )
        }
    }
}
