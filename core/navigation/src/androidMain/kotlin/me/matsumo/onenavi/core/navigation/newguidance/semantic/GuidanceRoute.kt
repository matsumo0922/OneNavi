package me.matsumo.onenavi.core.navigation.newguidance.semantic

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList

/**
 * 1 ルート分の案内計画 (semantic 層)。
 *
 * attach 時に 1 回だけ構築する位置非依存・不変のモデル。tick ごとの進捗は持たない
 * (それは progress 層の責務)。
 *
 * @property totalDistanceMeters ルート全長 (m)。
 * @property totalDurationSeconds ルート全体の所要時間 (秒)。
 * @property tollTotalYen 料金合計 (円)。無料・不明なら null。
 * @property events ルート上の案内イベント (geometry 距離の昇順)。
 */
@Immutable
data class GuidanceRoute(
    val totalDistanceMeters: Double,
    val totalDurationSeconds: Int,
    val tollTotalYen: Int?,
    val events: ImmutableList<GuidanceEvent>,
)
