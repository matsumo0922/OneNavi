package me.matsumo.onenavi.car.navigation

import androidx.car.app.model.Distance
import androidx.car.app.navigation.model.Maneuver
import kotlinx.collections.immutable.persistentListOf
import me.matsumo.onenavi.core.model.ManeuverModifier
import me.matsumo.onenavi.core.model.ManeuverType
import me.matsumo.onenavi.core.model.RoadClass
import me.matsumo.onenavi.core.model.RouteDetail
import me.matsumo.onenavi.core.model.RoutePoint
import me.matsumo.onenavi.core.model.RouteWaypoint
import me.matsumo.onenavi.core.navigation.newguidance.model.GuidanceProgress
import me.matsumo.onenavi.core.navigation.newguidance.model.GuidanceState
import me.matsumo.onenavi.core.navigation.newguidance.model.RouteMatchState
import me.matsumo.onenavi.core.navigation.newguidance.model.VehiclePositionSource
import me.matsumo.onenavi.core.navigation.newguidance.presentation.GuidancePresentation
import me.matsumo.onenavi.core.navigation.newguidance.presentation.ManeuverCallout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [GuidanceCarTripMapper] の Android Auto Trip metadata 変換テスト。 */
class GuidanceCarTripMapperTest {

    private val mapper = GuidanceCarTripMapper(nowMillis = { FIXED_NOW_MILLIS })

    @Test
    fun `Guiding は目的地と次案内 step を持つ Trip に変換される`() {
        val maneuver = buildManeuverCallout()
        val state = buildGuidingState(maneuver)

        val trip = mapper.toTrip(state)
        val destination = trip.destinations.single()
        val destinationEstimate = trip.destinationTravelEstimates.single()
        val step = trip.steps.single()
        val stepEstimate = trip.stepTravelEstimates.single()

        assertEquals(expected = DESTINATION_NAME, actual = destination.name.toString())
        assertEquals(expected = CURRENT_ROAD_NAME, actual = trip.currentRoad.toString())
        assertEquals(expected = Maneuver.TYPE_TURN_NORMAL_LEFT, actual = step.maneuver?.type)
        assertEquals(expected = "霞が関 左折です", actual = step.cue.toString())
        assertEquals(expected = CURRENT_ROAD_NAME, actual = step.road.toString())
        assertEquals(expected = REMAINING_SECONDS.toLong(), actual = destinationEstimate.remainingTimeSeconds)
        assertEquals(expected = Distance.UNIT_KILOMETERS_P1, actual = destinationEstimate.remainingDistance?.displayUnit)
        assertEquals(expected = 500.0, actual = stepEstimate.remainingDistance?.displayDistance)
        assertEquals(expected = 50L, actual = stepEstimate.remainingTimeSeconds)
    }

    @Test
    fun `左側通行向けの既定 maneuver type に変換される`() {
        assertManeuverType(
            expectedType = Maneuver.TYPE_ROUNDABOUT_ENTER_CW,
            maneuverType = ManeuverType.ROUNDABOUT,
            maneuverModifier = ManeuverModifier.STRAIGHT,
        )
        assertManeuverType(
            expectedType = Maneuver.TYPE_U_TURN_RIGHT,
            maneuverType = ManeuverType.TURN,
            maneuverModifier = ManeuverModifier.UTURN,
        )
        assertManeuverType(
            expectedType = Maneuver.TYPE_ON_RAMP_U_TURN_RIGHT,
            maneuverType = ManeuverType.ON_RAMP,
            maneuverModifier = ManeuverModifier.UTURN,
        )
        assertManeuverType(
            expectedType = Maneuver.TYPE_U_TURN_RIGHT,
            maneuverType = ManeuverType.OFF_RAMP,
            maneuverModifier = ManeuverModifier.UTURN,
        )
        assertManeuverType(
            expectedType = Maneuver.TYPE_U_TURN_RIGHT,
            maneuverType = ManeuverType.FORK,
            maneuverModifier = ManeuverModifier.UTURN,
        )
        assertManeuverType(
            expectedType = Maneuver.TYPE_U_TURN_RIGHT,
            maneuverType = ManeuverType.UTURN,
            maneuverModifier = ManeuverModifier.STRAIGHT,
        )
        assertManeuverType(
            expectedType = Maneuver.TYPE_U_TURN_LEFT,
            maneuverType = ManeuverType.UTURN,
            maneuverModifier = ManeuverModifier.LEFT,
        )
    }

    @Test
    fun `step ETA は目的地 ETA を超えない`() {
        val maneuver = buildManeuverCallout(
            distanceToManeuverMeters = REMAINING_METERS * 2,
        )
        val state = buildGuidingState(maneuver)

        val trip = mapper.toTrip(state)
        val destinationEstimate = trip.destinationTravelEstimates.single()
        val stepEstimate = trip.stepTravelEstimates.single()

        assertEquals(
            expected = destinationEstimate.remainingTimeSeconds,
            actual = stepEstimate.remainingTimeSeconds,
        )
    }

