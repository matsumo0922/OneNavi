package me.matsumo.onenavi.core.datasource.location

import androidx.compose.runtime.Immutable
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 位置 tick から表示用の自車速度を推定する。
 *
 * provider 由来の速度を優先し、速度が無い tick では前回位置との差分から速度を補完する。
 * 表示値は急な桁揺れを抑えるため EMA で平滑化する。
 */
internal class VehicleSpeedEstimator {

    private var previousLocation: UserLocation? = null
    private var smoothedSpeedMps: Float? = null

    /**
     * 位置 tick を速度付き tick と速度 state に変換する。
     *
     * @param location provider から受け取った位置 tick
     * @return 速度を補完した位置 tick と共有速度 state
     */
    fun estimate(location: UserLocation): VehicleSpeedEstimation {
        val measuredSpeedMps = location.speedMps.validSpeedOrNull()
        val derivedSpeedMps = measuredSpeedMps ?: deriveSpeedMps(location)
        val speedSource = speedSourceFor(
            measuredSpeedMps = measuredSpeedMps,
            derivedSpeedMps = derivedSpeedMps,
        )
        val displaySpeedKmh = derivedSpeedMps?.let(::smoothDisplaySpeedKmh)

        previousLocation = location

        return VehicleSpeedEstimation(
            location = location.copy(speedMps = derivedSpeedMps),
            state = VehicleSpeedState(
                speedMps = derivedSpeedMps,
                displaySpeedKmh = displaySpeedKmh,
                source = speedSource,
                updatedAtMillis = location.timestampMillis,
            ),
        )
    }

    /**
     * 前回位置との差分から速度を補完する。
     *
     * @param location 今回の位置 tick
     * @return 補完した速度。算出できない場合は null
     */
    private fun deriveSpeedMps(location: UserLocation): Float? {
        val previous = previousLocation ?: return null
        val elapsedSeconds = previous.elapsedSecondsTo(location) ?: return null
        if (elapsedSeconds < MIN_DERIVED_SAMPLE_INTERVAL_SECONDS) return null

        val distanceMeters = haversineMeters(
            first = previous,
            second = location,
        )
        val speedMps = (distanceMeters / elapsedSeconds).toFloat()
        if (!speedMps.isFinite()) return null
        if (speedMps > MAX_DERIVED_SPEED_MPS) return null

        return speedMps.coerceAtLeast(0f)
    }

    /**
     * 表示用速度を EMA で平滑化し km/h に丸める。
     *
     * @param speedMps 今回 tick の速度
     * @return 表示用 km/h
     */
    private fun smoothDisplaySpeedKmh(speedMps: Float): Int {
        val previousSpeedMps = smoothedSpeedMps
        val nextSpeedMps = if (previousSpeedMps == null) {
            speedMps
        } else {
            previousSpeedMps + (speedMps - previousSpeedMps) * DISPLAY_SPEED_EMA_ALPHA
        }

        smoothedSpeedMps = nextSpeedMps.coerceAtLeast(0f)

        return (smoothedSpeedMps ?: 0f)
            .times(MPS_TO_KMH)
            .roundToInt()
    }

    /**
     * 速度 state の入力元を返す。
     *
     * @param measuredSpeedMps provider 由来の速度
     * @param derivedSpeedMps 差分補完後の速度
     * @return 速度入力元
     */
    private fun speedSourceFor(measuredSpeedMps: Float?, derivedSpeedMps: Float?): VehicleSpeedSource {
        if (measuredSpeedMps != null) return VehicleSpeedSource.LOCATION
        if (derivedSpeedMps != null) return VehicleSpeedSource.DERIVED_LOCATION_DELTA

        return VehicleSpeedSource.UNAVAILABLE
    }

    /**
     * 2 tick 間の経過秒を返す。
     *
     * @param next 次の tick
     * @return monotonic clock または wall clock から算出した経過秒
     */
    private fun UserLocation.elapsedSecondsTo(next: UserLocation): Double? {
        val fromElapsedRealtimeNanos = elapsedRealtimeNanos
        val toElapsedRealtimeNanos = next.elapsedRealtimeNanos

        if (fromElapsedRealtimeNanos != null && toElapsedRealtimeNanos != null) {
            val elapsedNanos = toElapsedRealtimeNanos - fromElapsedRealtimeNanos
            return elapsedNanos
                .takeIf { nanos -> nanos > 0L }
                ?.toDouble()
                ?.div(NANOS_PER_SECOND)
        }

        return (next.timestampMillis - timestampMillis)
            .takeIf { millis -> millis > 0L }
            ?.toDouble()
            ?.div(MILLIS_PER_SECOND)
    }

    /**
     * 2 tick の球面距離を返す。
     *
     * @param first 1 点目
     * @param second 2 点目
     * @return 2 点間距離 m
     */
    private fun haversineMeters(first: UserLocation, second: UserLocation): Double {
        val firstLatitudeRadians = Math.toRadians(first.latitude)
        val secondLatitudeRadians = Math.toRadians(second.latitude)
        val latitudeDeltaRadians = Math.toRadians(second.latitude - first.latitude)
        val longitudeDeltaRadians = Math.toRadians(second.longitude - first.longitude)
        val sinLatitudeDelta = sin(latitudeDeltaRadians / 2.0)
        val sinLongitudeDelta = sin(longitudeDeltaRadians / 2.0)
        val haversine = sinLatitudeDelta.pow(2.0) +
            cos(firstLatitudeRadians) * cos(secondLatitudeRadians) * sinLongitudeDelta.pow(2.0)
        val centralAngle = 2.0 * atan2(sqrt(haversine), sqrt(1.0 - haversine))

        return EARTH_RADIUS_METERS * centralAngle
    }

    /**
     * 有効な速度だけを返す。
     *
     * @return 有限で非負の速度。無効値は null
     */
    private fun Float?.validSpeedOrNull(): Float? {
        val speedMps = this ?: return null
        if (!speedMps.isFinite()) return null
        if (speedMps < 0f) return null

        return speedMps
    }

    private companion object {

        /** 1 秒あたりのナノ秒。 */
        const val NANOS_PER_SECOND = 1_000_000_000.0

        /** 1 秒あたりのミリ秒。 */
        const val MILLIS_PER_SECOND = 1_000.0

        /** m/s を km/h へ変換する係数。 */
        const val MPS_TO_KMH = 3.6f

        /** 座標差分で速度を補完する最小 tick 間隔。 */
        const val MIN_DERIVED_SAMPLE_INTERVAL_SECONDS = 0.2

        /** 速度補完で採用する最大速度。高速道路の実用域を超える外れ値は捨てる。 */
        const val MAX_DERIVED_SPEED_MPS = 70f

        /** 表示速度の EMA 係数。 */
        const val DISPLAY_SPEED_EMA_ALPHA = 0.35f

        /** 地球半径 m。 */
        const val EARTH_RADIUS_METERS = 6_371_000.0
    }
}

/**
 * 速度推定後の位置 tick と速度 state。
 *
 * @param location 速度を補完した位置 tick
 * @param state UI と案内ロジックが共有する速度 state
 */
@Immutable
internal data class VehicleSpeedEstimation(
    val location: UserLocation,
    val state: VehicleSpeedState,
)
