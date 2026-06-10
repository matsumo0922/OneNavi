package me.matsumo.onenavi.car

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import me.matsumo.onenavi.core.common.car.CarPhoneSessionCoordinator
import me.matsumo.onenavi.core.navigation.newguidance.NewGuidanceManager

/**
 * 全ての表示面が閉じた時だけ共有中の案内 session を停止する監視役。
 */
class CarGuidanceSessionReleaser(
    private val carPhoneSessionCoordinator: CarPhoneSessionCoordinator,
    private val newGuidanceManager: NewGuidanceManager,
    private val scope: CoroutineScope,
) {

    private var hasObservedActiveSurface = false
    private var releaseJob: Job? = null

    init {
        carPhoneSessionCoordinator.state
            .map { state -> state.activeSurfaces.isNotEmpty() }
            .distinctUntilChanged()
            .onEach { hasActiveSurface -> handleActiveSurfaceChanged(hasActiveSurface) }
            .launchIn(scope)
    }

    /** 監視が開始済みであることを呼び出し側から明示する。 */
    fun ensureStarted() = Unit

    private fun handleActiveSurfaceChanged(hasActiveSurface: Boolean) {
        if (hasActiveSurface) {
            hasObservedActiveSurface = true
            releaseJob?.cancel()
            releaseJob = null
            return
        }

        if (!hasObservedActiveSurface) {
            return
        }

        releaseJob?.cancel()
        releaseJob = scope.launch {
            releaseIfNoActiveSurfaceAfterDelay()
        }
    }

    private suspend fun releaseIfNoActiveSurfaceAfterDelay() {
        delay(EMPTY_SURFACE_RELEASE_DELAY_MILLIS)

        val hasNoActiveSurface = carPhoneSessionCoordinator.state.value.activeSurfaces.isEmpty()
        if (hasNoActiveSurface) {
            newGuidanceManager.release()
        }
    }

    /** surface 消滅後の release 判定に使う定数群。 */
    private companion object {

        /** 画面再生成による一時的な surface 不在を無視するための猶予時間。 */
        const val EMPTY_SURFACE_RELEASE_DELAY_MILLIS = 500L
    }
}
