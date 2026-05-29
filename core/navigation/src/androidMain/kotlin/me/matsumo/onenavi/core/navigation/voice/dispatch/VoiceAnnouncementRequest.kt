package me.matsumo.onenavi.core.navigation.voice.dispatch

import androidx.compose.runtime.Immutable
import me.matsumo.onenavi.core.navigation.voice.plan.AnnouncementStageKind
import me.matsumo.onenavi.core.navigation.voice.plan.VoiceAnnouncementId
import me.matsumo.onenavi.core.navigation.voice.selector.VoiceAnnouncementSelection

/**
 * scheduler が dispatcher / 直列消化キューへ受け渡す、発話 1 件ぶんの確定リクエスト。
 *
 * 選択結果 ([VoiceAnnouncementSelection]) のうち発話実行と発話中状態の再構築に必要な情報だけを抜き出す。
 * 緊急度は現在地に対する相対量で tick ごとに変わるため保持せず、案内地点位置と種別だけを持ち回る。
 *
 * @property stageId 発話する距離段の id。発話完了通知の照合キーになる
 * @property targetIndex 案内地点の plan 内 index。キュー消化時の通過済み判定に使う
 * @property targetGeometryMeters 案内地点の geometry 累積距離 (m)。発話中の緊急度再計算の素材
 * @property kind 距離段の種別。発話中の緊急度 tie-break に使う
 * @property content 読み上げる確定発話内容
 */
@Immutable
internal data class VoiceAnnouncementRequest(
    val stageId: VoiceAnnouncementId,
    val targetIndex: Int,
    val targetGeometryMeters: Double,
    val kind: AnnouncementStageKind,
    val content: VoiceAnnouncementContent,
) {

    internal companion object {

        /**
         * 選択結果とレンダリング済み発話内容からリクエストを作る。
         *
         * @param selection scheduler が採用した距離段の選択結果
         * @param content category gate / 結合を適用済みの発話内容
         */
        fun from(
            selection: VoiceAnnouncementSelection,
            content: VoiceAnnouncementContent,
        ): VoiceAnnouncementRequest = VoiceAnnouncementRequest(
            stageId = selection.stage.id,
            targetIndex = selection.targetIndex,
            targetGeometryMeters = selection.targetGeometryMeters,
            kind = selection.stage.kind,
            content = content,
        )
    }
}
