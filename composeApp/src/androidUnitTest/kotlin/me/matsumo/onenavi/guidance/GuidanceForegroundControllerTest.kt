package me.matsumo.onenavi.guidance

import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.matsumo.onenavi.core.model.RoadClass
import me.matsumo.onenavi.core.model.RouteDetail
import me.matsumo.onenavi.core.model.RoutePoint
import me.matsumo.onenavi.core.navigation.newguidance.model.GuidanceProgress
import me.matsumo.onenavi.core.navigation.newguidance.model.GuidanceState
import me.matsumo.onenavi.core.navigation.newguidance.model.RouteMatchState
import me.matsumo.onenavi.core.navigation.newguidance.model.VehiclePositionSource
import me.matsumo.onenavi.core.navigation.newguidance.presentation.GuidancePresentation
import kotlin.test.Test
import kotlin.test.assertEquals

/** [GuidanceForegroundController] の案内状態監視テスト。 */
@OptIn(ExperimentalCoroutinesApi::class)
class GuidanceForegroundControllerTest {

    @Test
    fun `Guiding に入ると Foreground Service を起動する`() = runTest {
        val state = MutableStateFlow<GuidanceState>(GuidanceState.Idle)
        var startCount = 0
        var stopCount = 0
        GuidanceForegroundController(
            guidanceState = state,
            startService = { startCount += COUNT_INCREMENT },
            stopService = { stopCount += COUNT_INCREMENT },
            scope = backgroundScope,
        ).ensureStarted()
        runCurrent()

        state.value = GuidanceState.Guiding(
            route = buildRoute(),
            progress = buildProgress(),
            presentation = GuidancePresentation.Empty,
        )
        runCurrent()

        assertEquals(expected = 1, actual = startCount)
        assertEquals(expected = 1, actual = stopCount)
    }

    @Test
    fun `案内が Idle に戻ると Foreground Service を停止する`() = runTest {
        val state = MutableStateFlow<GuidanceState>(
            GuidanceState.Guiding(
                route = buildRoute(),
                progress = buildProgress(),
                presentation = GuidancePresentation.Empty,
            ),
        )
        var startCount = 0
        var stopCount = 0
        GuidanceForegroundController(
            guidanceState = state,
            startService = { startCount += COUNT_INCREMENT },
            stopService = { stopCount += COUNT_INCREMENT },
            scope = backgroundScope,
        ).ensureStarted()
        runCurrent()

        state.value = GuidanceState.Idle
        runCurrent()

        assertEquals(expected = 1, actual = startCount)
        assertEquals(expected = 1, actual = stopCount)
    }

    @Test
    fun `foreground 復帰時に案内中なら Foreground Service の再起動を試みる`() = runTest {
        val state = MutableStateFlow<GuidanceState>(
            GuidanceState.Guiding(
                route = buildRoute(),
                progress = buildProgress(),
                presentation = GuidancePresentation.Empty,
            ),
        )
        var startCount = 0
        var stopCount = 0
        val controller = GuidanceForegroundController(
            guidanceState = state,
            startService = { startCount += COUNT_INCREMENT },
            stopService = { stopCount += COUNT_INCREMENT },
            scope = backgroundScope,
        )
        controller.ensureStarted()
        runCurrent()

        controller.restartIfGuidanceActive()

        assertEquals(expected = 2, actual = startCount)
        assertEquals(expected = 0, actual = stopCount)
    }

    @Test
    fun `foreground 復帰時に Idle なら Foreground Service の再起動を試みない`() = runTest {
        val state = MutableStateFlow<GuidanceState>(GuidanceState.Idle)
        var startCount = 0
        var stopCount = 0
        val controller = GuidanceForegroundController(
            guidanceState = state,
            startService = { startCount += COUNT_INCREMENT },
            stopService = { stopCount += COUNT_INCREMENT },
            scope = backgroundScope,
        )
        controller.ensureStarted()
        runCurrent()

        controller.restartIfGuidanceActive()

        assertEquals(expected = 0, actual = startCount)
        assertEquals(expected = 1, actual = stopCount)
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
            positionSource = VehiclePositionSource.OBSERVED,
            projectionErrorMeters = null,
        )
    }

    /** テスト用固定値。 */
    private companion object {
        /** callback 呼び出し数の増分。 */
        const val COUNT_INCREMENT = 1
    }
}
