package me.matsumo.onenavi.core.navigation.newguidance

import kotlinx.collections.immutable.persistentListOf
import me.matsumo.onenavi.core.datasource.location.UserLocation
import me.matsumo.onenavi.core.model.ManeuverModifier
import me.matsumo.onenavi.core.model.ManeuverType
import me.matsumo.onenavi.core.model.RoadClass
import me.matsumo.onenavi.core.model.RoutePoint
import me.matsumo.onenavi.core.navigation.extnav.ExtNavProgressSnapshot
import me.matsumo.onenavi.core.navigation.newguidance.model.GuidanceProgress
import me.matsumo.onenavi.core.navigation.newguidance.model.RouteMatchState
import me.matsumo.onenavi.core.navigation.newguidance.model.VehiclePositionSource
import me.matsumo.onenavi.core.navigation.newguidance.presentation.GuidancePresentation
import me.matsumo.onenavi.core.navigation.newguidance.presentation.ManeuverBanner
import me.matsumo.onenavi.core.navigation.newguidance.presentation.ManeuverCallout
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [GuidanceRoadClassOverride] の unit test。
 */
class GuidanceRoadClassOverrideTest {

    @Test
    fun `新鮮で近い補正はprogressとbannerの道路種別へ反映する`() {
        val snapshot = buildSnapshot(roadClass = RoadClass.HIGHWAY)
        val override = RoadClassOverride(
            roadClass = RoadClass.ORDINARY,
            coordinate = RoutePoint(latitude = 35.0, longitude = 139.0),
            updatedAtMillis = 1_000L,
        )

        val adjusted = GuidanceRoadClassOverride.apply(
            snapshot = snapshot,
            override = override,
            nowMillis = 2_000L,
        )

        assertEquals(RoadClass.ORDINARY, adjusted.progress.currentRoadClass)
        assertEquals(RoadClass.ORDINARY, adjusted.presentation.banner?.roadClass)
    }

    @Test
    fun `古い補正は反映しない`() {
        val snapshot = buildSnapshot(roadClass = RoadClass.HIGHWAY)
        val override = RoadClassOverride(
            roadClass = RoadClass.ORDINARY,
            coordinate = RoutePoint(latitude = 35.0, longitude = 139.0),
            updatedAtMillis = 1_000L,
        )

        val adjusted = GuidanceRoadClassOverride.apply(
            snapshot = snapshot,
            override = override,
            nowMillis = 20_000L,
        )

        assertEquals(RoadClass.HIGHWAY, adjusted.progress.currentRoadClass)
        assertEquals(RoadClass.HIGHWAY, adjusted.presentation.banner?.roadClass)
    }

    private fun buildSnapshot(roadClass: RoadClass): ExtNavProgressSnapshot {
        val point = RoutePoint(latitude = 35.0, longitude = 139.0)
        val progress = GuidanceProgress(
            distanceRemainingMeters = 100,
            durationRemainingSeconds = 30,
            etaEpochMillis = 0L,
            traveledMeters = 0,
            elapsedSeconds = 0,
            currentCumulativeMeters = 0.0,
            snappedLocation = point,
            bearingDegrees = 0f,
            observedLocation = point,
            observedBearingDegrees = null,
            observedAccuracyMeters = 3f,
            locationTimestampMillis = 1_000L,
            locationElapsedRealtimeNanos = 0L,
            vehicleSpeedMps = null,
            currentRoadName = null,
            currentRoadClass = roadClass,
            currentSpeedLimitKmh = null,
            routeMatchState = RouteMatchState.ON_ROUTE,
            positionSource = VehiclePositionSource.OBSERVED,
            projectionErrorMeters = null,
        )
        val callout = ManeuverCallout(
            type = ManeuverType.TURN,
            modifier = ManeuverModifier.RIGHT,
            location = point,
            geometryDistanceFromStartMeters = 50.0,
            distanceToManeuverMeters = 50,
            intersectionName = null,
            exitNumber = null,
            guidancePointIndex = 0,
        )
        return ExtNavProgressSnapshot(
            progress = progress,
            presentation = GuidancePresentation(
                nextManeuver = callout,
                followupManeuver = null,
                banner = ManeuverBanner(
                    primary = callout,
                    secondaryLabel = null,
                    signpostImageKey = null,
                    roadClass = roadClass,
                    followup = null,
                    support = null,
                    hasMoreEvents = false,
                ),
                listItems = persistentListOf(),
            ),
            rawLocation = UserLocation(
                latitude = point.latitude,
                longitude = point.longitude,
                bearingDegrees = null,
                speedMps = null,
                accuracyMeters = 3f,
                timestampMillis = 1_000L,
                elapsedRealtimeNanos = 0L,
            ),
            currentCumulativeMeters = 0.0,
            distanceRemainingMeters = 100.0,
            matchedSegmentIndex = 0,
            projectionErrorMeters = null,
            locationTimestampMillis = 1_000L,
            vehicleSpeedMps = null,
            routeMatchState = RouteMatchState.ON_ROUTE,
            positionSource = VehiclePositionSource.OBSERVED,
            isOffRouteCandidate = false,
            nextGuidancePointIndex = 0,
            headingDegrees = null,
        )
    }
}
