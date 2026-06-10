package me.matsumo.onenavi

import android.Manifest.permission
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.ads.MobileAds
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.init
import me.matsumo.onenavi.car.CarGuidanceSessionReleaser
import me.matsumo.onenavi.components.PermissionScreen
import me.matsumo.onenavi.core.common.car.CarPhoneSessionCommand
import me.matsumo.onenavi.core.common.car.CarPhoneSessionCommandEnvelope
import me.matsumo.onenavi.core.common.car.CarPhoneSessionCoordinator
import me.matsumo.onenavi.core.common.car.OneNaviDisplaySurface
import me.matsumo.onenavi.core.common.car.PhoneDestinationSearchLauncher
import me.matsumo.onenavi.core.model.Theme
import me.matsumo.onenavi.core.ui.theme.LocalOneNaviDisplaySurface
import me.matsumo.onenavi.core.ui.theme.OneNaviTheme
import me.matsumo.onenavi.core.ui.theme.shouldUseDarkTheme
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class MainActivity : ComponentActivity() {

    private val viewModel by viewModel<MainViewModel>()
    private val carPhoneSessionCoordinator by inject<CarPhoneSessionCoordinator>()
    private val carGuidanceSessionReleaser by inject<CarGuidanceSessionReleaser>()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        carGuidanceSessionReleaser.ensureStarted()
        handleDestinationSearchIntent(intent)
        enableEdgeToEdge()
        setContent {
            val userData by viewModel.setting.collectAsStateWithLifecycle(null)
            val phoneCommand by carPhoneSessionCoordinator.phoneCommand.collectAsStateWithLifecycle()
            val isSystemInDarkTheme = shouldUseDarkTheme(userData?.theme ?: Theme.System)
            val destinationSearchRequestId = phoneCommand?.destinationSearchRequestId()

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
                CompositionLocalProvider(
                    LocalOneNaviDisplaySurface provides OneNaviDisplaySurface.Phone,
                ) {
                    AnimatedContent(
                        targetState = isPermissionGranted,
                        label = "PermissionTransition",
                    ) { granted ->
                        if (granted) {
                            OneNaviApp(
                                modifier = Modifier.fillMaxSize(),
                                setting = setting,
                                destinationSearchRequestId = destinationSearchRequestId,
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
            }

            splashScreen.setKeepOnScreenCondition { userData == null }
        }

        FileKit.init(this)
        initAdsSdk()
    }

    override fun onStart() {
        super.onStart()
        carPhoneSessionCoordinator.registerSurface(OneNaviDisplaySurface.Phone)
    }

    override fun onStop() {
        carPhoneSessionCoordinator.unregisterSurface(OneNaviDisplaySurface.Phone)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDestinationSearchIntent(intent)
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

    private fun handleDestinationSearchIntent(intent: Intent?) {
        if (intent?.action != PhoneDestinationSearchLauncher.ACTION_OPEN_DESTINATION_SEARCH) {
            return
        }

        carPhoneSessionCoordinator.requestPhoneDestinationSearch()
    }

    private fun CarPhoneSessionCommandEnvelope.destinationSearchRequestId(): Long? {
        return when (command) {
            CarPhoneSessionCommand.OpenDestinationSearch -> id
        }
    }
}
