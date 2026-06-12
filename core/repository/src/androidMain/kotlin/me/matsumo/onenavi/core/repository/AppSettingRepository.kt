package me.matsumo.onenavi.core.repository

import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.StateFlow
import me.matsumo.onenavi.core.datasource.AppSettingDataSource
import me.matsumo.onenavi.core.model.AppSetting
import me.matsumo.onenavi.core.model.DeveloperFeature
import me.matsumo.onenavi.core.model.Theme

class AppSettingRepository(
    private val dataSource: AppSettingDataSource,
) {
    val setting: StateFlow<AppSetting> = dataSource.setting

    suspend fun currentSetting(): AppSetting = dataSource.currentSetting()

    suspend fun awaitInitialLoad(): AppSetting = dataSource.awaitInitialLoad()

    suspend fun initializeIdIfNeeded() = dataSource.initializeIdIfNeeded()

    suspend fun setId(id: String) = dataSource.setId(id)

    suspend fun setTheme(theme: Theme) = dataSource.setTheme(theme)

    suspend fun setUseDynamicColor(useDynamicColor: Boolean) = dataSource.setUseDynamicColor(useDynamicColor)

    suspend fun setSeedColor(color: Color) = dataSource.setSeedColor(color)

    suspend fun setPlusMode(plusMode: Boolean) = dataSource.setPlusMode(plusMode)

    suspend fun setDeveloperMode(developerMode: Boolean) = dataSource.setDeveloperMode(developerMode)

    suspend fun setDeveloperFeatureEnabled(feature: DeveloperFeature, isEnabled: Boolean) =
        dataSource.setDeveloperFeatureEnabled(feature, isEnabled)

    suspend fun setUseMediaAudioChannelOnCar(useMediaAudioChannelOnCar: Boolean) =
        dataSource.setUseMediaAudioChannelOnCar(useMediaAudioChannelOnCar)

    suspend fun setTtsVolumeGainDb(ttsVolumeGainDb: Double) = dataSource.setTtsVolumeGainDb(ttsVolumeGainDb)

    suspend fun setTtsVoiceNameOverride(ttsVoiceNameOverride: String) =
        dataSource.setTtsVoiceNameOverride(ttsVoiceNameOverride)

    suspend fun setTtsSpeakingRateOverride(ttsSpeakingRateOverride: Double) =
        dataSource.setTtsSpeakingRateOverride(ttsSpeakingRateOverride)

    suspend fun setGuidanceCategoryEnabled(categoryKey: String, isEnabled: Boolean) =
        dataSource.setGuidanceCategoryEnabled(categoryKey, isEnabled)
}
