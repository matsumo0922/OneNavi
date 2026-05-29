package me.matsumo.onenavi.core.navigation.voice.selector

import androidx.compose.runtime.Immutable

/**
 * 発話判定 1 回ぶんの位置入力。実 tick (GPS 更新) のうち、発話レイヤが必要とする最小情報に絞ったもの。
 *
 * 発話トリガは「現在地がトリガ距離に到達したか」の level 判定で行い、一度きりの発話保証は
 * [me.matsumo.onenavi.core.navigation.voice.suppression.VoiceAnnouncementSpeechState] の既処理マークが担う。
 * これにより同 tick で複数段を跨いでも、選ばれなかった段が次 tick で再評価される (前 tick 値は持たない)。
 * snapshot から本型への変換は scheduler (Phase 3) が担い、発話レイヤを route 追従の実装詳細から切り離す。
 *
 * @property currentCumulativeMeters 現 tick の geometry 累積距離 (m)
 * @property speedMetersPerSecond 自車速度 (m/s)。取得できない場合は null
 * @property isRouteUsable route 一致状態が発話可能か。OFF_ROUTE_CONFIRMED 等では false
 */
@Immutable
internal data class VoiceTick(
    val currentCumulativeMeters: Double,
    val speedMetersPerSecond: Double?,
    val isRouteUsable: Boolean,
)
