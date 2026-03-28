package me.matsumo.onenavi

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeUIViewController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.matsumo.onenavi.core.repository.AppSettingRepository
import me.matsumo.onenavi.core.repository.BillingRepository
import org.koin.compose.koinInject

@Suppress("FunctionNaming")
fun MainViewController() = ComposeUIViewController {
    val settingRepository = koinInject<AppSettingRepository>()
    val billingRepository = koinInject<BillingRepository>()
    val userData by settingRepository.setting.collectAsStateWithLifecycle(null)

    LaunchedEffect(Unit) {
        billingRepository.configure()
        settingRepository.initializeIdIfNeeded()
    }

    userData?.let {
        OneNaviApp(
            modifier = Modifier.fillMaxSize(),
            setting = it,
        )
    }
}
