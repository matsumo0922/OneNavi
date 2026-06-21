package me.matsumo.onenavi.core.navigation.voice.selector

import androidx.compose.runtime.Immutable

/**
 * 発話判定 1 回ぶんの位置入力。実 tick (GPS 更新) のうち、発話レイヤが必要とする最小情報に絞ったもの。
 *
 * 発話トリガは現在地スカラと段の距離窓 (MIDDLE) / 到達リードタイム (FINAL) で判定する位置依存の純粋判定で、
 * 前 tick 値は持たない。一度きりの発話保証や距離違い代替候補の消費は
 * [me.matsumo.onenavi.core.navigation.voice.suppression.VoiceAnnouncementSpeechState] の既処理マークが担う。
 * snapshot から本型への変換は scheduler (Phase 3) が担い、発話レイヤを route 追従の実装詳細から切り離す。
 *
 * @property currentCumulativeMeters 現 tick の geometry 累積距離 (m)
 * @property speedMetersPerSecond 自車速度 (m/s)。取得できない場合は null
 * @property canAnnounce route 一致状態が発話可能か。OFF_ROUTE_CONFIRMED 等では false
 * @property canCommitPassedTargets 通過済み案内地点を恒久的に記録してよいか
 */
@Immutable
internal data class VoiceTick(
    val currentCumulativeMeters: Double,
    val speedMetersPerSecond: Double?,
    val canAnnounce: Boolean,
    val canCommitPassedTargets: Boolean,
)
