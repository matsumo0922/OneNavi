package me.matsumo.onenavi.feature.home.map

import androidx.lifecycle.ViewModel
import me.matsumo.onenavi.core.model.AppConfig

class HomeMapViewModel(
    private val appConfig: AppConfig,
) : ViewModel() {
    val mapBoxToken: String get() = appConfig.mapBoxToken
}
