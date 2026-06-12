package me.matsumo.onenavi.feature.setting

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.matsumo.onenavi.core.model.DeveloperFeature
import me.matsumo.onenavi.core.resource.Res
import me.matsumo.onenavi.core.resource.setting_developer_options_car_vd_debug_overlay
import me.matsumo.onenavi.core.resource.setting_developer_options_car_vd_debug_overlay_description
import me.matsumo.onenavi.core.resource.setting_developer_options_fake_gps
import me.matsumo.onenavi.core.resource.setting_developer_options_fake_gps_description
import me.matsumo.onenavi.core.resource.setting_developer_options_force_plus_privilege
import me.matsumo.onenavi.core.resource.setting_developer_options_force_plus_privilege_description
import me.matsumo.onenavi.core.resource.setting_developer_options_map_diagnostics
import me.matsumo.onenavi.core.resource.setting_developer_options_map_diagnostics_description
import me.matsumo.onenavi.core.resource.setting_developer_options_master
import me.matsumo.onenavi.core.resource.setting_developer_options_master_description
import me.matsumo.onenavi.core.resource.setting_developer_options_section_access
import me.matsumo.onenavi.core.resource.setting_developer_options_section_android_auto
import me.matsumo.onenavi.core.resource.setting_developer_options_section_location
import me.matsumo.onenavi.core.resource.setting_developer_options_section_map
import me.matsumo.onenavi.core.resource.setting_developer_options_show_developer_badge
import me.matsumo.onenavi.core.resource.setting_developer_options_show_developer_badge_description
import me.matsumo.onenavi.core.resource.setting_developer_options_show_paywall_section
import me.matsumo.onenavi.core.resource.setting_developer_options_show_paywall_section_description
import me.matsumo.onenavi.core.resource.setting_developer_options_title
import me.matsumo.onenavi.core.ui.theme.LocalNavBackStack
import me.matsumo.onenavi.feature.setting.components.SettingSwitchItem
import me.matsumo.onenavi.feature.setting.components.SettingTitleItem
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingDeveloperOptionsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingViewModel = koinViewModel(),
) {
    val navBackStack = LocalNavBackStack.current
    val setting by viewModel.setting.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(stringResource(Res.string.setting_developer_options_title))
                },
                navigationIcon = {
                    IconButton(
                        onClick = { navBackStack.removeAt(navBackStack.size - 1) },
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Default.ArrowBack,
                            contentDescription = null,
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = paddingValues,
        ) {
            item {
                SettingSwitchItem(
                    modifier = Modifier.fillMaxWidth(),
                    title = Res.string.setting_developer_options_master,
                    description = Res.string.setting_developer_options_master_description,
                    value = setting.developerMode,
                    onValueChanged = { isEnabled ->
                        viewModel.setDeveloperMode(isEnabled)
                    },
                )
            }

            for (sectionTitle in developerFeatureSections) {
                val features = DeveloperFeature.entries.filter { developerFeature -> developerFeature.sectionTitle == sectionTitle }

                item {
                    SettingTitleItem(
                        modifier = Modifier.fillMaxWidth(),
                        text = sectionTitle,
                    )
                }

                items(
                    items = features,
                    key = { developerFeature -> developerFeature.name },
                ) { developerFeature ->
                    SettingSwitchItem(
                        modifier = Modifier.fillMaxWidth(),
                        title = developerFeature.title,
                        description = developerFeature.description,
                        value = setting.isDeveloperFeatureEnabled(developerFeature),
                        onValueChanged = { isEnabled ->
                            viewModel.setDeveloperFeatureEnabled(developerFeature, isEnabled)
                        },
                        isEnabled = setting.developerMode,
                    )
                }
            }
        }
    }
}

/** 開発者向けオプション画面に表示する機能グループ。 */
private val developerFeatureSections = listOf(
    Res.string.setting_developer_options_section_access,
    Res.string.setting_developer_options_section_location,
    Res.string.setting_developer_options_section_map,
    Res.string.setting_developer_options_section_android_auto,
)

private val DeveloperFeature.sectionTitle: StringResource
    get() = when (this) {
        DeveloperFeature.FORCE_PLUS_PRIVILEGE -> Res.string.setting_developer_options_section_access
        DeveloperFeature.SHOW_PAYWALL_SECTION -> Res.string.setting_developer_options_section_access
        DeveloperFeature.SHOW_DEVELOPER_BADGE -> Res.string.setting_developer_options_section_access
        DeveloperFeature.FAKE_GPS -> Res.string.setting_developer_options_section_location
        DeveloperFeature.MAP_DIAGNOSTICS -> Res.string.setting_developer_options_section_map
        DeveloperFeature.CAR_VD_DEBUG_OVERLAY -> Res.string.setting_developer_options_section_android_auto
    }

private val DeveloperFeature.title: StringResource
    get() = when (this) {
        DeveloperFeature.FORCE_PLUS_PRIVILEGE -> Res.string.setting_developer_options_force_plus_privilege
        DeveloperFeature.SHOW_PAYWALL_SECTION -> Res.string.setting_developer_options_show_paywall_section
        DeveloperFeature.SHOW_DEVELOPER_BADGE -> Res.string.setting_developer_options_show_developer_badge
        DeveloperFeature.FAKE_GPS -> Res.string.setting_developer_options_fake_gps
        DeveloperFeature.MAP_DIAGNOSTICS -> Res.string.setting_developer_options_map_diagnostics
        DeveloperFeature.CAR_VD_DEBUG_OVERLAY -> Res.string.setting_developer_options_car_vd_debug_overlay
    }

private val DeveloperFeature.description: StringResource
    get() = when (this) {
        DeveloperFeature.FORCE_PLUS_PRIVILEGE -> Res.string.setting_developer_options_force_plus_privilege_description
        DeveloperFeature.SHOW_PAYWALL_SECTION -> Res.string.setting_developer_options_show_paywall_section_description
        DeveloperFeature.SHOW_DEVELOPER_BADGE -> Res.string.setting_developer_options_show_developer_badge_description
        DeveloperFeature.FAKE_GPS -> Res.string.setting_developer_options_fake_gps_description
        DeveloperFeature.MAP_DIAGNOSTICS -> Res.string.setting_developer_options_map_diagnostics_description
        DeveloperFeature.CAR_VD_DEBUG_OVERLAY -> Res.string.setting_developer_options_car_vd_debug_overlay_description
    }
