package me.matsumo.onenavi.feature.map.location

import me.matsumo.onenavi.core.model.RoutePoint
import me.matsumo.onenavi.feature.map.state.VehicleLocationSource
import me.matsumo.onenavi.feature.map.state.VehicleLocationState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@Suppress("NonAsciiCharacters")
class VehicleLocationStabilizerTest {

    @Test
    fun 古い初期位置は破棄する() {
        val stabilizer = VehicleLocationStabilizer(currentTimeMillis = { NOW_MILLIS })

        val actual = stabilizer.stabilize(
            locationState(
                latitude = TOKYO_LATITUDE,
                longitude = TOKYO_LONGITUDE,
                timestampMillis = NOW_MILLIS - STALE_AGE_MILLIS,
            ),
        )

        assertNull(actual)
    }

    @Test
    fun 静止中の位置とbearingは前回採用値を維持する() {
        val stabilizer = VehicleLocationStabilizer(currentTimeMillis = { NOW_MILLIS })
        val first = stabilizer.stabilize(
            locationState(
                latitude = TOKYO_LATITUDE,
                longitude = TOKYO_LONGITUDE,
                bearingDegrees = 45f,
                speedMps = MOVING_SPEED_MPS,
            ),
        )

        val actual = stabilizer.stabilize(
            locationState(
                latitude = TOKYO_LATITUDE + STATIC_NOISE_LATITUDE_DELTA,
                longitude = TOKYO_LONGITUDE,
                bearingDegrees = 180f,
                speedMps = 0f,
                timestampMillis = NOW_MILLIS + ONE_SECOND_MILLIS,
                elapsedRealtimeNanos = ONE_SECOND_NANOS,
            ),
        )

        assertNotNull(first)
        assertNotNull(actual)
        assertEquals(first.location, actual.location)
        assertEquals(first.bearingDegrees, actual.bearingDegrees)
        assertEquals(0f, actual.speedMps)
    }

    @Test
    fun 単発の遠距離外れ値は破棄する() {
        val stabilizer = VehicleLocationStabilizer(currentTimeMillis = { NOW_MILLIS })

        stabilizer.stabilize(
            locationState(
                latitude = TOKYO_LATITUDE,
                longitude = TOKYO_LONGITUDE,
                speedMps = MOVING_SPEED_MPS,
            ),
        )

        val actual = stabilizer.stabilize(
            locationState(
                latitude = OSAKA_LATITUDE,
                longitude = OSAKA_LONGITUDE,
                speedMps = 0f,
                timestampMillis = NOW_MILLIS + ONE_SECOND_MILLIS,
                elapsedRealtimeNanos = ONE_SECOND_NANOS,
            ),
        )

        assertNull(actual)
    }

    @Test
    fun 遠距離位置が連続した場合は大きな移動として採用する() {
        val stabilizer = VehicleLocationStabilizer(currentTimeMillis = { NOW_MILLIS })

        stabilizer.stabilize(
            locationState(
                latitude = TOKYO_LATITUDE,
                longitude = TOKYO_LONGITUDE,
                speedMps = MOVING_SPEED_MPS,
            ),
        )

        val firstOutlier = stabilizer.stabilize(
            locationState(
                latitude = OSAKA_LATITUDE,
                longitude = OSAKA_LONGITUDE,
                bearingDegrees = 180f,
                speedMps = 0f,
                timestampMillis = NOW_MILLIS + ONE_SECOND_MILLIS,
                elapsedRealtimeNanos = ONE_SECOND_NANOS,
            ),
        )
        val secondOutlier = stabilizer.stabilize(
            locationState(
                latitude = OSAKA_LATITUDE + RELOCATION_LATITUDE_DELTA,
                longitude = OSAKA_LONGITUDE,
                bearingDegrees = 180f,
                speedMps = 0f,
                timestampMillis = NOW_MILLIS + 2 * ONE_SECOND_MILLIS,
                elapsedRealtimeNanos = 2 * ONE_SECOND_NANOS,
            ),
        )
        val actual = stabilizer.stabilize(
            locationState(
                latitude = OSAKA_LATITUDE + 2 * RELOCATION_LATITUDE_DELTA,
                longitude = OSAKA_LONGITUDE,
                bearingDegrees = 180f,
                speedMps = 0f,
                timestampMillis = NOW_MILLIS + 3 * ONE_SECOND_MILLIS,
                elapsedRealtimeNanos = 3 * ONE_SECOND_NANOS,
            ),
        )

        assertNull(firstOutlier)
        assertNull(secondOutlier)
        assertNotNull(actual)
        assertNull(actual.bearingDegrees)
    }

    private fun locationState(
        latitude: Double,
        longitude: Double,
        bearingDegrees: Float? = null,
        speedMps: Float? = null,
        accuracyMeters: Float? = DEFAULT_ACCURACY_METERS,
        timestampMillis: Long = NOW_MILLIS,
        elapsedRealtimeNanos: Long = 0L,
        source: VehicleLocationSource = VehicleLocationSource.RAW_GPS,
    ): VehicleLocationState = VehicleLocationState(
        location = RoutePoint(
            latitude = latitude,
            longitude = longitude,
        ),
        bearingDegrees = bearingDegrees,
        accuracyMeters = accuracyMeters,
        timestampMillis = timestampMillis,
        elapsedRealtimeNanos = elapsedRealtimeNanos,
        speedMps = speedMps,
        routeProgressMeters = null,
        source = source,
        routeMatchState = null,
        projectionErrorMeters = null,
    )

    private companion object {

        const val NOW_MILLIS = 1_000_000L
        const val STALE_AGE_MILLIS = 5 * 60 * 1_000L
        const val ONE_SECOND_MILLIS = 1_000L
        const val ONE_SECOND_NANOS = 1_000_000_000L
        const val DEFAULT_ACCURACY_METERS = 5f
        const val MOVING_SPEED_MPS = 8f
        const val TOKYO_LATITUDE = 35.681236
        const val TOKYO_LONGITUDE = 139.767125
        const val OSAKA_LATITUDE = 34.702485
        const val OSAKA_LONGITUDE = 135.495951
        const val STATIC_NOISE_LATITUDE_DELTA = 0.00001
        const val RELOCATION_LATITUDE_DELTA = 0.00002
    }
}
