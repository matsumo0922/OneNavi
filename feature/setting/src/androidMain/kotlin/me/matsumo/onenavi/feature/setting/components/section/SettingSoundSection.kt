package me.matsumo.onenavi.feature.setting.components.section

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import me.matsumo.onenavi.core.model.AppSetting
import me.matsumo.onenavi.core.resource.Res
import me.matsumo.onenavi.core.resource.setting_sound
import me.matsumo.onenavi.core.resource.setting_sound_media_channel
import me.matsumo.onenavi.core.resource.setting_sound_media_channel_description
import me.matsumo.onenavi.feature.setting.components.SettingSwitchItem
import me.matsumo.onenavi.feature.setting.components.SettingTitleItem

@Composable
internal fun SettingSoundSection(
    setting: AppSetting,
    onUseMediaAudioChannelOnCarChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        SettingTitleItem(
            modifier = Modifier.fillMaxWidth(),
            text = Res.string.setting_sound,
        )

        SettingSwitchItem(
            modifier = Modifier.fillMaxWidth(),
            title = Res.string.setting_sound_media_channel,
            description = Res.string.setting_sound_media_channel_description,
            value = setting.useMediaAudioChannelOnCar,
            onValueChanged = onUseMediaAudioChannelOnCarChanged,
        )
    }
}
