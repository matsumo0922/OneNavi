package me.matsumo.onenavi.feature.map.state

import me.matsumo.onenavi.core.model.RoadClass
import me.matsumo.onenavi.core.model.RoutePoint
import me.matsumo.onenavi.core.navigation.newguidance.model.GuidanceProgress
import me.matsumo.onenavi.core.navigation.newguidance.model.RouteMatchState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [GuidanceVehicleLocationSelector] の表示位置選択テスト。
 */
@Suppress("NonAsciiCharacters")
class GuidanceVehicleLocationSelectorTest {

    @Test
    fun route上ではrouteSnapped位置を選ぶ() {
        val progress = guidanceProgress(routeMatchState = RouteMatchState.ON_ROUTE)

        val actual = GuidanceVehicleLocationSelector.select(progress = progress)

        assertEquals(progress.snappedLocation, actual.location)
        assertEquals(VehicleLocationSource.ROUTE_SNAPPED, actual.source)
        assertEquals(progress.currentCumulativeMeters, actual.routeProgressMeters)
        assertEquals(RouteMatchState.ON_ROUTE, actual.routeMatchState)
    }

    @Test
    fun offRouteCandidateでは実位置を選ぶ() {
        val progress = guidanceProgress(routeMatchState = RouteMatchState.OFF_ROUTE_CANDIDATE)

        val actual = GuidanceVehicleLocationSelector.select(progress = progress)

        assertEquals(OBSERVED_LOCATION, actual.location)
        assertEquals(VehicleLocationSource.RAW_GPS, actual.source)
        assertNull(actual.routeProgressMeters)
        assertEquals(RouteMatchState.OFF_ROUTE_CANDIDATE, actual.routeMatchState)
        assertEquals(progress.projectionErrorMeters, actual.projectionErrorMeters)
    }

    @Test
    fun offRouteConfirmedでも実位置を選ぶ() {
        val progress = guidanceProgress(routeMatchState = RouteMatchState.OFF_ROUTE_CONFIRMED)

        val actual = GuidanceVehicleLocationSelector.select(progress = progress)

        assertEquals(OBSERVED_LOCATION, actual.location)
        assertEquals(VehicleLocationSource.RAW_GPS, actual.source)
        assertNull(actual.routeProgressMeters)
        assertEquals(RouteMatchState.OFF_ROUTE_CONFIRMED, actual.routeMatchState)
    }

    @Test
    fun offRouteでも観測位置がない場合はrouteSnapped位置にfallbackする() {
        val progress = guidanceProgress(
            routeMatchState = RouteMatchState.OFF_ROUTE_CANDIDATE,
            observedLocation = null,
        )

        val actual = GuidanceVehicleLocationSelector.select(progress = progress)

        assertEquals(progress.snappedLocation, actual.location)
        assertEquals(VehicleLocationSource.ROUTE_SNAPPED, actual.source)
        assertEquals(progress.currentCumulativeMeters, actual.routeProgressMeters)
    }

    private fun guidanceProgress(
        routeMatchState: RouteMatchState,
        observedLocation: RoutePoint? = OBSERVED_LOCATION,
    ): GuidanceProgress = GuidanceProgress(
        distanceRemainingMeters = 1_000,
        durationRemainingSeconds = 120,
        etaEpochMillis = NOW_MILLIS + 120_000L,
        traveledMeters = 100,
        elapsedSeconds = 60,
        currentCumulativeMeters = ROUTE_PROGRESS_METERS,
        snappedLocation = ROUTE_SNAPPED_LOCATION,
        bearingDegrees = 90f,
        observedLocation = observedLocation,
        observedBearingDegrees = 45f,
        observedAccuracyMeters = 5f,
        locationTimestampMillis = NOW_MILLIS,
        locationElapsedRealtimeNanos = NOW_NANOS,
        vehicleSpeedMps = 8f,
        currentRoadName = null,
        currentRoadClass = RoadClass.ORDINARY,
        currentSpeedLimitKmh = null,
        routeMatchState = routeMatchState,
        projectionErrorMeters = PROJECTION_ERROR_METERS,
    )

    private companion object {

        const val NOW_MILLIS = 1_000_000L
        const val NOW_NANOS = 1_000_000_000L
        const val ROUTE_PROGRESS_METERS = 120.0
        const val PROJECTION_ERROR_METERS = 35.0

        val ROUTE_SNAPPED_LOCATION = RoutePoint(
            latitude = 35.0,
            longitude = 139.0,
        )
        val OBSERVED_LOCATION = RoutePoint(
            latitude = 35.0003,
            longitude = 139.0003,
        )
    }
}
