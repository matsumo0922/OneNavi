package me.matsumo.onenavi.feature.map.state

import me.matsumo.onenavi.core.model.RoutePoint
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [VehiclePoseEstimator] の表示位置補間テスト。
 */
@Suppress("NonAsciiCharacters")
class VehiclePoseEstimatorTest {

    @Test
    fun 自由位置の小さな移動は即時反映しない() {
        val estimator = VehiclePoseEstimator()
        val nextLocation = MapGeodesy.destinationPoint(
            origin = START_LOCATION,
            bearingDegrees = EAST_BEARING_DEGREES,
            distanceMeters = SMALL_MOVE_METERS,
        )

        estimator.updateAndEstimateAtNextSampleTime()
        estimator.updateSample(
            sample = vehicleLocationState(
                location = nextLocation,
                elapsedRealtimeNanos = ONE_SECOND_NANOS,
            ),
            routeKey = null,
            routeGeometry = emptyList(),
            nowElapsedRealtimeNanos = ONE_SECOND_NANOS,
        )

        val actual = assertNotNull(estimator.estimate(nowElapsedRealtimeNanos = ONE_SECOND_NANOS))

        assertDistanceLessThan(
            expectedLocation = START_LOCATION,
            actualLocation = actual.location,
            thresholdMeters = SNAP_TOLERANCE_METERS,
        )
        assertDistanceGreaterThan(
            expectedLocation = nextLocation,
            actualLocation = actual.location,
            thresholdMeters = SNAP_TOLERANCE_METERS,
        )
    }

    @Test
    fun 自由位置が大きく移動した場合は補間せず即時反映する() {
        val estimator = VehiclePoseEstimator()
        val nextLocation = MapGeodesy.destinationPoint(
            origin = START_LOCATION,
            bearingDegrees = EAST_BEARING_DEGREES,
            distanceMeters = LARGE_MOVE_METERS,
        )

        estimator.updateAndEstimateAtNextSampleTime()
        estimator.updateSample(
            sample = vehicleLocationState(
                location = nextLocation,
                elapsedRealtimeNanos = ONE_SECOND_NANOS,
            ),
            routeKey = null,
            routeGeometry = emptyList(),
            nowElapsedRealtimeNanos = ONE_SECOND_NANOS,
        )

        val actual = assertNotNull(estimator.estimate(nowElapsedRealtimeNanos = ONE_SECOND_NANOS))

        assertDistanceLessThan(
            expectedLocation = nextLocation,
            actualLocation = actual.location,
            thresholdMeters = SNAP_TOLERANCE_METERS,
        )
    }

    @Test
    fun route進捗が大きく前進した場合は補間せず即時反映する() {
        val estimator = VehiclePoseEstimator()
        val routeGeometry = listOf(
            START_LOCATION,
            MapGeodesy.destinationPoint(
                origin = START_LOCATION,
                bearingDegrees = EAST_BEARING_DEGREES,
                distanceMeters = ROUTE_TOTAL_METERS,
            ),
        )
        val nextLocation = MapGeodesy.destinationPoint(
            origin = START_LOCATION,
            bearingDegrees = EAST_BEARING_DEGREES,
            distanceMeters = LARGE_ROUTE_PROGRESS_METERS,
        )

        estimator.updateSample(
            sample = vehicleLocationState(
                location = START_LOCATION,
                elapsedRealtimeNanos = START_NANOS,
                routeProgressMeters = START_ROUTE_PROGRESS_METERS,
            ),
            routeKey = ROUTE_KEY,
            routeGeometry = routeGeometry,
            nowElapsedRealtimeNanos = START_NANOS,
        )
        estimator.estimate(nowElapsedRealtimeNanos = START_NANOS)
        estimator.estimate(nowElapsedRealtimeNanos = ONE_SECOND_NANOS)
        estimator.updateSample(
            sample = vehicleLocationState(
                location = nextLocation,
                elapsedRealtimeNanos = ONE_SECOND_NANOS,
                routeProgressMeters = LARGE_ROUTE_PROGRESS_METERS,
            ),
            routeKey = ROUTE_KEY,
            routeGeometry = routeGeometry,
            nowElapsedRealtimeNanos = ONE_SECOND_NANOS,
        )

        val actual = assertNotNull(estimator.estimate(nowElapsedRealtimeNanos = ONE_SECOND_NANOS))

        assertDistanceLessThan(
            expectedLocation = nextLocation,
            actualLocation = actual.location,
            thresholdMeters = SNAP_TOLERANCE_METERS,
        )
    }

