package me.matsumo.onenavi.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate

/**
 * Android Auto の地図表示画面。
 * [NavigationTemplate] を使用して Google ナビゲーション向けの操作面を表示する。
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
