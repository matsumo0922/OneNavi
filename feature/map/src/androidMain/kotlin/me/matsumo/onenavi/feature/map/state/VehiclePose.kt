package me.matsumo.onenavi.feature.map.state

import androidx.compose.runtime.Immutable
import me.matsumo.onenavi.core.model.RoutePoint

/**
 * 画面に描画する瞬間的な自車 pose。
 *
 * @param location 地図に表示する自車位置
 * @param bearingDegrees 自車マーカーの向き。取得できない場合は null
 */
@Immutable
data class VehiclePose(
    val location: RoutePoint,
    val bearingDegrees: Float?,
)
