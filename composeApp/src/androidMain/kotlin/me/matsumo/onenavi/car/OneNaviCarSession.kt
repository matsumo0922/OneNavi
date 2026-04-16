package me.matsumo.onenavi.car

import android.content.Intent
import android.content.res.Configuration
import androidx.car.app.Screen
import androidx.car.app.Session

/**
 * Android Auto セッション。
 * 地図表示用の [OneNaviCarMapScreen] を返す。
 */
class OneNaviCarSession : Session() {

    override fun onCreateScreen(intent: Intent): Screen {
        return OneNaviCarMapScreen(carContext)
    }

    override fun onCarConfigurationChanged(newConfiguration: Configuration) {
        super.onCarConfigurationChanged(newConfiguration)
    }
}
