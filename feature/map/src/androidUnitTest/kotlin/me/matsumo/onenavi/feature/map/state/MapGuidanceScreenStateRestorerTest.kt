package me.matsumo.onenavi.feature.map.state

import kotlinx.collections.immutable.persistentListOf
import me.matsumo.onenavi.core.model.RoadClass
import me.matsumo.onenavi.core.model.RouteDetail
import me.matsumo.onenavi.core.model.RoutePoint
import me.matsumo.onenavi.core.navigation.newguidance.model.GuidanceProgress
import me.matsumo.onenavi.core.navigation.newguidance.model.GuidanceState
import me.matsumo.onenavi.core.navigation.newguidance.model.RouteMatchState
import me.matsumo.onenavi.core.navigation.newguidance.presentation.GuidancePresentation
import kotlin.test.Test
import kotlin.test.assertEquals

/** 案内 state から地図画面 stack を復元する helper のテスト。 */
class MapGuidanceScreenStateRestorerTest {

    @Test
    fun `Guiding なら Navigating を復元する`() {
        val states = MapGuidanceScreenStateRestorer.restore(
            states = listOf(MapScreenState.Browsing),
            guidanceState = GuidanceState.Guiding(
                route = buildRoute(),
                progress = buildProgress(),
                presentation = GuidancePresentation.Empty,
            ),
        )

        assertEquals(
            expected = listOf(
                MapScreenState.Browsing,
                MapScreenState.Navigating,
            ),
            actual = states,
        )
    }

    @Test
    fun `Rerouting なら Navigating を復元する`() {
        val states = MapGuidanceScreenStateRestorer.restore(
            states = listOf(MapScreenState.Browsing),
            guidanceState = GuidanceState.Rerouting(
                previousRoute = buildRoute(),
                previousProgress = buildProgress(),
            ),
        )

        assertEquals(
            expected = listOf(
                MapScreenState.Browsing,
                MapScreenState.Navigating,
            ),
            actual = states,
        )
    }

    @Test
    fun `Idle では stack を変更しない`() {
        val states = listOf<MapScreenState>(
            MapScreenState.Browsing,
            MapScreenState.Navigating,
        )

        val restoredStates = MapGuidanceScreenStateRestorer.restore(
            states = states,
            guidanceState = GuidanceState.Idle,
        )

        assertEquals(
            expected = states,
            actual = restoredStates,
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
}
