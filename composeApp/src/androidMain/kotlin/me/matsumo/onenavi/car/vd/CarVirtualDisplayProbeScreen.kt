package me.matsumo.onenavi.car.vd

import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import io.github.aakira.napier.Napier
import me.matsumo.onenavi.core.model.DeveloperFeature
import me.matsumo.onenavi.core.repository.AppSettingRepository
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** Android Auto template 画面として Surface callback を登録する検証 Screen。 */
class CarVirtualDisplayProbeScreen(
    private val carContext: CarContext,
) : Screen(carContext), KoinComponent {

    private val appSettingRepository by inject<AppSettingRepository>()
    private val controller = CarVirtualDisplayProbeController(
        context = carContext.applicationContext,
        isDebugOverlayEnabled = {
            appSettingRepository.setting.value.isDeveloperFeatureEnabled(DeveloperFeature.CAR_VD_DEBUG_OVERLAY)
        },
        isInputLatencyLoggingEnabled = {
            appSettingRepository.setting.value.isDeveloperFeatureEnabled(DeveloperFeature.CAR_VD_INPUT_LATENCY_LOGS)
        },
    )
    private val surfaceCallback = CarVirtualDisplayProbeSurfaceCallback(controller)
    private var isSurfaceCallbackRegistered = false

    init {
        lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onDestroy(owner: LifecycleOwner) {
                    unregisterSurfaceCallback()
                    controller.release()
                }
            },
        )
    }

    override fun onGetTemplate(): Template {
        registerSurfaceCallback()
        return buildNavigationTemplate()
    }

    private fun registerSurfaceCallback() {
        if (isSurfaceCallbackRegistered) {
            return
        }

        carContext.getCarService(AppManager::class.java).setSurfaceCallback(surfaceCallback)
        isSurfaceCallbackRegistered = true
    }

    private fun unregisterSurfaceCallback() {
        if (!isSurfaceCallbackRegistered) {
            return
        }

        carContext.getCarService(AppManager::class.java).setSurfaceCallback(null)
        isSurfaceCallbackRegistered = false
    }

    private fun buildNavigationTemplate(): NavigationTemplate {
        return NavigationTemplate.Builder()
            .setActionStrip(buildActionStrip())
            .setMapActionStrip(buildMapActionStrip())
            .setPanModeListener(::handlePanModeChanged)
            .build()
    }

    private fun buildActionStrip(): ActionStrip {
        return ActionStrip.Builder()
            .addAction(Action.APP_ICON)
            .build()
    }

    private fun buildMapActionStrip(): ActionStrip {
        return ActionStrip.Builder()
            .addAction(Action.PAN)
            .build()
    }

    private fun handlePanModeChanged(isInPanMode: Boolean) {
        Napier.i(tag = TAG) { "Pan mode changed. isInPanMode=$isInPanMode" }
        controller.updatePanMode(isInPanMode)
    }

    /** Screen のログタグ。 */
    private companion object {
        /** logcat 抽出用タグ。 */
        const val TAG = "OneNaviCarVd"
    }
}
