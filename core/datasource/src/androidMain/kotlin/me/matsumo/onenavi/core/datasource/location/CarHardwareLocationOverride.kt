package me.matsumo.onenavi.core.datasource.location

import androidx.compose.runtime.Immutable
import me.matsumo.onenavi.core.common.car.CarHardwareDataStatus
import me.matsumo.onenavi.core.common.car.CarHardwareDiagnosticsSnapshot
import me.matsumo.onenavi.core.common.car.CarHardwareLocationPointSnapshot
import me.matsumo.onenavi.core.common.car.CarHardwareValueSnapshot

/** 車両ハードウェアから取得した位置と車速を端末位置へ重ねる。 */
internal class CarHardwareLocationOverride(
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
    private val currentElapsedRealtimeMillis: () -> Long = android.os.SystemClock::elapsedRealtime,
) {

    /**
     * 端末位置へ車両ハードウェア値を重ねた結果を返す。
     *
     * @param phoneLocation 端末側の位置。未取得の場合は null
     * @param snapshot 車両ハードウェア診断の最新 snapshot
     * @return 位置として利用できる tick。端末と車両のどちらにも位置が無い場合は null
     */
    fun apply(
        phoneLocation: UserLocation?,
        snapshot: CarHardwareDiagnosticsSnapshot,
    ): CarHardwareLocationOverrideResult? {
        val carHardwareLocation = snapshot.location.location.toFreshUserLocationOrNull()
        val carHardwareSpeedMps = snapshot.resolveCarHardwareSpeedMps(carHardwareLocation)
        val selectedLocation = carHardwareLocation ?: phoneLocation ?: return null
        val selectedSpeedMps = carHardwareSpeedMps ?: phoneLocation?.speedMps.validSpeedOrNull()
        val measuredSpeedSource = if (carHardwareSpeedMps != null) {
            VehicleSpeedSource.CAR_HARDWARE
        } else {
            VehicleSpeedSource.LOCATION
        }

        return CarHardwareLocationOverrideResult(
            location = selectedLocation.copy(speedMps = selectedSpeedMps),
            measuredSpeedSource = measuredSpeedSource,
        )
    }

    private fun CarHardwareDiagnosticsSnapshot.resolveCarHardwareSpeedMps(
        carHardwareLocation: UserLocation?,
    ): Float? {
        return speed.rawSpeedMetersPerSecond.successfulFreshValueOrNull()
            .validSpeedOrNull()
            ?: speed.displaySpeedMetersPerSecond.successfulFreshValueOrNull()
                .validSpeedOrNull()
            ?: carHardwareLocation?.speedMps.validSpeedOrNull()
    }

    private fun CarHardwareValueSnapshot<CarHardwareLocationPointSnapshot>.toFreshUserLocationOrNull(): UserLocation? {
        val pointSnapshot = successfulValueOrNull() ?: return null

        return pointSnapshot.toUserLocationOrNull(carValueTimestampMillis = timestampMillis)
    }

    private fun CarHardwareLocationPointSnapshot.toUserLocationOrNull(carValueTimestampMillis: Long): UserLocation? {
        if (!latitude.isFinite() || !longitude.isFinite()) return null
        if (!isFreshLocation(carValueTimestampMillis)) return null

        val timestampMillis = locationTimeMillis
            .takeIf { timeMillis -> timeMillis > 0L }
            ?: currentTimeMillis()
        val elapsedRealtimeNanos = elapsedRealtimeNanos
            .takeIf { elapsedNanos -> elapsedNanos > 0L }
        val accuracyMeters = accuracyMeters
            .takeIf { accuracy -> accuracy != null && accuracy.isFinite() && accuracy >= 0f }
            ?: DEFAULT_CAR_HARDWARE_ACCURACY_METERS

        return UserLocation(
            latitude = latitude,
            longitude = longitude,
            bearingDegrees = bearingDegrees.takeIf { bearing -> bearing != null && bearing.isFinite() },
            speedMps = speedMetersPerSecond.validSpeedOrNull(),
            accuracyMeters = accuracyMeters,
            timestampMillis = timestampMillis,
            elapsedRealtimeNanos = elapsedRealtimeNanos,
        )
    }

    private fun CarHardwareLocationPointSnapshot.isFreshLocation(carValueTimestampMillis: Long): Boolean {
        val elapsedNanos = elapsedRealtimeNanos

        if (elapsedNanos > 0L) {
            return isFreshLocationAge(currentElapsedRealtimeMillis() - elapsedNanos / NANOS_PER_MILLIS)
        }

        if (locationTimeMillis > 0L) {
            return isFreshLocationAge(currentTimeMillis() - locationTimeMillis)
        }

        if (carValueTimestampMillis > 0L) {
            return isFreshLocationAge(currentElapsedRealtimeMillis() - carValueTimestampMillis)
        }

        return false
    }

    private fun isFreshLocationAge(ageMillis: Long): Boolean {
        return ageMillis in 0L..MAX_CAR_HARDWARE_LOCATION_AGE_MILLIS
    }

    private fun <Value> CarHardwareValueSnapshot<Value>.successfulValueOrNull(): Value? {
        if (status != CarHardwareDataStatus.SUCCESS) return null

        return value
    }

    private fun <Value> CarHardwareValueSnapshot<Value>.successfulFreshValueOrNull(): Value? {
        if (!isFreshValue()) return null

        return successfulValueOrNull()
    }

    private fun CarHardwareValueSnapshot<*>.isFreshValue(): Boolean {
        if (timestampMillis <= 0L) return false

        val ageMillis = currentElapsedRealtimeMillis() - timestampMillis
        return ageMillis in 0L..MAX_CAR_HARDWARE_SPEED_AGE_MILLIS
    }

    private fun Float?.validSpeedOrNull(): Float? {
        val speedMps = this ?: return null
        if (!speedMps.isFinite()) return null
        if (speedMps < 0f) return null

        return speedMps
    }

    /** 車両ハードウェア値の鮮度判定に使う定数群。 */
    private companion object {

        /** 1 ミリ秒あたりのナノ秒。 */
        const val NANOS_PER_MILLIS = 1_000_000L

        /** 車両位置を端末位置より優先する最大 age。 */
        const val MAX_CAR_HARDWARE_LOCATION_AGE_MILLIS = 10_000L

        /** 車両車速を端末速度より優先する最大 age。 */
        const val MAX_CAR_HARDWARE_SPEED_AGE_MILLIS = 10_000L

        /** 車両位置が水平精度を返さない場合に使う保守的な精度。 */
        const val DEFAULT_CAR_HARDWARE_ACCURACY_METERS = 8f
    }
}

/**
 * 車両ハードウェア値を端末位置へ重ねた結果。
 *
 * @param location tracker と地図へ流す位置 tick
 * @param measuredSpeedSource 速度が provider から直接得られた場合の入力元
 */
@Immutable
internal data class CarHardwareLocationOverrideResult(
    val location: UserLocation,
    val measuredSpeedSource: VehicleSpeedSource,
)
