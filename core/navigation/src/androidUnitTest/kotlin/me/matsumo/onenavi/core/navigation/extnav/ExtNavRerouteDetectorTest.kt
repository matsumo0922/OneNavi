package me.matsumo.onenavi.core.navigation.extnav

import kotlinx.collections.immutable.persistentListOf
import me.matsumo.onenavi.core.datasource.location.UserLocation
import me.matsumo.onenavi.core.model.RoadClass
import me.matsumo.onenavi.core.model.RouteDetail
import me.matsumo.onenavi.core.model.RoutePoint
import me.matsumo.onenavi.core.navigation.newguidance.model.GuidanceProgress
import me.matsumo.onenavi.core.navigation.newguidance.model.RouteMatchState
import me.matsumo.onenavi.core.navigation.newguidance.model.VehiclePositionSource
import me.matsumo.onenavi.core.navigation.newguidance.presentation.GuidancePresentation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * [ExtNavRerouteDetector] のウォームアップ判定テスト。
 */
class ExtNavRerouteDetectorTest {

    @Test
    fun `attach後5秒未満の離脱確定はリルートしない`() {
        val detector = ExtNavRerouteDetector()
        detector.attach(buildRoute())

        detector.onSnapshot(buildOffRouteSnapshot(timestampMillis = FIRST_SNAPSHOT_MILLIS))
        val actual = detector.onSnapshot(
            buildOffRouteSnapshot(
                timestampMillis = FIRST_SNAPSHOT_MILLIS + REROUTE_WARMUP_MILLIS - 1L,
            ),
        )

        assertSame(ExtNavRerouteDecision.None, actual)
    }

    @Test
    fun `attach後5秒経過した離脱確定はリルートする`() {
        val detector = ExtNavRerouteDetector()
        detector.attach(buildRoute())

        detector.onSnapshot(buildOffRouteSnapshot(timestampMillis = FIRST_SNAPSHOT_MILLIS))
        val actual = detector.onSnapshot(
            buildOffRouteSnapshot(
                timestampMillis = FIRST_SNAPSHOT_MILLIS + REROUTE_WARMUP_MILLIS,
            ),
        )

        val request = assertIs<ExtNavRerouteDecision.Request>(actual)
        assertEquals(ExtNavRerouteReason.OffRoute, request.reason)
    }

    private fun buildOffRouteSnapshot(timestampMillis: Long): ExtNavProgressSnapshot {
        val rawLocation = UserLocation(
            latitude = ORIGIN_LATITUDE,
            longitude = ORIGIN_LONGITUDE + OFF_ROUTE_LONGITUDE_DELTA,
            bearingDegrees = 90f,
            speedMps = 10f,
            accuracyMeters = 5f,
            timestampMillis = timestampMillis,
            elapsedRealtimeNanos = timestampMillis * NANOS_PER_MILLIS,
        )

        return ExtNavProgressSnapshot(
            progress = buildProgress(timestampMillis = timestampMillis),
            presentation = GuidancePresentation.Empty,
            rawLocation = rawLocation,
            currentCumulativeMeters = CURRENT_CUMULATIVE_METERS,
            distanceRemainingMeters = DISTANCE_REMAINING_METERS,
            matchedSegmentIndex = 0,
            projectionErrorMeters = PROJECTION_ERROR_METERS,
            locationTimestampMillis = timestampMillis,
            vehicleSpeedMps = rawLocation.speedMps,
            routeMatchState = RouteMatchState.OFF_ROUTE_CONFIRMED,
            positionSource = VehiclePositionSource.OBSERVED,
            isOffRouteCandidate = true,
            nextGuidancePointIndex = null,
            headingDegrees = rawLocation.bearingDegrees,
        )
    }

    private fun buildProgress(timestampMillis: Long): GuidanceProgress = GuidanceProgress(
        distanceRemainingMeters = DISTANCE_REMAINING_METERS.toInt(),
        durationRemainingSeconds = DURATION_REMAINING_SECONDS,
        etaEpochMillis = timestampMillis + DURATION_REMAINING_SECONDS * MILLIS_PER_SECOND,
        traveledMeters = CURRENT_CUMULATIVE_METERS.toInt(),
        elapsedSeconds = 0,
        currentCumulativeMeters = CURRENT_CUMULATIVE_METERS,
        snappedLocation = RoutePoint(
            latitude = ORIGIN_LATITUDE,
            longitude = ORIGIN_LONGITUDE,
        ),
        bearingDegrees = 90f,
        observedLocation = RoutePoint(
            latitude = ORIGIN_LATITUDE,
            longitude = ORIGIN_LONGITUDE + OFF_ROUTE_LONGITUDE_DELTA,
        ),
        observedBearingDegrees = 90f,
        observedAccuracyMeters = 5f,
        locationTimestampMillis = timestampMillis,
        locationElapsedRealtimeNanos = timestampMillis * NANOS_PER_MILLIS,
        vehicleSpeedMps = 10f,
        currentRoadName = null,
        currentRoadClass = RoadClass.ORDINARY,
        currentSpeedLimitKmh = null,
        routeMatchState = RouteMatchState.OFF_ROUTE_CONFIRMED,
        positionSource = VehiclePositionSource.OBSERVED,
        projectionErrorMeters = PROJECTION_ERROR_METERS,
    )

    private fun buildRoute(): RouteDetail {
        val origin = RoutePoint(
            latitude = ORIGIN_LATITUDE,
            longitude = ORIGIN_LONGITUDE,
        )
        val destination = RoutePoint(
            latitude = ORIGIN_LATITUDE,
            longitude = ORIGIN_LONGITUDE + ROUTE_LONGITUDE_DELTA,
        )

        return RouteDetail(
            id = "reroute-detector-test",
            origin = origin,
            destination = destination,
            intermediateWaypoints = persistentListOf(),
            geometry = persistentListOf(origin, destination),
            distanceMeters = DISTANCE_REMAINING_METERS,
            durationSeconds = DURATION_REMAINING_SECONDS.toDouble(),
            steps = persistentListOf(),
        )
    }

    /** テスト用の固定値。 */
    private companion object {

        /** 初回 snapshot の時刻。 */
        const val FIRST_SNAPSHOT_MILLIS: Long = 1_000L

        /** Step 1 で採用したリルート抑止時間。 */
        const val REROUTE_WARMUP_MILLIS: Long = 5_000L

        /** ミリ秒からナノ秒への変換係数。 */
        const val NANOS_PER_MILLIS: Long = 1_000_000L

        /** 秒からミリ秒への変換係数。 */
        const val MILLIS_PER_SECOND: Long = 1_000L

        /** テストルートの始点緯度。 */
        const val ORIGIN_LATITUDE: Double = 35.0

        /** テストルートの始点経度。 */
        const val ORIGIN_LONGITUDE: Double = 139.0

        /** テストルートの経度方向差分。 */
        const val ROUTE_LONGITUDE_DELTA: Double = 0.01

        /** 生位置をルートから少し外すための経度方向差分。 */
        const val OFF_ROUTE_LONGITUDE_DELTA: Double = 0.001

        /** snapshot のルート上累積距離。 */
        const val CURRENT_CUMULATIVE_METERS: Double = 120.0

        /** snapshot の残距離。 */
        const val DISTANCE_REMAINING_METERS: Double = 800.0

        /** snapshot の残所要時間。 */
        const val DURATION_REMAINING_SECONDS: Int = 120

        /** snapshot の投影誤差。 */
        const val PROJECTION_ERROR_METERS: Double = 45.0
    }
}
