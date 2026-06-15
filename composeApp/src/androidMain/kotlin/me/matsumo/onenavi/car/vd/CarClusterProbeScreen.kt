package me.matsumo.onenavi.car.vd

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate

/**
 * インストルメントクラスター用 Session が描画する検出専用の最小 Screen。
 *
 * クラスター対応の有無を確認することだけが目的のため地図描画は行わず、NavigationTemplate を
 * 返すだけに留める。実際の地図描画は別途 #98 の本実装で扱う。
 */
class CarClusterProbeScreen(
    carContext: CarContext,
) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        return NavigationTemplate.Builder()
            .setActionStrip(buildActionStrip())
            .build()
    }

    private fun buildActionStrip(): ActionStrip {
        return ActionStrip.Builder()
            .addAction(Action.APP_ICON)
            .build()
    }
}
