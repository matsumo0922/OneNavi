package me.matsumo.onenavi.car.vd

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session

/**
 * インストルメントクラスター用ディスプレイの Session を受け持つ検出専用 Session。
 *
 * メインディスプレイの [CarVirtualDisplayProbeSession] とは別系統で、クラスター対応の
 * 検出のみを担い地図描画は行わない。host から cluster 用 SessionInfo が渡されたときだけ生成される。
 */
class CarClusterProbeSession : Session() {

    override fun onCreateScreen(intent: Intent): Screen {
        return CarClusterProbeScreen(carContext)
    }
}
