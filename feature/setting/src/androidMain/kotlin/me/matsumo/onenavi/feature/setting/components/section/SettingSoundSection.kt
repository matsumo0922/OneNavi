package me.matsumo.onenavi.feature.setting.components.section

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import me.matsumo.onenavi.core.model.AppSetting
import me.matsumo.onenavi.core.resource.Res
import me.matsumo.onenavi.core.resource.setting_sound
import me.matsumo.onenavi.core.resource.setting_sound_category
import me.matsumo.onenavi.core.resource.setting_sound_category_description
import me.matsumo.onenavi.core.resource.setting_sound_media_channel
import me.matsumo.onenavi.core.resource.setting_sound_media_channel_description
import me.matsumo.onenavi.core.resource.setting_sound_speed_adaptive_tts_gain
import me.matsumo.onenavi.core.resource.setting_sound_speed_adaptive_tts_gain_description
import me.matsumo.onenavi.core.resource.setting_sound_speed_adaptive_tts_gain_max
import me.matsumo.onenavi.core.resource.setting_sound_speed_adaptive_tts_gain_max_description
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
    onSpeedAdaptiveTtsGainEnabledChanged: (Boolean) -> Unit,
    onSpeedAdaptiveTtsGainMaxDbChanged: (Double) -> Unit,
    onVoiceCategoryClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // ドラッグ中の毎 tick で DataStore へ書き込まないよう、編集中の値は draft として保持し、
    // 操作確定時 (onValueChangeFinished) にだけ永続化する。
    var ttsVolumeGainDbDraft by remember(setting.ttsVolumeGainDb) {
        mutableDoubleStateOf(setting.ttsVolumeGainDb)
    }
    var speedAdaptiveTtsGainMaxDbDraft by remember(setting.speedAdaptiveTtsGainMaxDb) {
        mutableDoubleStateOf(setting.speedAdaptiveTtsGainMaxDb)
    }

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
            valueLabel = formatTtsVolumeGainDb(ttsVolumeGainDbDraft),
            value = ttsVolumeGainDbDraft.toFloat(),
            onValueChanged = { volumeGainDb ->
                ttsVolumeGainDbDraft = roundTtsVolumeGainDb(volumeGainDb)
            },
            onValueChangeFinished = {
                onTtsVolumeGainDbChanged(ttsVolumeGainDbDraft)
            },
            valueRange = AppSetting.TTS_VOLUME_GAIN_DB_MIN.toFloat()..AppSetting.TTS_VOLUME_GAIN_DB_MAX.toFloat(),
            steps = AppSetting.TTS_VOLUME_GAIN_DB_STEPS,
        )

        SettingSwitchItem(
            modifier = Modifier.fillMaxWidth(),
            title = Res.string.setting_sound_speed_adaptive_tts_gain,
            description = Res.string.setting_sound_speed_adaptive_tts_gain_description,
            value = setting.isSpeedAdaptiveTtsGainEnabled,
            onValueChanged = onSpeedAdaptiveTtsGainEnabledChanged,
        )

        SettingSliderItem(
            modifier = Modifier.fillMaxWidth(),
            title = Res.string.setting_sound_speed_adaptive_tts_gain_max,
            description = Res.string.setting_sound_speed_adaptive_tts_gain_max_description,
            valueLabel = formatTtsVolumeGainDb(speedAdaptiveTtsGainMaxDbDraft),
            value = speedAdaptiveTtsGainMaxDbDraft.toFloat(),
            onValueChanged = { maxGainDb ->
                speedAdaptiveTtsGainMaxDbDraft = roundTtsVolumeGainDb(maxGainDb)
            },
            onValueChangeFinished = {
                onSpeedAdaptiveTtsGainMaxDbChanged(speedAdaptiveTtsGainMaxDbDraft)
            },
            valueRange = SpeedAdaptiveTtsGainMaxDbRange,
            steps = AppSetting.SPEED_ADAPTIVE_TTS_GAIN_MAX_DB_STEPS,
            isEnabled = setting.isSpeedAdaptiveTtsGainEnabled,
        )

        SettingTextItem(
            modifier = Modifier.fillMaxWidth(),
            title = Res.string.setting_sound_category,
            description = Res.string.setting_sound_category_description,
            onClick = onVoiceCategoryClicked,
        )
    }
}

private fun formatTtsVolumeGainDb(volumeGainDb: Double): String {
    if (volumeGainDb == 0.0) return "0 dB"

    return String.format(
        Locale.US,
        "%+.0f dB",
        volumeGainDb,
    )
}

private fun roundTtsVolumeGainDb(volumeGainDb: Float): Double =
    volumeGainDb.roundToInt().toDouble()

/** 速度連動 TTS ゲイン最大量 slider の範囲。 */
private val SpeedAdaptiveTtsGainMaxDbRange =
    AppSetting.SPEED_ADAPTIVE_TTS_GAIN_MAX_DB_MIN.toFloat().rangeTo(
        AppSetting.SPEED_ADAPTIVE_TTS_GAIN_MAX_DB_MAX.toFloat(),
    )
