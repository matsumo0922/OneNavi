package me.matsumo.onenavi.feature.setting

import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import me.matsumo.onenavi.core.common.car.CarHardwareDiagnosticsState
import me.matsumo.onenavi.core.model.DeveloperFeature
import me.matsumo.onenavi.core.model.Theme
import me.matsumo.onenavi.core.repository.AppSettingRepository

class SettingViewModel(
    private val repository: AppSettingRepository,
) : ViewModel() {
    val setting = repository.setting
    val carHardwareDiagnostics = CarHardwareDiagnosticsState.snapshot

    fun setTheme(theme: Theme) {
        viewModelScope.launch {
            repository.setTheme(theme)
        }
    }

    fun setUseDynamicColor(useDynamicColor: Boolean) {
        viewModelScope.launch {
            repository.setUseDynamicColor(useDynamicColor)
        }
    }

    fun setSeedColor(color: Color) {
        viewModelScope.launch {
            repository.setSeedColor(color)
        }
    }

    fun setDeveloperMode(developerMode: Boolean) {
        viewModelScope.launch {
            repository.setDeveloperMode(developerMode)
        }
    }

    fun setDeveloperFeatureEnabled(feature: DeveloperFeature, isEnabled: Boolean) {
        viewModelScope.launch {
            repository.setDeveloperFeatureEnabled(feature, isEnabled)
        }
    }

    fun setUseMediaAudioChannelOnCar(useMediaAudioChannelOnCar: Boolean) {
        viewModelScope.launch {
            repository.setUseMediaAudioChannelOnCar(useMediaAudioChannelOnCar)
        }
    }

    fun setTtsVolumeGainDb(ttsVolumeGainDb: Double) {
        viewModelScope.launch {
            repository.setTtsVolumeGainDb(ttsVolumeGainDb)
        }
    }

    fun setSpeedAdaptiveTtsGainEnabled(isEnabled: Boolean) {
        viewModelScope.launch {
            repository.setSpeedAdaptiveTtsGainEnabled(isEnabled)
        }
    }

    fun setSpeedAdaptiveTtsGainMaxDb(maxGainDb: Double) {
        viewModelScope.launch {
            repository.setSpeedAdaptiveTtsGainMaxDb(maxGainDb)
        }
    }

    fun setMapDefaultZoom(defaultZoom: Float) {
        viewModelScope.launch {
            repository.setMapDefaultZoom(defaultZoom)
        }
    }

    fun setMapGuidanceManeuverZoom(guidanceManeuverZoom: Float) {
        viewModelScope.launch {
            repository.setMapGuidanceManeuverZoom(guidanceManeuverZoom)
        }
    }

    fun setMapTiltedCameraDegrees(tiltedCameraDegrees: Float) {
        viewModelScope.launch {
            repository.setMapTiltedCameraDegrees(tiltedCameraDegrees)
        }
    }

    fun setGuidanceCategoryEnabled(categoryKey: String, isEnabled: Boolean) {
        viewModelScope.launch {
            repository.setGuidanceCategoryEnabled(categoryKey, isEnabled)
        }
    }
}
