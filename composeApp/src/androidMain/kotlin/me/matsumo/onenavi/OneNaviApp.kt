package me.matsumo.onenavi

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.lexilabs.basic.ads.BasicAds
import app.lexilabs.basic.ads.DependsOnGoogleMobileAds
import app.lexilabs.basic.ads.DependsOnGoogleUserMessagingPlatform
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.request.crossfade
import io.github.vinceglb.filekit.coil.addPlatformFileSupport
import me.matsumo.onenavi.core.model.AppSetting
import me.matsumo.onenavi.core.ui.theme.OneNaviTheme

@OptIn(DependsOnGoogleMobileAds::class, DependsOnGoogleUserMessagingPlatform::class)
@Composable
internal fun OneNaviApp(
    setting: AppSetting,
    modifier: Modifier = Modifier,
) {
    SetupCoil()
    BasicAds.Initialize()

    OneNaviTheme(setting) {
        AppNavHost(modifier)
    }
}

@Composable
private fun SetupCoil() {
    setSingletonImageLoaderFactory { context ->
        ImageLoader.Builder(context)
            .components {
                addPlatformFileSupport()
            }
            .crossfade(true)
            .build()
    }
}
