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
import me.matsumo.onenavi.core.resource.Res
import me.matsumo.onenavi.core.resource.setting_voice_category_title
import me.matsumo.onenavi.core.ui.theme.LocalNavBackStack
import me.matsumo.onenavi.feature.setting.components.SettingSwitchItem
import me.matsumo.onenavi.feature.setting.components.voice.GuidanceCategoryToggle
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingVoiceCategoryScreen(
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
                    Text(stringResource(Res.string.setting_voice_category_title))
                },
                navigationIcon = {
                    IconButton({ navBackStack.removeAt(navBackStack.size - 1) }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Default.ArrowBack,
                            contentDescription = null,
                        )
                    }
                },
            )
        },
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = it,
        ) {
            items(
                items = GuidanceCategoryToggle.entries,
                key = { toggle -> toggle.key },
            ) { toggle ->
                SettingSwitchItem(
                    modifier = Modifier.fillMaxWidth(),
                    title = toggle.key,
                    description = stringResource(toggle.description),
                    value = toggle.key !in setting.disabledGuidanceCategories,
                    onValueChanged = { isEnabled ->
                        viewModel.setGuidanceCategoryEnabled(toggle.key, isEnabled)
                    },
                )
            }
        }
    }
}
