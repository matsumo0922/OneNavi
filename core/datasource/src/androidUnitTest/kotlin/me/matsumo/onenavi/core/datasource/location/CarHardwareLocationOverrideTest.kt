package me.matsumo.onenavi.core.datasource.location

import me.matsumo.onenavi.core.common.car.CarHardwareDataStatus
import me.matsumo.onenavi.core.common.car.CarHardwareDiagnosticsSnapshot
import me.matsumo.onenavi.core.common.car.CarHardwareEnergySnapshot
import me.matsumo.onenavi.core.common.car.CarHardwareLocationPointSnapshot
import me.matsumo.onenavi.core.common.car.CarHardwareLocationSnapshot
import me.matsumo.onenavi.core.common.car.CarHardwareSpeedSnapshot
import me.matsumo.onenavi.core.common.car.CarHardwareTollCardSnapshot
import me.matsumo.onenavi.core.common.car.CarHardwareValueSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** [CarHardwareLocationOverride] の車両ハードウェア優先ロジックを検証する。 */
class CarHardwareLocationOverrideTest {

    @Test
    fun 車両位置とraw車速が成功している場合は端末位置と速度を上書きする() {
        val override = createOverride()
        val phoneLocation = phoneLocation()
        val snapshot = carHardwareSnapshot(
            location = carHardwareLocation(
                latitude = CAR_LATITUDE,
                longitude = CAR_LONGITUDE,
                speedMetersPerSecond = LOCATION_SPEED_MPS,
                accuracyMeters = null,
            ),
            rawSpeedMetersPerSecond = RAW_SPEED_MPS,
        )

        val actual = override.apply(
            phoneLocation = phoneLocation,
            snapshot = snapshot,
        )

        assertEquals(CAR_LATITUDE, actual?.location?.latitude)
        assertEquals(CAR_LONGITUDE, actual?.location?.longitude)
        assertEquals(RAW_SPEED_MPS, actual?.location?.speedMps)
        assertEquals(DEFAULT_CAR_ACCURACY_METERS, actual?.location?.accuracyMeters)
        assertEquals(VehicleSpeedSource.CAR_HARDWARE, actual?.measuredSpeedSource)
    }

    @Test
    fun 車両位置が無く車速だけ成功している場合は端末位置の速度だけ上書きする() {
        val override = createOverride()
        val phoneLocation = phoneLocation(speedMps = PHONE_SPEED_MPS)
        val snapshot = carHardwareSnapshot(rawSpeedMetersPerSecond = RAW_SPEED_MPS)

        val actual = override.apply(
            phoneLocation = phoneLocation,
            snapshot = snapshot,
        )

        assertEquals(PHONE_LATITUDE, actual?.location?.latitude)
        assertEquals(PHONE_LONGITUDE, actual?.location?.longitude)
        assertEquals(RAW_SPEED_MPS, actual?.location?.speedMps)
        assertEquals(VehicleSpeedSource.CAR_HARDWARE, actual?.measuredSpeedSource)
    }

    @Test
    fun 車両位置だけ成功している場合は端末なしでも車両位置を返す() {
        val override = createOverride()
        val snapshot = carHardwareSnapshot(
            location = carHardwareLocation(
                speedMetersPerSecond = LOCATION_SPEED_MPS,
            ),
        )

        val actual = override.apply(
            phoneLocation = null,
            snapshot = snapshot,
        )

        assertEquals(CAR_LATITUDE, actual?.location?.latitude)
        assertEquals(CAR_LONGITUDE, actual?.location?.longitude)
        assertEquals(LOCATION_SPEED_MPS, actual?.location?.speedMps)
        assertEquals(VehicleSpeedSource.CAR_HARDWARE, actual?.measuredSpeedSource)
    }

    @Test
    fun 車両値が古い場合は端末位置と速度を維持する() {
        val override = createOverride()
        val phoneLocation = phoneLocation(speedMps = PHONE_SPEED_MPS)
        val staleElapsedMillis = CURRENT_ELAPSED_REALTIME_MILLIS - STALE_AGE_MILLIS
        val snapshot = carHardwareSnapshot(
            location = carHardwareLocation(
                elapsedRealtimeNanos = staleElapsedMillis * NANOS_PER_MILLIS,
            ),
            rawSpeedMetersPerSecond = RAW_SPEED_MPS,
            speedTimestampMillis = staleElapsedMillis,
        )

        val actual = override.apply(
            phoneLocation = phoneLocation,
            snapshot = snapshot,
        )

        assertEquals(PHONE_LATITUDE, actual?.location?.latitude)
        assertEquals(PHONE_LONGITUDE, actual?.location?.longitude)
        assertEquals(PHONE_SPEED_MPS, actual?.location?.speedMps)
        assertEquals(VehicleSpeedSource.LOCATION, actual?.measuredSpeedSource)
    }

