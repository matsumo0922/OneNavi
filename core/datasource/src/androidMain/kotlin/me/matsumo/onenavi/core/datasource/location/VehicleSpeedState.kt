package me.matsumo.onenavi.core.datasource.location

import androidx.compose.runtime.Immutable

/**
 * 速度推定に使った入力元。
 */
enum class VehicleSpeedSource {
    /** 端末の位置情報 provider が返した速度。 */
    LOCATION,

    /** 車両ハードウェア API が返した速度。 */
    CAR_HARDWARE,

    /** 連続する位置 tick の座標差分から補完した速度。 */
    DERIVED_LOCATION_DELTA,

    /** 速度をまだ算出できていない状態。 */
    UNAVAILABLE,
}

/**
 * UI と案内ロジックが共有する自車速度 state。
 *
 * @param speedMps 平滑化前の自車速度。取得できない場合は null
 * @param displaySpeedKmh UI 表示向けに平滑化・丸め済みの速度。取得できない場合は null
 * @param source 速度の入力元
 * @param updatedAtMillis この速度 state の元になった位置 tick の計測時刻。取得できない場合は null
 */
@Immutable
data class VehicleSpeedState(
    val speedMps: Float? = null,
    val displaySpeedKmh: Int? = null,
    val source: VehicleSpeedSource = VehicleSpeedSource.UNAVAILABLE,
    val updatedAtMillis: Long? = null,
)
