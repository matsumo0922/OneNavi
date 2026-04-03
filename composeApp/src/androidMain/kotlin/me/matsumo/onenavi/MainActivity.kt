package me.matsumo.onenavi

import android.Manifest.permission
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import com.mapbox.navigation.core.lifecycle.MapboxNavigationApp
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.ads.MobileAds
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.init
import me.matsumo.onenavi.components.PermissionScreen
import me.matsumo.onenavi.core.model.Theme
import me.matsumo.onenavi.core.ui.theme.OneNaviTheme
import me.matsumo.onenavi.core.ui.theme.shouldUseDarkTheme
import org.koin.androidx.viewmodel.ext.android.viewModel

class MainActivity : ComponentActivity() {

    private val viewModel by viewModel<MainViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        MapboxNavigationApp.attach(this)
        setContent {
            val userData by viewModel.setting.collectAsStateWithLifecycle(null)
            val isSystemInDarkTheme = shouldUseDarkTheme(userData?.theme ?: Theme.System)

            var isPermissionGranted by remember { mutableStateOf(hasRequiredPermissions()) }

            LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
                isPermissionGranted = hasRequiredPermissions()
            }

            val lightScrim = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
            val darkScrim = Color.argb(0x80, 0x1b, 0x1b, 0x1b)

            DisposableEffect(isSystemInDarkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { isSystemInDarkTheme },
                    navigationBarStyle = SystemBarStyle.auto(lightScrim, darkScrim) { isSystemInDarkTheme },
                )
                onDispose {}
            }

            userData?.let { setting ->
                AnimatedContent(
                    targetState = isPermissionGranted,
                    label = "PermissionTransition",
                ) { granted ->
                    if (granted) {
                        OneNaviApp(
                            modifier = Modifier.fillMaxSize(),
                            setting = setting,
                        )
                    } else {
                        OneNaviTheme(setting) {
                            PermissionScreen(
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
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

    private fun hasRequiredPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(this, permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
}
