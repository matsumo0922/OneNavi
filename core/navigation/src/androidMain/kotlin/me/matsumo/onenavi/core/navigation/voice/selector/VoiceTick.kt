package me.matsumo.onenavi.core.navigation.voice.selector

import androidx.compose.runtime.Immutable

/**
 * 発話判定 1 回ぶんの位置入力。実 tick (GPS 更新) のうち、発話レイヤが必要とする最小情報に絞ったもの。
 *
 * geometry 累積距離は前 tick と現 tick の 2 点を持ち、中間段の越え判定を区間 (previous, current] で
 * 行えるようにする。snapshot から本型への変換は scheduler (Phase 3) が担い、発話レイヤを
 * route 追従の実装詳細から切り離す。
 *
 * @property previousCumulativeMeters 前 tick の geometry 累積距離 (m)。attach 直後の初回は 0.0 を渡す
 * @property currentCumulativeMeters 現 tick の geometry 累積距離 (m)
 * @property speedMetersPerSecond 自車速度 (m/s)。取得できない場合は null
 * @property isRouteUsable route 一致状態が発話可能か。OFF_ROUTE_CONFIRMED 等では false
 */
@Immutable
internal data class VoiceTick(
    val previousCumulativeMeters: Double,
    val currentCumulativeMeters: Double,
    val speedMetersPerSecond: Double?,
    val isRouteUsable: Boolean,
)
