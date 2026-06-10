package me.matsumo.onenavi.car.vd

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import me.matsumo.onenavi.core.common.car.CarDisplayState

/** Android Auto host との接続ごとに VD Activity 検証 Screen を生成する Session。 */
class CarVirtualDisplayProbeSession : Session() {

    init {
        CarDisplayState.registerCarDisplay()
        lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onDestroy(owner: LifecycleOwner) {
                    CarDisplayState.unregisterCarDisplay()
                }
            },
        )
    }

    override fun onCreateScreen(intent: Intent): Screen {
        return CarVirtualDisplayProbeScreen(carContext)
    }
}
