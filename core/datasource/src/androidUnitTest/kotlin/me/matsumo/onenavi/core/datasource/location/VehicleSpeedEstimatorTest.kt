package me.matsumo.onenavi.core.datasource.location

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [VehicleSpeedEstimator] の速度補完と表示速度変換を検証する。
 */
@Suppress("NonAsciiCharacters")
class VehicleSpeedEstimatorTest {

    @Test
    fun provider速度を優先して表示速度へ変換する() {
        val estimator = VehicleSpeedEstimator()

        val actual = estimator.estimate(
            location(
                speedMps = 10f,
            ),
        )

        assertEquals(10f, actual.location.speedMps)
        assertEquals(36, actual.state.displaySpeedKmh)
        assertEquals(VehicleSpeedSource.LOCATION, actual.state.source)
    }

    @Test
    fun provider速度が無い場合は前回位置との差分から速度を補完する() {
        val estimator = VehicleSpeedEstimator()
        estimator.estimate(location(longitude = ORIGIN_LONGITUDE))

        val actual = estimator.estimate(
            location(
                longitude = ORIGIN_LONGITUDE + ONE_HUNDRED_METERS_LONGITUDE_DELTA,
                timestampMillis = NOW_MILLIS + TEN_SECONDS_MILLIS,
                elapsedRealtimeNanos = TEN_SECONDS_NANOS,
            ),
        )

        assertEquals(10f, actual.location.speedMps ?: 0f, absoluteTolerance = 0.1f)
        assertEquals(36, actual.state.displaySpeedKmh)
        assertEquals(VehicleSpeedSource.DERIVED_LOCATION_DELTA, actual.state.source)
    }

    @Test
    fun 差分速度が外れ値の場合は速度なしとして扱う() {
        val estimator = VehicleSpeedEstimator()
        estimator.estimate(location(longitude = ORIGIN_LONGITUDE))

        val actual = estimator.estimate(
            location(
                longitude = ORIGIN_LONGITUDE + IMPLAUSIBLE_LONGITUDE_DELTA,
                timestampMillis = NOW_MILLIS + TEN_SECONDS_MILLIS,
                elapsedRealtimeNanos = TEN_SECONDS_NANOS,
            ),
        )

        assertNull(actual.location.speedMps)
        assertNull(actual.state.displaySpeedKmh)
        assertEquals(VehicleSpeedSource.UNAVAILABLE, actual.state.source)
    }

    @Test
    fun 表示速度はEMAで平滑化する() {
        val estimator = VehicleSpeedEstimator()

        estimator.estimate(location(speedMps = 10f))
        val actual = estimator.estimate(
            location(
                speedMps = 20f,
                timestampMillis = NOW_MILLIS + TEN_SECONDS_MILLIS,
                elapsedRealtimeNanos = TEN_SECONDS_NANOS,
            ),
        )

        assertEquals(49, actual.state.displaySpeedKmh)
    }

    private fun location(
        longitude: Double = ORIGIN_LONGITUDE,
        speedMps: Float? = null,
        timestampMillis: Long = NOW_MILLIS,
        elapsedRealtimeNanos: Long? = 0L,
    ): UserLocation = UserLocation(
        latitude = ORIGIN_LATITUDE,
        longitude = longitude,
        bearingDegrees = null,
        speedMps = speedMps,
        accuracyMeters = 5f,
        timestampMillis = timestampMillis,
        elapsedRealtimeNanos = elapsedRealtimeNanos,
    )

    /** 速度推定テストで使う定数群。 */
    private companion object {

        /** テストで使う基準緯度。 */
        const val ORIGIN_LATITUDE = 0.0

        /** テストで使う基準経度。 */
        const val ORIGIN_LONGITUDE = 139.0

        /** テストで使う基準時刻。 */
        const val NOW_MILLIS = 1_000L

        /** 10 秒をミリ秒で表した値。 */
        const val TEN_SECONDS_MILLIS = 10_000L

        /** 10 秒をナノ秒で表した値。 */
        const val TEN_SECONDS_NANOS = 10_000_000_000L

        /** 赤道上でおおむね 100 m になる経度差。 */
        const val ONE_HUNDRED_METERS_LONGITUDE_DELTA = 0.0008993216

        /** 補完速度の上限を超える経度差。 */
        const val IMPLAUSIBLE_LONGITUDE_DELTA = 0.02
    }
}
