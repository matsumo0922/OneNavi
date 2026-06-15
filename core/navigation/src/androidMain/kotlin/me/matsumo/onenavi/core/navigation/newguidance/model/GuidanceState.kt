package me.matsumo.onenavi.core.navigation.newguidance.model

import androidx.compose.runtime.Immutable
import me.matsumo.onenavi.core.model.RouteDetail
import me.matsumo.onenavi.core.navigation.newguidance.presentation.GuidancePresentation

/**
 * Guidance 期 (案内中) の状態。
 *
 * - [Idle]: 案内停止中 / Preview の前後
 * - [Preparing]: 案内開始準備中
 * - [Guiding]: 通常案内中
 * - [Rerouting]: 逸脱検知 → 外部ナビ API ライブラリで再探索中
 * - [Arrived]: 目的地到達
 * - [Failed]: 致命的失敗
 */
@Immutable
sealed interface GuidanceState {

    /** 案内停止中 / Preview の前後。 */
    data object Idle : GuidanceState

    /**
     * 案内開始準備中。
     *
     * @param route 準備中のルート
     * @param initialProgress 準備中に UI が読む初期進捗
     */
    @Immutable
    data class Preparing(
        val route: RouteDetail,
        val initialProgress: GuidanceProgress,
    ) : GuidanceState

    /**
     * 通常案内中。
     *
     * @param route 現在案内中のルート
     * @param progress 案内中 UI が読む進捗スナップショット (位置スカラ)
     * @param presentation 案内中 UI が読む presentation 射影 (バナー / リスト / CallOut)
     */
    @Immutable
    data class Guiding(
        val route: RouteDetail,
        val progress: GuidanceProgress,
        val presentation: GuidancePresentation,
    ) : GuidanceState

    /**
     * 逸脱検知 → 外部ナビ API ライブラリで再探索中。
     *
     * 再探索が完了するまで地図上の旧ルート表示と、リルート中パネルに必要な進捗情報を保持する。
     *
     * @param previousRoute 再探索前に案内していたルート
     * @param previousProgress 再探索を開始した時点の最後の進捗スナップショット
     */
    @Immutable
    data class Rerouting(
        val previousRoute: RouteDetail,
        val previousProgress: GuidanceProgress,
    ) : GuidanceState

    /** 目的地到達。 */
    data object Arrived : GuidanceState

    /** 致命的失敗。`message` に原因を入れる。 */
    @Immutable
    data class Failed(val message: String) : GuidanceState
}
