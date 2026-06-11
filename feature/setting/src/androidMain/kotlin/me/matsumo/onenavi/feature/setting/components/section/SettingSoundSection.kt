package me.matsumo.onenavi.feature.setting.components.section

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import me.matsumo.onenavi.core.model.AppSetting
import me.matsumo.onenavi.core.resource.Res
import me.matsumo.onenavi.core.resource.setting_sound
import me.matsumo.onenavi.core.resource.setting_sound_category
import me.matsumo.onenavi.core.resource.setting_sound_category_description
import me.matsumo.onenavi.core.resource.setting_sound_media_channel
import me.matsumo.onenavi.core.resource.setting_sound_media_channel_description
import me.matsumo.onenavi.core.resource.setting_sound_tts_volume_gain
import me.matsumo.onenavi.core.resource.setting_sound_tts_volume_gain_description
import me.matsumo.onenavi.feature.setting.components.SettingSliderItem
import me.matsumo.onenavi.feature.setting.components.SettingSwitchItem
import me.matsumo.onenavi.feature.setting.components.SettingTextItem
import me.matsumo.onenavi.feature.setting.components.SettingTitleItem
import java.util.Locale
import kotlin.math.roundToInt

@Composable
internal fun SettingSoundSection(
    setting: AppSetting,
    onUseMediaAudioChannelOnCarChanged: (Boolean) -> Unit,
    onTtsVolumeGainDbChanged: (Double) -> Unit,
    onVoiceCategoryClicked: () -> Unit,
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

        SettingSliderItem(
            modifier = Modifier.fillMaxWidth(),
            title = Res.string.setting_sound_tts_volume_gain,
            description = Res.string.setting_sound_tts_volume_gain_description,
            valueLabel = formatTtsVolumeGainDb(setting.ttsVolumeGainDb),
            value = setting.ttsVolumeGainDb.toFloat(),
            onValueChanged = { volumeGainDb ->
                onTtsVolumeGainDbChanged(roundTtsVolumeGainDb(volumeGainDb))
            },
            valueRange = AppSetting.TTS_VOLUME_GAIN_DB_MIN.toFloat()..AppSetting.TTS_VOLUME_GAIN_DB_MAX.toFloat(),
            steps = AppSetting.TTS_VOLUME_GAIN_DB_STEPS,
        )

        SettingTextItem(
            modifier = Modifier.fillMaxWidth(),
            title = Res.string.setting_sound_category,
            description = Res.string.setting_sound_category_description,
            onClick = onVoiceCategoryClicked,
        )
    }
}

private fun formatTtsVolumeGainDb(volumeGainDb: Double): String =
    String.format(
        Locale.US,
        "%+.0f dB",
        volumeGainDb,
    )

private fun roundTtsVolumeGainDb(volumeGainDb: Float): Double =
    volumeGainDb.roundToInt().toDouble()
