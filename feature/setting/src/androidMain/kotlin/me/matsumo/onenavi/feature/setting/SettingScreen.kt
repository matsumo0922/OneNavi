package me.matsumo.onenavi.feature.setting

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.matsumo.onenavi.core.ui.screen.Destination
import me.matsumo.onenavi.core.ui.theme.LocalNavBackStack
import me.matsumo.onenavi.feature.setting.components.SettingTopAppBar
import me.matsumo.onenavi.feature.setting.components.section.SettingInfoSection
import me.matsumo.onenavi.feature.setting.components.section.SettingOthersSection
import me.matsumo.onenavi.feature.setting.components.section.SettingPaywallSection
import me.matsumo.onenavi.feature.setting.components.section.SettingSoundSection
import me.matsumo.onenavi.feature.setting.components.section.SettingThemeSection
import org.koin.compose.viewmodel.koinViewModel

@Composable
internal fun SettingScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingViewModel = koinViewModel(),
) {
    val navBackStack = LocalNavBackStack.current
    val uriHandler = LocalUriHandler.current
    val setting by viewModel.setting.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        topBar = {
            SettingTopAppBar(
                onBackClicked = { navBackStack.removeAt(navBackStack.size - 1) },
                modifier = Modifier,
            )
        },
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = it,
        ) {
            if (!setting.plusMode || setting.developerMode) {
                item {
                    SettingPaywallSection(
                        modifier = Modifier.fillMaxWidth(),
                        onUpgradeClicked = { navBackStack.add(Destination.Paywall("setting")) },
                    )
                }
            }

            item {
                SettingThemeSection(
                    modifier = Modifier.fillMaxWidth(),
                    setting = setting,
                    onThemeChanged = viewModel::setTheme,
                    onUseDynamicColorChanged = viewModel::setUseDynamicColor,
                    onSeedColorChanged = viewModel::setSeedColor,
                )
            }

            item {
                SettingSoundSection(
                    modifier = Modifier.fillMaxWidth(),
                    setting = setting,
                    onUseMediaAudioChannelOnCarChanged = viewModel::setUseMediaAudioChannelOnCar,
                    onVoiceCategoryClicked = { navBackStack.add(Destination.Setting.VoiceCategory) },
                )
            }

            item {
                SettingInfoSection(
                    modifier = Modifier.fillMaxWidth(),
                    setting = setting,
                )
            }

            item {
                SettingOthersSection(
                    modifier = Modifier.fillMaxWidth(),
                    setting = setting,
                    onTeamsOfServiceClicked = {
                        uriHandler.openUri("https://www.matsumo.me/application/all/team_of_service")
                    },
                    onPrivacyPolicyClicked = {
                        uriHandler.openUri("https://www.matsumo.me/application/all/privacy_policy")
                    },
                    onOpenSourceLicenseClicked = {
                        navBackStack.add(Destination.Setting.License)
                    },
                    onDeveloperModeChanged = viewModel::setDeveloperMode,
                )
            }
        }
    }
}
