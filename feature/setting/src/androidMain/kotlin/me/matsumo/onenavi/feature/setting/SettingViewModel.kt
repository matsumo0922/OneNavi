package me.matsumo.onenavi.feature.setting

import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import me.matsumo.onenavi.core.model.Theme
import me.matsumo.onenavi.core.repository.AppSettingRepository

class SettingViewModel(
    private val repository: AppSettingRepository,
) : ViewModel() {
    val setting = repository.setting

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

    fun setUseMediaAudioChannelOnCar(useMediaAudioChannelOnCar: Boolean) {
        viewModelScope.launch {
            repository.setUseMediaAudioChannelOnCar(useMediaAudioChannelOnCar)
        }
    }
}
