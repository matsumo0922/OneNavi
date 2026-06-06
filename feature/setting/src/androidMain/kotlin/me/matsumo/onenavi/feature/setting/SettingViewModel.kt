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

    fun setGuidanceCategoryEnabled(categoryKey: String, isEnabled: Boolean) {
        viewModelScope.launch {
            val current = repository.setting.value.disabledGuidanceCategories
            val updated = nextDisabledCategories(current, categoryKey, isEnabled)
            repository.setDisabledGuidanceCategories(updated)
        }
    }

    /** ON なら OFF 集合から除外し、OFF なら追加した新しい OFF 集合を返す。 */
    private fun nextDisabledCategories(
        current: Set<String>,
        categoryKey: String,
        isEnabled: Boolean,
    ): Set<String> {
        return if (isEnabled) current - categoryKey else current + categoryKey
    }
}
