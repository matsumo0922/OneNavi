package me.matsumo.onenavi.core.navigation.newguidance.model

import androidx.compose.runtime.Immutable

/**
 * Guidance 期 (案内中) の状態。spec/24 §8 の state machine に対応する。
 *
 * - [Idle]: 案内停止中 / Preview の前後
 * - [Guiding]: 通常案内中。`activeChunkIndex` が現在 SDK に投入している RefinedChunk
 * - [AdvancingChunk]: 80% 閾値を超えて次 chunk に切替中の過渡状態
 * - [Rerouting]: 逸脱検知 → 外部ナビ API ライブラリで再探索中
 * - [Arrived]: 目的地到達
 * - [Failed]: chunk 切替が retry 上限を超えた等の致命的失敗
 */
@Immutable
sealed interface GuidanceState {

    data object Idle : GuidanceState

    @Immutable
    data class Guiding(val activeChunkIndex: Int) : GuidanceState

    @Immutable
    data class AdvancingChunk(val from: Int) : GuidanceState

    data object Rerouting : GuidanceState

    data object Arrived : GuidanceState

    @Immutable
    data class Failed(val message: String) : GuidanceState
}
