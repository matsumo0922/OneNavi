package me.matsumo.onenavi

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.ads.MobileAds
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.init
import me.matsumo.onenavi.core.model.Theme
import me.matsumo.onenavi.core.ui.theme.shouldUseDarkTheme
import org.koin.androidx.viewmodel.ext.android.viewModel

class MainActivity : ComponentActivity() {

    private val viewModel by viewModel<MainViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val userData by viewModel.setting.collectAsStateWithLifecycle(null)
            val isSystemInDarkTheme = shouldUseDarkTheme(userData?.theme ?: Theme.System)

            val lightScrim = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
            val darkScrim = Color.argb(0x80, 0x1b, 0x1b, 0x1b)

            DisposableEffect(isSystemInDarkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { isSystemInDarkTheme },
                    navigationBarStyle = SystemBarStyle.auto(lightScrim, darkScrim) { isSystemInDarkTheme },
                )
                onDispose {}
            }

            userData?.let {
                OneNaviApp(
                    modifier = Modifier.fillMaxSize(),
                    setting = it,
                )
            }

            splashScreen.setKeepOnScreenCondition { userData == null }
        }

        FileKit.init(this)
        initAdsSdk()
    }

    private fun initAdsSdk() {
        if (viewModel.isAdsSdkInitialized.value) {
            return
        }

        MobileAds.initialize(this)
        viewModel.setAdsSdkInitialized()
    }
}
