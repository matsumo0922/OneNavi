package me.matsumo.onenavi.guidance

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import me.matsumo.onenavi.core.navigation.newguidance.model.GuidanceState
import java.util.concurrent.atomic.AtomicBoolean

/** 案内状態に合わせて案内用 Foreground Service の起動と停止を制御する coordinator。 */
internal class GuidanceForegroundController(
    private val guidanceState: StateFlow<GuidanceState>,
    private val startService: () -> Unit,
    private val stopService: () -> Unit,
    private val scope: CoroutineScope,
) {

    private val isStarted = AtomicBoolean(false)

    /** 案内状態の監視を一度だけ開始する。 */
    fun ensureStarted() {
        if (!isStarted.compareAndSet(false, true)) {
            return
        }

        guidanceState
            .map { state -> state.requiresForegroundService() }
            .distinctUntilChanged()
            .onEach(::handleForegroundServiceState)
            .launchIn(scope)
    }

    private fun handleForegroundServiceState(requiresForegroundService: Boolean) {
        if (requiresForegroundService) {
            startService()
        } else {
            stopService()
        }
    }

    private fun GuidanceState.requiresForegroundService(): Boolean {
        return when (this) {
            is GuidanceState.Guiding,
            is GuidanceState.Rerouting,
            -> true
            GuidanceState.Arrived,
            is GuidanceState.Failed,
            GuidanceState.Idle,
            -> false
        }
    }
}
