package me.matsumo.onenavi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.matsumo.onenavi.core.repository.AppSettingRepository
import me.matsumo.onenavi.core.repository.BillingRepository

class MainViewModel(
    private val settingRepository: AppSettingRepository,
    private val billingRepository: BillingRepository,
) : ViewModel() {

    val setting = settingRepository.setting

    private val _isAdsSdkInitialized = MutableStateFlow(false)
    val isAdsSdkInitialized = _isAdsSdkInitialized.asStateFlow()

    init {
        billingRepository.configure()

        viewModelScope.launch {
            settingRepository.initializeIdIfNeeded()
        }
    }

    fun setAdsSdkInitialized() {
        _isAdsSdkInitialized.value = true
    }
}
