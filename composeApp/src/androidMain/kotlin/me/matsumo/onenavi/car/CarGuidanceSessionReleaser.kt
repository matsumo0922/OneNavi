package me.matsumo.onenavi.car

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import me.matsumo.onenavi.core.common.car.CarDisplayState
import me.matsumo.onenavi.core.common.car.CarPhoneSessionCoordinator

/**
 * 全ての表示面と Android Auto session が閉じた時だけ共有中の案内 session を停止する監視役。
 */
internal class CarGuidanceSessionReleaser(
    private val carPhoneSessionCoordinator: CarPhoneSessionCoordinator,
    private val releaseGuidanceSession: () -> Unit,
    private val scope: CoroutineScope,
) {

    private var hasObservedGuidanceOwner = false
    private var releaseJob: Job? = null

    init {
        carPhoneSessionCoordinator.state
            .map { state -> state.activeSurfaces.isNotEmpty() }
            .combine(CarDisplayState.isOnCarFlow) { hasActiveSurface, isOnCar ->
                hasActiveSurface || isOnCar
            }
            .distinctUntilChanged()
            .onEach { hasGuidanceOwner -> handleGuidanceOwnerChanged(hasGuidanceOwner) }
            .launchIn(scope)
    }

    /** 監視が開始済みであることを呼び出し側から明示する。 */
    fun ensureStarted() = Unit

    private fun handleGuidanceOwnerChanged(hasGuidanceOwner: Boolean) {
        if (hasGuidanceOwner) {
            hasObservedGuidanceOwner = true
            releaseJob?.cancel()
            releaseJob = null
            return
        }

        if (!hasObservedGuidanceOwner) {
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
        val hasNoGuidanceOwner = hasNoActiveSurface && !CarDisplayState.isOnCar

        if (hasNoGuidanceOwner) {
            releaseGuidanceSession()
        }
    }

    /** surface 消滅後の release 判定に使う定数群。 */
    private companion object {

        /** 画面再生成による一時的な surface 不在を無視するための猶予時間。 */
        const val EMPTY_SURFACE_RELEASE_DELAY_MILLIS = 500L
    }
}
