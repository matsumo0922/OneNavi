package me.matsumo.onenavi.car.vd

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import me.matsumo.onenavi.car.hardware.CarHardwareDataSource
import me.matsumo.onenavi.car.navigation.CarNavigationSessionPublisher
import me.matsumo.onenavi.core.common.car.CarDisplayState
import me.matsumo.onenavi.core.common.car.CarPhoneSessionCoordinator
import me.matsumo.onenavi.core.common.car.OneNaviDisplaySurface
import me.matsumo.onenavi.core.common.car.parseAssistantNavUri
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** Android Auto host との接続ごとに VD Activity 検証 Screen を生成する Session。 */
class CarVirtualDisplayProbeSession : Session(), KoinComponent {

    private val carHardwareDataSource by inject<CarHardwareDataSource>()
    private val carNavigationSessionPublisher by inject<CarNavigationSessionPublisher>()
    private val carPhoneSessionCoordinator by inject<CarPhoneSessionCoordinator>()

    init {
        CarDisplayState.registerCarDisplay()
        lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onDestroy(owner: LifecycleOwner) {
                    carHardwareDataSource.detach()
                    carNavigationSessionPublisher.detach()
                    CarDisplayState.unregisterCarDisplay()
                }
            },
        )
    }

    override fun onCreateScreen(intent: Intent): Screen {
        carHardwareDataSource.attach(carContext)
        carNavigationSessionPublisher.attach(carContext)
        dispatchAssistantIntent(intent)
        return CarVirtualDisplayProbeScreen(carContext)
    }

    override fun onNewIntent(intent: Intent) {
        dispatchAssistantIntent(intent)
    }

    private fun dispatchAssistantIntent(intent: Intent) {
        val uri = intent.dataString ?: return
        val request = parseAssistantNavUri(uri, intent.action) ?: return

        carPhoneSessionCoordinator.requestAssistantNavigation(request, OneNaviDisplaySurface.AndroidAutoVirtualDisplay)
    }
}
