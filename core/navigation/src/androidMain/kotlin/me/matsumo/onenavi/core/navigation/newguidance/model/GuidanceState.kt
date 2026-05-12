package me.matsumo.onenavi.core.navigation.newguidance.model

import androidx.compose.runtime.Immutable

/**
 * Guidance 期 (案内中) の状態。
 *
 * - [Idle]: 案内停止中 / Preview の前後
 * - [Guiding]: 通常案内中
 * - [Rerouting]: 逸脱検知 → 外部ナビ API ライブラリで再探索中
 * - [Arrived]: 目的地到達
 * - [Failed]: 致命的失敗
 */
@Immutable
sealed interface GuidanceState {

    /** 案内停止中 / Preview の前後。 */
    data object Idle : GuidanceState

    /** 通常案内中。 */
    data object Guiding : GuidanceState

    /** 逸脱検知 → 外部ナビ API ライブラリで再探索中。 */
    data object Rerouting : GuidanceState

    /** 目的地到達。 */
    data object Arrived : GuidanceState

    /** 致命的失敗。`message` に原因を入れる。 */
    @Immutable
    data class Failed(val message: String) : GuidanceState
}
