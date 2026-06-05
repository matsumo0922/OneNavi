package me.matsumo.onenavi.car

import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * Android Auto の地図表示画面。
 *
 * [NavigationTemplate] でナビ向けの操作面を表示しつつ、地図そのものは
 * [OneNaviCarMapRenderer] を [AppManager.setSurfaceCallback] に登録して
 * app 所有の Surface へ描画する。
 */
class OneNaviCarMapScreen(
    carContext: CarContext,
) : Screen(carContext), DefaultLifecycleObserver {

    private val mapRenderer = OneNaviCarMapRenderer(carContext)

    init {
        lifecycle.addObserver(this)
    }

    override fun onCreate(owner: LifecycleOwner) {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(mapRenderer)
    }

    override fun onDestroy(owner: LifecycleOwner) {
        mapRenderer.release()
    }

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
