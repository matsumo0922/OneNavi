package me.matsumo.onenavi.feature.map.state

import kotlinx.collections.immutable.persistentListOf
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
        val deviceLocation = deviceLocation()

        val actual = GuidanceVehicleLocationSelector.select(
            progress = progress,
            deviceLocationState = deviceLocation,
        )

        assertEquals(progress.snappedLocation, actual.location)
        assertEquals(VehicleLocationSource.ROUTE_SNAPPED, actual.source)
        assertEquals(progress.currentCumulativeMeters, actual.routeProgressMeters)
        assertEquals(RouteMatchState.ON_ROUTE, actual.routeMatchState)
    }

    @Test
    fun offRouteCandidateでは実位置を選ぶ() {
        val progress = guidanceProgress(routeMatchState = RouteMatchState.OFF_ROUTE_CANDIDATE)
        val deviceLocation = deviceLocation()

        val actual = GuidanceVehicleLocationSelector.select(
            progress = progress,
            deviceLocationState = deviceLocation,
        )

        assertEquals(deviceLocation.location, actual.location)
        assertEquals(deviceLocation.source, actual.source)
        assertNull(actual.routeProgressMeters)
        assertEquals(RouteMatchState.OFF_ROUTE_CANDIDATE, actual.routeMatchState)
        assertEquals(progress.projectionErrorMeters, actual.projectionErrorMeters)
    }

    @Test
    fun offRouteConfirmedでも実位置を選ぶ() {
        val progress = guidanceProgress(routeMatchState = RouteMatchState.OFF_ROUTE_CONFIRMED)
        val deviceLocation = deviceLocation()

        val actual = GuidanceVehicleLocationSelector.select(
            progress = progress,
            deviceLocationState = deviceLocation,
        )

        assertEquals(deviceLocation.location, actual.location)
        assertEquals(deviceLocation.source, actual.source)
        assertNull(actual.routeProgressMeters)
        assertEquals(RouteMatchState.OFF_ROUTE_CONFIRMED, actual.routeMatchState)
    }

    @Test
    fun offRouteでも実位置が古い場合はrouteSnapped位置にfallbackする() {
        val progress = guidanceProgress(routeMatchState = RouteMatchState.OFF_ROUTE_CANDIDATE)
        val deviceLocation = deviceLocation(timestampMillis = NOW_MILLIS - STALE_DEVICE_LOCATION_MILLIS)

        val actual = GuidanceVehicleLocationSelector.select(
            progress = progress,
            deviceLocationState = deviceLocation,
        )

        assertEquals(progress.snappedLocation, actual.location)
        assertEquals(VehicleLocationSource.ROUTE_SNAPPED, actual.source)
        assertEquals(progress.currentCumulativeMeters, actual.routeProgressMeters)
    }

    private fun guidanceProgress(
        routeMatchState: RouteMatchState,
    ): GuidanceProgress = GuidanceProgress(
        distanceRemainingMeters = 1_000,
        durationRemainingSeconds = 120,
        etaEpochMillis = NOW_MILLIS + 120_000L,
        traveledMeters = 100,
        currentCumulativeMeters = ROUTE_PROGRESS_METERS,
        snappedLocation = ROUTE_SNAPPED_LOCATION,
        bearingDegrees = 90f,
        locationTimestampMillis = NOW_MILLIS,
        locationElapsedRealtimeNanos = NOW_NANOS,
        vehicleSpeedMps = 8f,
        nextManeuver = null,
        followupManeuver = null,
        lanes = persistentListOf(),
        directionSign = null,
        highwayPanel = null,
        currentRoadName = null,
        currentRoadClass = RoadClass.ORDINARY,
        currentSpeedLimitKmh = null,
        routeMatchState = routeMatchState,
        projectionErrorMeters = PROJECTION_ERROR_METERS,
    )

    private fun deviceLocation(
        timestampMillis: Long = NOW_MILLIS,
    ): VehicleLocationState = VehicleLocationState(
        location = DEVICE_LOCATION,
        bearingDegrees = 45f,
        accuracyMeters = 5f,
        timestampMillis = timestampMillis,
        elapsedRealtimeNanos = NOW_NANOS,
        speedMps = 8f,
        routeProgressMeters = null,
        source = VehicleLocationSource.SDK_ROAD_SNAPPED,
    )

    private companion object {

        const val NOW_MILLIS = 1_000_000L
        const val NOW_NANOS = 1_000_000_000L
        const val ROUTE_PROGRESS_METERS = 120.0
        const val PROJECTION_ERROR_METERS = 35.0
        const val STALE_DEVICE_LOCATION_MILLIS = 10_000L

        val ROUTE_SNAPPED_LOCATION = RoutePoint(
            latitude = 35.0,
            longitude = 139.0,
        )
        val DEVICE_LOCATION = RoutePoint(
            latitude = 35.0003,
            longitude = 139.0003,
        )
    }
}
