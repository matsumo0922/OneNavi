package me.matsumo.onenavi.car

import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.matsumo.onenavi.core.common.car.CarDisplayState
import me.matsumo.onenavi.core.common.car.CarPhoneSessionCoordinator
import me.matsumo.onenavi.core.common.car.OneNaviDisplaySurface
import me.matsumo.onenavi.core.model.RoadClass
import me.matsumo.onenavi.core.model.RouteDetail
import me.matsumo.onenavi.core.model.RoutePoint
import me.matsumo.onenavi.core.navigation.newguidance.model.GuidanceProgress
import me.matsumo.onenavi.core.navigation.newguidance.model.GuidanceState
import me.matsumo.onenavi.core.navigation.newguidance.model.RouteMatchState
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
        val guidanceState = MutableStateFlow<GuidanceState>(GuidanceState.Idle)
        var releaseCount = 0
        CarGuidanceSessionReleaser(
            carPhoneSessionCoordinator = coordinator,
            guidanceState = guidanceState,
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
        val guidanceState = MutableStateFlow<GuidanceState>(GuidanceState.Idle)
        var releaseCount = 0
        CarGuidanceSessionReleaser(
            carPhoneSessionCoordinator = coordinator,
            guidanceState = guidanceState,
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

    @Test
    fun `スマホ単体で active guidance 中なら表示面が無くても release しない`() = runTest {
        val coordinator = CarPhoneSessionCoordinator()
        val guidanceState = MutableStateFlow<GuidanceState>(GuidanceState.Idle)
        var releaseCount = 0
        CarGuidanceSessionReleaser(
            carPhoneSessionCoordinator = coordinator,
            guidanceState = guidanceState,
            releaseGuidanceSession = { releaseCount += RELEASE_COUNT_INCREMENT },
            scope = backgroundScope,
        ).ensureStarted()
        runCurrent()

        coordinator.registerSurface(OneNaviDisplaySurface.Phone)
        guidanceState.value = buildActiveGuidanceState()
        runCurrent()

        coordinator.unregisterSurface(OneNaviDisplaySurface.Phone)
        advanceTimeBy(RELEASE_DELAY_MILLIS)
        runCurrent()

        assertEquals(0, releaseCount)

        guidanceState.value = GuidanceState.Idle
        runCurrent()
        advanceTimeBy(RELEASE_DELAY_MILLIS)
        runCurrent()

        assertEquals(1, releaseCount)
    }

    private fun buildActiveGuidanceState(): GuidanceState {
        return GuidanceState.Rerouting(
            previousRoute = buildRoute(),
            previousProgress = buildProgress(),
        )
    }

    private fun buildRoute(): RouteDetail {
        val origin = RoutePoint(
            latitude = 35.0,
            longitude = 139.0,
        )
        val destination = RoutePoint(
            latitude = 35.1,
            longitude = 139.1,
        )

        return RouteDetail(
            id = "route-0",
            origin = origin,
            destination = destination,
            intermediateWaypoints = persistentListOf(),
            geometry = persistentListOf(origin, destination),
            distanceMeters = 1_000.0,
            durationSeconds = 120.0,
            steps = persistentListOf(),
        )
    }

    private fun buildProgress(): GuidanceProgress {
        val location = RoutePoint(
            latitude = 35.0,
            longitude = 139.0,
        )

        return GuidanceProgress(
            distanceRemainingMeters = 1_000,
            durationRemainingSeconds = 120,
            etaEpochMillis = 1_000L,
            traveledMeters = 0,
            elapsedSeconds = 0,
            currentCumulativeMeters = 0.0,
            snappedLocation = location,
            bearingDegrees = 0f,
            observedLocation = null,
            observedBearingDegrees = null,
            observedAccuracyMeters = null,
            locationTimestampMillis = 1_000L,
            locationElapsedRealtimeNanos = null,
            vehicleSpeedMps = null,
            currentRoadName = null,
            currentRoadClass = RoadClass.ORDINARY,
            currentSpeedLimitKmh = null,
            routeMatchState = RouteMatchState.ON_ROUTE,
            projectionErrorMeters = null,
        )
    }

    /** release callback の呼び出し数増分。 */
    private companion object {

        /** 表示面消滅から案内停止までの猶予時間。 */
        const val RELEASE_DELAY_MILLIS = 500L

        /** release callback の呼び出し数増分。 */
        const val RELEASE_COUNT_INCREMENT = 1
    }
}
