package me.matsumo.onenavi.core.model

import androidx.compose.runtime.Immutable

/**
 * 次のマニューバ（曲がる地点）の情報。
 * Mapbox の BannerInstructions / RouteProgress から変換される。
 *
 * @param type マニューバ種別（"turn", "fork", "merge", "on ramp", "off ramp", "arrive" 等）
 * @param modifier 方向修飾子（"left", "right", "slight left", "sharp right", "straight", "uturn" 等）
 * @param distanceMeters 次のマニューバまでの残り距離（メートル）
 * @param instruction 交差点名 / JCT 名 / 道路名
 */
@Immutable
data class ManeuverInfo(
    val type: String,
    val modifier: String?,
    val distanceMeters: Double,
    val instruction: String,
)