    private fun VehiclePoseEstimator.updateAndEstimateAtNextSampleTime() {
        updateSample(
            sample = vehicleLocationState(
                location = START_LOCATION,
                elapsedRealtimeNanos = START_NANOS,
            ),
            routeKey = null,
            routeGeometry = emptyList(),
            nowElapsedRealtimeNanos = START_NANOS,
        )
        estimate(nowElapsedRealtimeNanos = START_NANOS)
        estimate(nowElapsedRealtimeNanos = ONE_SECOND_NANOS)
    }

    private fun vehicleLocationState(
        location: RoutePoint,
        elapsedRealtimeNanos: Long,
        routeProgressMeters: Double? = null,
    ): VehicleLocationState = VehicleLocationState(
        location = location,
        bearingDegrees = null,
        accuracyMeters = DEFAULT_ACCURACY_METERS,
        timestampMillis = NOW_MILLIS + elapsedRealtimeNanos / NANOS_PER_MILLISECOND,
        elapsedRealtimeNanos = elapsedRealtimeNanos,
        speedMps = null,
        routeProgressMeters = routeProgressMeters,
        source = if (routeProgressMeters == null) {
            VehicleLocationSource.RAW_GPS
        } else {
            VehicleLocationSource.ROUTE_SNAPPED
        },
        routeMatchState = null,
        positionSource = null,
        projectionErrorMeters = null,
    )

    private fun assertDistanceLessThan(
        expectedLocation: RoutePoint,
        actualLocation: RoutePoint,
        thresholdMeters: Double,
    ) {
        val distanceMeters = MapGeodesy.haversineMeters(expectedLocation, actualLocation)

        assertTrue(
            actual = distanceMeters < thresholdMeters,
            message = "distanceMeters=$distanceMeters thresholdMeters=$thresholdMeters",
        )
    }

    private fun assertDistanceGreaterThan(
        expectedLocation: RoutePoint,
        actualLocation: RoutePoint,
        thresholdMeters: Double,
    ) {
        val distanceMeters = MapGeodesy.haversineMeters(expectedLocation, actualLocation)

        assertTrue(
            actual = distanceMeters > thresholdMeters,
            message = "distanceMeters=$distanceMeters thresholdMeters=$thresholdMeters",
        )
    }

    private companion object {

        /** テスト用の現在 wall clock 時刻。 */
        const val NOW_MILLIS = 1_000_000L

        /** monotonic clock の開始時刻。 */
        const val START_NANOS = 0L

        /** 1 秒後の monotonic clock 時刻。 */
        const val ONE_SECOND_NANOS = 1_000_000_000L

        /** ns から ms へ変換する係数。 */
        const val NANOS_PER_MILLISECOND = 1_000_000L

        /** テスト位置の水平精度。 */
        const val DEFAULT_ACCURACY_METERS = 5f

        /** 東向きの方位。 */
        const val EAST_BEARING_DEGREES = 90f

        /** 補間対象として扱う小さな移動距離。 */
        const val SMALL_MOVE_METERS = 50.0

        /** 即時反映対象として扱う大きな移動距離。 */
        const val LARGE_MOVE_METERS = 500.0

        /** route 全体のテスト距離。 */
        const val ROUTE_TOTAL_METERS = 1_000.0

        /** route 開始地点の進捗距離。 */
        const val START_ROUTE_PROGRESS_METERS = 0.0

        /** 即時反映対象として扱う route 進捗距離。 */
        const val LARGE_ROUTE_PROGRESS_METERS = 500.0

        /** 即時反映後の誤差許容距離。 */
        const val SNAP_TOLERANCE_METERS = 1.0

        /** テスト route の key。 */
        const val ROUTE_KEY = "route"

        /** テストの開始地点。 */
        val START_LOCATION = RoutePoint(
            latitude = 35.681236,
            longitude = 139.767125,
        )
    }
}
