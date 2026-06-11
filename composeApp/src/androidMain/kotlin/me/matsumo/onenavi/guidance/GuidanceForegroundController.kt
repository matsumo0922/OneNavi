package me.matsumo.onenavi.guidance

import io.github.aakira.napier.Napier
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

    /** 表示面へ戻った時に案内中なら Foreground Service の再起動を試みる。 */
    fun restartIfGuidanceActive() {
        if (guidanceState.value.requiresForegroundService()) {
            startForegroundService()
        }
    }

    private fun handleForegroundServiceState(requiresForegroundService: Boolean) {
        if (requiresForegroundService) {
            startForegroundService()
        } else {
            stopForegroundService()
        }
    }

    private fun startForegroundService() {
        runCatching {
            startService()
        }.onFailure { error ->
            Napier.w(tag = TAG, throwable = error) { "Failed to request guidance foreground service start." }
        }
    }

    private fun stopForegroundService() {
        runCatching {
            stopService()
        }.onFailure { error ->
            Napier.w(tag = TAG, throwable = error) { "Failed to request guidance foreground service stop." }
        }
    }

    /** logcat 用の固定値。 */
    private companion object {
        /** logcat 抽出用タグ。 */
        const val TAG = "GuidanceForegroundController"
    }
}
