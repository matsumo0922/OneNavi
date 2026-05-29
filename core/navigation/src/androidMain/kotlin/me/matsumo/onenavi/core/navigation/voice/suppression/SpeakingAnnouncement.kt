package me.matsumo.onenavi.core.navigation.voice.suppression

import androidx.compose.runtime.Immutable
import me.matsumo.onenavi.core.navigation.voice.plan.AnnouncementStageKind
import me.matsumo.onenavi.core.navigation.voice.plan.VoiceAnnouncementId
import me.matsumo.onenavi.core.navigation.voice.selector.VoiceAnnouncementSelection
import me.matsumo.onenavi.core.navigation.voice.selector.VoiceAnnouncementUrgency
import me.matsumo.onenavi.core.navigation.voice.selector.VoiceTick

/**
 * 現在発話中の段の最小情報。barge-in 判定で発話中の緊急度と新候補の緊急度を比較するために保持する。
 *
 * 緊急度は現在地に対する残距離で決まり発話中も変化するため、開始時点の値は保持せず、
 * 案内地点位置と種別だけを持って tick ごとに [currentUrgency] で再計算する。
 *
 * @property stageId 発話中の段の id
 * @property targetGeometryMeters 発話中の段が属する案内地点の geometry 累積距離 (m)
 * @property kind 発話中の段の種別
 */
@Immutable
internal data class SpeakingAnnouncement(
    val stageId: VoiceAnnouncementId,
    val targetGeometryMeters: Double,
    val kind: AnnouncementStageKind,
) {

    /** 現 tick での緊急度を再計算する。発話を続けるほど残距離が縮み緊急度が上がる。 */
    fun currentUrgency(tick: VoiceTick): VoiceAnnouncementUrgency =
        VoiceAnnouncementUrgency.of(
            targetGeometryMeters = targetGeometryMeters,
            currentCumulativeMeters = tick.currentCumulativeMeters,
            kind = kind,
        )

    internal companion object {

        /** 選択結果から発話中レコードを作る。 */
        fun from(selection: VoiceAnnouncementSelection): SpeakingAnnouncement =
            SpeakingAnnouncement(
                stageId = selection.stage.id,
                targetGeometryMeters = selection.targetGeometryMeters,
                kind = selection.stage.kind,
            )
    }
}
