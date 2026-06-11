package me.matsumo.onenavi.car

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.matsumo.onenavi.core.common.car.CarDisplayState
import me.matsumo.onenavi.core.common.car.CarPhoneSessionCoordinator
import me.matsumo.onenavi.core.common.car.OneNaviDisplaySurface
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** [CarGuidanceSessionReleaser] の表示面監視テスト。 */
@OptIn(ExperimentalCoroutinesApi::class)
class CarGuidanceSessionReleaserTest {

    @AfterTest
    fun tearDown() {
        while (CarDisplayState.isOnCar) {
            CarDisplayState.unregisterCarDisplay()
        }
    }

    @Test
    fun `表示面が無くても Android Auto 接続中なら release しない`() = runTest {
        val coordinator = CarPhoneSessionCoordinator()
        var releaseCount = 0
        CarGuidanceSessionReleaser(
            carPhoneSessionCoordinator = coordinator,
            releaseGuidanceSession = { releaseCount += RELEASE_COUNT_INCREMENT },
            scope = backgroundScope,
        ).ensureStarted()
        runCurrent()

        coordinator.registerSurface(OneNaviDisplaySurface.Phone)
        CarDisplayState.registerCarDisplay()
        runCurrent()

        coordinator.unregisterSurface(OneNaviDisplaySurface.Phone)
        advanceTimeBy(RELEASE_DELAY_MILLIS)
        runCurrent()

        assertEquals(0, releaseCount)

        CarDisplayState.unregisterCarDisplay()
        advanceTimeBy(RELEASE_DELAY_MILLIS)
        runCurrent()

        assertEquals(1, releaseCount)
    }

    @Test
    fun `表示面と Android Auto 接続が無ければ release する`() = runTest {
        val coordinator = CarPhoneSessionCoordinator()
        var releaseCount = 0
        CarGuidanceSessionReleaser(
            carPhoneSessionCoordinator = coordinator,
            releaseGuidanceSession = { releaseCount += RELEASE_COUNT_INCREMENT },
            scope = backgroundScope,
        ).ensureStarted()
        runCurrent()

        coordinator.registerSurface(OneNaviDisplaySurface.Phone)
        runCurrent()

        coordinator.unregisterSurface(OneNaviDisplaySurface.Phone)
        advanceTimeBy(RELEASE_DELAY_MILLIS)
        runCurrent()

        assertEquals(1, releaseCount)
    }

    /** release callback の呼び出し数増分。 */
    private companion object {

        /** 表示面消滅から案内停止までの猶予時間。 */
        const val RELEASE_DELAY_MILLIS = 500L

        /** release callback の呼び出し数増分。 */
        const val RELEASE_COUNT_INCREMENT = 1
    }
}
