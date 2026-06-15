package me.matsumo.onenavi.core.navigation.voice.scheduler

import me.matsumo.onenavi.core.model.RoadClass
import me.matsumo.onenavi.core.model.RoutePoint
import me.matsumo.onenavi.core.navigation.extnav.ExtNavProgressSnapshot
import me.matsumo.onenavi.core.navigation.newguidance.model.GuidanceProgress
import me.matsumo.onenavi.core.navigation.newguidance.model.RouteMatchState
import me.matsumo.onenavi.core.navigation.newguidance.model.VehiclePositionSource
import me.matsumo.onenavi.core.navigation.newguidance.presentation.GuidancePresentation
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [isUsableForVoiceAnnouncement] の route 一致状態 → 発話可否マッピングのテスト。
 */
class VoiceTickFactoryTest {

    @Test
    fun `ON_ROUTE は発話可能`() {
        assertTrue(RouteMatchState.ON_ROUTE.isUsableForVoiceAnnouncement())
    }

    @Test
    fun `OFF_ROUTE 候補と確定は発話不能`() {
        assertFalse(RouteMatchState.OFF_ROUTE_CANDIDATE.isUsableForVoiceAnnouncement())
        assertFalse(RouteMatchState.OFF_ROUTE_CONFIRMED.isUsableForVoiceAnnouncement())
    }

    @Test
    fun `DR tick は route 上でも発話不能として扱う`() {
        val factory = VoiceTickFactory()
        val tick = factory.from(buildSnapshot(positionSource = VehiclePositionSource.DEAD_RECKONING))

        assertFalse(tick.isRouteUsable)
    }

    @Test
    fun `観測 tick は route 上なら発話可能として扱う`() {
        val factory = VoiceTickFactory()
        val tick = factory.from(buildSnapshot(positionSource = VehiclePositionSource.OBSERVED))

        assertTrue(tick.isRouteUsable)
    }

    private fun buildSnapshot(positionSource: VehiclePositionSource): ExtNavProgressSnapshot {
        val point = RoutePoint(latitude = 35.0, longitude = 139.0)
        val progress = GuidanceProgress(
            distanceRemainingMeters = 1_000,
            durationRemainingSeconds = 120,
            etaEpochMillis = 120_000L,
            traveledMeters = 0,
            elapsedSeconds = 0,
            currentCumulativeMeters = 100.0,
            snappedLocation = point,
            bearingDegrees = 0f,
            observedLocation = point.takeIf { positionSource == VehiclePositionSource.OBSERVED },
            observedBearingDegrees = null,
            observedAccuracyMeters = null,
            locationTimestampMillis = 1_000L,
            locationElapsedRealtimeNanos = 1_000_000L,
            vehicleSpeedMps = 10f,
            currentRoadName = null,
            currentRoadClass = RoadClass.ORDINARY,
            currentSpeedLimitKmh = null,
            routeMatchState = RouteMatchState.ON_ROUTE,
            positionSource = positionSource,
            projectionErrorMeters = null,
        )

        return ExtNavProgressSnapshot(
            progress = progress,
            presentation = GuidancePresentation.Empty,
            rawLocation = null,
            currentCumulativeMeters = progress.currentCumulativeMeters,
            distanceRemainingMeters = progress.distanceRemainingMeters.toDouble(),
            matchedSegmentIndex = 0,
            projectionErrorMeters = null,
            locationTimestampMillis = progress.locationTimestampMillis,
            vehicleSpeedMps = progress.vehicleSpeedMps,
            routeMatchState = progress.routeMatchState,
            positionSource = positionSource,
            isOffRouteCandidate = false,
            nextGuidancePointIndex = null,
            headingDegrees = progress.bearingDegrees,
        )
    }
}
