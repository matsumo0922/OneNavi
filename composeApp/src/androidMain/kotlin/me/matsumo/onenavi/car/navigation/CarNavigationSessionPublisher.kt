package me.matsumo.onenavi.car.navigation

import android.os.Looper
import androidx.car.app.CarContext
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.core.content.ContextCompat
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import me.matsumo.onenavi.core.navigation.newguidance.model.GuidanceState
import java.util.concurrent.Executor

/** Android Auto host へ案内開始状態と Trip metadata を送る publisher。 */
internal class CarNavigationSessionPublisher(
    private val guidanceState: StateFlow<GuidanceState>,
    private val stopGuidance: () -> Unit,
    private val scope: CoroutineScope,
    private val tripMapper: GuidanceCarTripMapper,
) {

    private var navigationManager: NavigationManager? = null
    private var mainExecutor: Executor? = null
    private var publishJob: Job? = null
    private var isNavigationStarted = false

    /** Car App Session に接続し、案内状態を host へ配信する。 */
    fun attach(carContext: CarContext) {
        val executor = ContextCompat.getMainExecutor(carContext)
        runOnMain(executor) {
            attachOnMain(carContext, executor)
        }
    }

    /** Car App Session から切断し、host 側の active navigation を終了する。 */
    fun detach() {
        val executor = mainExecutor ?: return
        runOnMain(executor, ::detachOnMain)
    }

    private fun runOnMain(executor: Executor, action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            executor.execute(action)
        }
    }

    private fun attachOnMain(carContext: CarContext, executor: Executor) {
        if (navigationManager != null) {
            detachOnMain()
        }

        val manager = carContext.getCarService(NavigationManager::class.java)
        manager.setNavigationManagerCallback(executor, buildNavigationManagerCallback())
        navigationManager = manager
        mainExecutor = executor
        startPublishing(executor)
    }

    private fun detachOnMain() {
        publishJob?.cancel()
        publishJob = null

        val manager = navigationManager ?: return
        endNavigation(manager)
        runCatching {
            manager.clearNavigationManagerCallback()
        }.onFailure { error ->
            Napier.w(tag = TAG, throwable = error) { "Failed to clear navigation manager callback." }
        }
        navigationManager = null
        mainExecutor = null
    }

    private fun buildNavigationManagerCallback(): NavigationManagerCallback {
        return object : NavigationManagerCallback {
            override fun onStopNavigation() {
                stopGuidance()
            }
        }
    }

    private fun startPublishing(executor: Executor) {
        publishJob?.cancel()
        publishJob = guidanceState
            .onEach { state -> executor.execute { publishStateOnMain(state) } }
            .launchIn(scope)
    }

    private fun publishStateOnMain(state: GuidanceState) {
        val manager = navigationManager ?: return

        when (state) {
            is GuidanceState.Guiding -> publishGuidingState(manager, state)
            is GuidanceState.Preparing -> publishPreparingState(manager, state)
            is GuidanceState.Rerouting -> publishReroutingState(manager, state)
            GuidanceState.Arrived,
            is GuidanceState.Failed,
            GuidanceState.Idle,
            -> endNavigation(manager)
        }
    }

    private fun publishGuidingState(manager: NavigationManager, state: GuidanceState.Guiding) {
        runCatching {
            ensureNavigationStarted(manager)
            manager.updateTrip(tripMapper.toTrip(state))
        }.onFailure { error ->
            Napier.w(tag = TAG, throwable = error) { "Failed to publish Android Auto trip." }
        }
    }

    private fun publishPreparingState(manager: NavigationManager, state: GuidanceState.Preparing) {
        runCatching {
            ensureNavigationStarted(manager)
            manager.updateTrip(tripMapper.toLoadingTrip(state))
        }.onFailure { error ->
            Napier.w(tag = TAG, throwable = error) { "Failed to publish Android Auto preparing trip." }
        }
    }

    private fun publishReroutingState(manager: NavigationManager, state: GuidanceState.Rerouting) {
        runCatching {
            ensureNavigationStarted(manager)
            manager.updateTrip(tripMapper.toLoadingTrip(state))
        }.onFailure { error ->
            Napier.w(tag = TAG, throwable = error) { "Failed to publish Android Auto rerouting trip." }
        }
    }

    private fun ensureNavigationStarted(manager: NavigationManager) {
        if (isNavigationStarted) {
            return
        }

        manager.navigationStarted()
        isNavigationStarted = true
    }

    private fun endNavigation(manager: NavigationManager) {
        if (!isNavigationStarted) {
            return
        }

        runCatching {
            manager.navigationEnded()
        }.onFailure { error ->
            Napier.w(tag = TAG, throwable = error) { "Failed to end Android Auto navigation." }
        }
        isNavigationStarted = false
    }

    /** logcat 用の固定値。 */
    private companion object {
        /** logcat 抽出用タグ。 */
        const val TAG = "CarNavigationPublisher"
    }
}
