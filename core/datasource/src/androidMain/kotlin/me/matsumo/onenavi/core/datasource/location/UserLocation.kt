package me.matsumo.onenavi.core.datasource.location

import androidx.compose.runtime.Immutable

/**
 * 位置情報プロバイダから得た 1 tick 分の生位置。
 *
 * @param latitude 緯度
 * @param longitude 経度
 * @param bearingDegrees 進行方向。取得できない場合は null
 * @param speedMps 速度。取得できない場合は null
 * @param accuracyMeters 水平精度
 * @param timestampMillis 位置情報の計測時刻
 */
@Immutable
data class UserLocation(
    val latitude: Double,
    val longitude: Double,
    val bearingDegrees: Float?,
    val speedMps: Float?,
    val accuracyMeters: Float,
    val timestampMillis: Long,
)