    @Test
    fun 車両値が成功以外の場合は上書きしない() {
        val override = createOverride()
        val phoneLocation = phoneLocation(speedMps = PHONE_SPEED_MPS)
        val snapshot = carHardwareSnapshot(
            location = carHardwareLocation(),
            rawSpeedMetersPerSecond = RAW_SPEED_MPS,
            locationStatus = CarHardwareDataStatus.UNAVAILABLE,
            speedStatus = CarHardwareDataStatus.UNAVAILABLE,
        )

        val actual = override.apply(
            phoneLocation = phoneLocation,
            snapshot = snapshot,
        )

        assertEquals(PHONE_LATITUDE, actual?.location?.latitude)
        assertEquals(PHONE_LONGITUDE, actual?.location?.longitude)
        assertEquals(PHONE_SPEED_MPS, actual?.location?.speedMps)
        assertEquals(VehicleSpeedSource.LOCATION, actual?.measuredSpeedSource)
    }

    @Test
    fun timestampが無い車両位置は採用しない() {
        val override = createOverride()
        val phoneLocation = phoneLocation(speedMps = PHONE_SPEED_MPS)
        val snapshot = carHardwareSnapshot(
            location = carHardwareLocation(
                locationTimeMillis = 0L,
                elapsedRealtimeNanos = 0L,
            ),
            locationTimestampMillis = 0L,
        )

        val actual = override.apply(
            phoneLocation = phoneLocation,
            snapshot = snapshot,
        )

        assertEquals(PHONE_LATITUDE, actual?.location?.latitude)
        assertEquals(PHONE_LONGITUDE, actual?.location?.longitude)
        assertEquals(PHONE_SPEED_MPS, actual?.location?.speedMps)
        assertEquals(VehicleSpeedSource.LOCATION, actual?.measuredSpeedSource)
    }

    @Test
    fun 位置にtimestampが無くてもCarValueのtimestampが新鮮なら車両位置を採用する() {
        val override = createOverride()
        val snapshot = carHardwareSnapshot(
            location = carHardwareLocation(
                locationTimeMillis = 0L,
                elapsedRealtimeNanos = 0L,
            ),
        )

        val actual = override.apply(
            phoneLocation = null,
            snapshot = snapshot,
        )

        assertEquals(CAR_LATITUDE, actual?.location?.latitude)
        assertEquals(CAR_LONGITUDE, actual?.location?.longitude)
        assertEquals(CURRENT_TIME_MILLIS, actual?.location?.timestampMillis)
    }

    @Test
    fun timestampが無い車速は採用しない() {
        val override = createOverride()
        val phoneLocation = phoneLocation(speedMps = PHONE_SPEED_MPS)
        val snapshot = carHardwareSnapshot(
            rawSpeedMetersPerSecond = RAW_SPEED_MPS,
            speedTimestampMillis = 0L,
        )

        val actual = override.apply(
            phoneLocation = phoneLocation,
            snapshot = snapshot,
        )

        assertEquals(PHONE_LATITUDE, actual?.location?.latitude)
        assertEquals(PHONE_LONGITUDE, actual?.location?.longitude)
        assertEquals(PHONE_SPEED_MPS, actual?.location?.speedMps)
        assertEquals(VehicleSpeedSource.LOCATION, actual?.measuredSpeedSource)
    }

    @Test
    fun 端末位置も車両位置も無い場合はnullを返す() {
        val override = createOverride()
        val snapshot = CarHardwareDiagnosticsSnapshot()

        val actual = override.apply(
            phoneLocation = null,
            snapshot = snapshot,
        )

        assertNull(actual)
    }

    private fun createOverride(
        currentTimeMillis: Long = CURRENT_TIME_MILLIS,
        currentElapsedRealtimeMillis: Long = CURRENT_ELAPSED_REALTIME_MILLIS,
    ): CarHardwareLocationOverride {
        return CarHardwareLocationOverride(
            currentTimeMillis = { currentTimeMillis },
            currentElapsedRealtimeMillis = { currentElapsedRealtimeMillis },
        )
    }

    private fun phoneLocation(
        speedMps: Float? = null,
    ): UserLocation {
        return UserLocation(
            latitude = PHONE_LATITUDE,
            longitude = PHONE_LONGITUDE,
            bearingDegrees = PHONE_BEARING_DEGREES,
            speedMps = speedMps,
            accuracyMeters = PHONE_ACCURACY_METERS,
            timestampMillis = CURRENT_TIME_MILLIS,
            elapsedRealtimeNanos = CURRENT_ELAPSED_REALTIME_MILLIS * NANOS_PER_MILLIS,
        )
    }

