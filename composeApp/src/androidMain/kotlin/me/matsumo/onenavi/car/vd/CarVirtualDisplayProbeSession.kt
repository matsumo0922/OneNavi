package me.matsumo.onenavi.car.vd

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session

/** Android Auto host との接続ごとに VD Activity 検証 Screen を生成する Session。 */
class CarVirtualDisplayProbeSession : Session() {

    override fun onCreateScreen(intent: Intent): Screen {
        return CarVirtualDisplayProbeScreen(carContext)
    }
}