    @Test
    fun `Rerouting は loading Trip に変換される`() {
        val state = GuidanceState.Rerouting(
            previousRoute = buildRoute(),
            previousProgress = buildProgress(),
        )

        val trip = mapper.toLoadingTrip(state)

        assertTrue(trip.isLoading)
        assertEquals(expected = CURRENT_ROAD_NAME, actual = trip.currentRoad.toString())
        assertTrue(trip.steps.isEmpty())
        assertTrue(trip.stepTravelEstimates.isEmpty())
    }

    private fun assertManeuverType(
        expectedType: Int,
        maneuverType: ManeuverType,
        maneuverModifier: ManeuverModifier,
    ) {
        val maneuver = buildManeuverCallout(
            type = maneuverType,
            modifier = maneuverModifier,
        )
        val state = buildGuidingState(maneuver)
        val trip = mapper.toTrip(state)
        val step = trip.steps.single()

        assertEquals(expected = expectedType, actual = step.maneuver?.type)
    }

    private fun buildGuidingState(
        maneuver: ManeuverCallout,
        progress: GuidanceProgress = buildProgress(),
    ): GuidanceState.Guiding {
        return GuidanceState.Guiding(
            route = buildRoute(),
            progress = progress,
            presentation = GuidancePresentation(
                nextManeuver = maneuver,
                followupManeuver = null,
                banner = null,
                listItems = persistentListOf(),
            ),
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
            distanceMeters = REMAINING_METERS.toDouble(),
            durationSeconds = REMAINING_SECONDS.toDouble(),
            steps = persistentListOf(),
            routeWaypoints = persistentListOf(
                RouteWaypoint.CurrentLocation(
                    latitude = origin.latitude,
                    longitude = origin.longitude,
                ),
                RouteWaypoint.Place(
                    name = DESTINATION_NAME,
                    latitude = destination.latitude,
                    longitude = destination.longitude,
                ),
            ),
        )
    }

    private fun buildProgress(
        distanceRemainingMeters: Int = REMAINING_METERS,
        durationRemainingSeconds: Int = REMAINING_SECONDS,
    ): GuidanceProgress {
        val location = RoutePoint(
            latitude = 35.0,
            longitude = 139.0,
        )

        return GuidanceProgress(
            distanceRemainingMeters = distanceRemainingMeters,
            durationRemainingSeconds = durationRemainingSeconds,
            etaEpochMillis = FIXED_NOW_MILLIS + durationRemainingSeconds * MILLIS_PER_SECOND,
            traveledMeters = 0,
            elapsedSeconds = 0,
            currentCumulativeMeters = 0.0,
            snappedLocation = location,
            bearingDegrees = 0f,
            observedLocation = null,
            observedBearingDegrees = null,
            observedAccuracyMeters = null,
            locationTimestampMillis = FIXED_NOW_MILLIS,
            locationElapsedRealtimeNanos = null,
            vehicleSpeedMps = null,
            currentRoadName = CURRENT_ROAD_NAME,
            currentRoadClass = RoadClass.ORDINARY,
            currentSpeedLimitKmh = null,
            routeMatchState = RouteMatchState.ON_ROUTE,
            positionSource = VehiclePositionSource.OBSERVED,
            projectionErrorMeters = null,
        )
    }

    private fun buildManeuverCallout(
        type: ManeuverType = ManeuverType.TURN,
        modifier: ManeuverModifier = ManeuverModifier.LEFT,
        distanceToManeuverMeters: Int = 500,
    ): ManeuverCallout {
        val location = RoutePoint(
            latitude = 35.05,
            longitude = 139.05,
        )

        return ManeuverCallout(
            type = type,
            modifier = modifier,
            location = location,
            geometryDistanceFromStartMeters = 500.0,
            distanceToManeuverMeters = distanceToManeuverMeters,
            intersectionName = "霞が関",
            exitNumber = null,
            guidancePointIndex = 0,
        )
    }

    /** テスト用固定値。 */
    private companion object {
        /** 現在時刻。 */
        const val FIXED_NOW_MILLIS = 10_000L

        /** 秒からミリ秒に変換する係数。 */
        const val MILLIS_PER_SECOND = 1000L

        /** テスト目的地名。 */
        const val DESTINATION_NAME = "東京タワー"

        /** テスト現在道路名。 */
        const val CURRENT_ROAD_NAME = "首都高速"

        /** テスト残距離。 */
        const val REMAINING_METERS = 6_000

        /** テスト残時間。 */
        const val REMAINING_SECONDS = 600
    }
}