    private fun carHardwareSnapshot(
        location: CarHardwareLocationPointSnapshot? = null,
        rawSpeedMetersPerSecond: Float? = null,
        speedTimestampMillis: Long = CURRENT_ELAPSED_REALTIME_MILLIS,
        locationTimestampMillis: Long = CURRENT_ELAPSED_REALTIME_MILLIS,
        locationStatus: CarHardwareDataStatus = CarHardwareDataStatus.SUCCESS,
        speedStatus: CarHardwareDataStatus = CarHardwareDataStatus.SUCCESS,
    ): CarHardwareDiagnosticsSnapshot {
        return CarHardwareDiagnosticsSnapshot(
            speed = CarHardwareSpeedSnapshot(
                rawSpeedMetersPerSecond = carHardwareValue(
                    value = rawSpeedMetersPerSecond,
                    status = speedStatus,
                    timestampMillis = speedTimestampMillis,
                ),
                displaySpeedMetersPerSecond = CarHardwareValueSnapshot.unknown(),
                speedDisplayUnit = CarHardwareValueSnapshot.unknown(),
            ),
            tollCard = CarHardwareTollCardSnapshot.UNKNOWN,
            energy = CarHardwareEnergySnapshot.UNKNOWN,
            location = CarHardwareLocationSnapshot(
                location = carHardwareValue(
                    value = location,
                    status = locationStatus,
                    timestampMillis = locationTimestampMillis,
                ),
            ),
        )
    }

    private fun carHardwareLocation(
        latitude: Double = CAR_LATITUDE,
        longitude: Double = CAR_LONGITUDE,
        speedMetersPerSecond: Float? = null,
        accuracyMeters: Float? = CAR_ACCURACY_METERS,
        locationTimeMillis: Long = CURRENT_TIME_MILLIS,
        elapsedRealtimeNanos: Long = CURRENT_ELAPSED_REALTIME_MILLIS * NANOS_PER_MILLIS,
    ): CarHardwareLocationPointSnapshot {
        return CarHardwareLocationPointSnapshot(
            latitude = latitude,
            longitude = longitude,
            altitudeMeters = null,
            accuracyMeters = accuracyMeters,
            bearingDegrees = CAR_BEARING_DEGREES,
            speedMetersPerSecond = speedMetersPerSecond,
            provider = "car",
            locationTimeMillis = locationTimeMillis,
            elapsedRealtimeNanos = elapsedRealtimeNanos,
        )
    }

    private fun <Value> carHardwareValue(
        value: Value?,
        status: CarHardwareDataStatus,
        timestampMillis: Long,
    ): CarHardwareValueSnapshot<Value> {
        return CarHardwareValueSnapshot(
            value = value,
            status = status,
            timestampMillis = timestampMillis,
        )
    }

    /** 車両ハードウェア override テストで使う固定値。 */
    private companion object {

        /** テスト現在時刻。 */
        const val CURRENT_TIME_MILLIS = 100_000L

        /** テスト monotonic clock 時刻。 */
        const val CURRENT_ELAPSED_REALTIME_MILLIS = 50_000L

        /** stale 判定させるための age。 */
        const val STALE_AGE_MILLIS = 20_000L

        /** 1 ミリ秒あたりのナノ秒。 */
        const val NANOS_PER_MILLIS = 1_000_000L

        /** 端末位置の緯度。 */
        const val PHONE_LATITUDE = 35.0

        /** 端末位置の経度。 */
        const val PHONE_LONGITUDE = 139.0

        /** 車両位置の緯度。 */
        const val CAR_LATITUDE = 36.0

        /** 車両位置の経度。 */
        const val CAR_LONGITUDE = 140.0

        /** 端末位置の方位。 */
        const val PHONE_BEARING_DEGREES = 90f

        /** 車両位置の方位。 */
        const val CAR_BEARING_DEGREES = 180f

        /** 端末位置の水平精度。 */
        const val PHONE_ACCURACY_METERS = 5f

        /** 車両位置の水平精度。 */
        const val CAR_ACCURACY_METERS = 4f

        /** 車両位置に水平精度が無い場合の既定値。 */
        const val DEFAULT_CAR_ACCURACY_METERS = 8f

        /** 端末位置 provider が返す速度。 */
        const val PHONE_SPEED_MPS = 3f

        /** 車両位置 provider が返す速度。 */
        const val LOCATION_SPEED_MPS = 8f

        /** 車両 speed API が返す raw 速度。 */
        const val RAW_SPEED_MPS = 12.5f
    }
}
