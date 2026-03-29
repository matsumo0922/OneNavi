package me.matsumo.onenavi.car

import android.content.Intent
import android.content.res.Configuration
import androidx.car.app.Screen
import androidx.car.app.Session
import com.mapbox.maps.MapboxExperimental
import com.mapbox.maps.extension.androidauto.mapboxMapInstaller

/**
 * Android Auto セッション。
 * セッションのライフサイクルに合わせて MapboxCarMap を管理し、
 * 地図表示用の [OneNaviCarMapScreen] を返す。
 */
@OptIn(MapboxExperimental::class)
class OneNaviCarSession : Session() {

    private val mapboxCarMap = mapboxMapInstaller()
        .onCreated(OneNaviCarMapObserver())
        .install()

    override fun onCreateScreen(intent: Intent): Screen {
        return OneNaviCarMapScreen(carContext)
    }

    override fun onCarConfigurationChanged(newConfiguration: Configuration) {
        super.onCarConfigurationChanged(newConfiguration)
    }
}
