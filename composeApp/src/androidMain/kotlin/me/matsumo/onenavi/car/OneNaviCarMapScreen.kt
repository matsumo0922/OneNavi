package me.matsumo.onenavi.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate

/**
 * Android Auto の地図表示画面。
 * [NavigationTemplate] を使用して Mapbox の地図を表示する。
 * 現時点ではナビゲーション機能は含まず、地図の表示のみ。
 */
class OneNaviCarMapScreen(
    carContext: CarContext,
) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        return NavigationTemplate.Builder()
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(
                        Action.Builder()
                            .setTitle("OneNavi")
                            .setOnClickListener { /* no-op */ }
                            .build(),
                    )
                    .build(),
            )
            .setMapActionStrip(
                ActionStrip.Builder()
                    .addAction(Action.PAN)
                    .build(),
            )
            .build()
    }
}
