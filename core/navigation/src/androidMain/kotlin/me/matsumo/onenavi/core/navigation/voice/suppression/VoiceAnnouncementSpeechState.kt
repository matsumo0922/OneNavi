package me.matsumo.onenavi.core.navigation.voice.suppression

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentSetOf
import me.matsumo.onenavi.core.navigation.voice.plan.VoiceAnnouncementId

/**
 * route 寿命内で発話の重複・追い越しを抑止するための状態。tick ごとに不変コピーで更新していく。
 *
 * 発話開始・割り込み・キュー投入のいずれかで処理が確定した段は、再選択を避けるため
 * [firedStageIds] に含める (= 「処理が確定済み」)。
 *
 * @property firedStageIds 発話・割り込み・キュー投入のいずれかで処理が確定した段の id 集合。再トリガを防ぐ
 * @property passedTargetIndices 通過済み案内地点の plan 内 index 集合。残段の遅延発話を防ぐ
 * @property speaking 現在発話中の段。無発話なら null
 */
@Immutable
internal data class VoiceAnnouncementSpeechState(
    val firedStageIds: PersistentSet<VoiceAnnouncementId> = persistentSetOf(),
    val passedTargetIndices: PersistentSet<Int> = persistentSetOf(),
    val speaking: SpeakingAnnouncement? = null,
) {

    /** 段の処理が確定済み (発話・割り込み・キュー投入のいずれか) かを返す。 */
    fun isStageFired(stageId: VoiceAnnouncementId): Boolean = stageId in firedStageIds

    /** 案内地点が通過済みかを返す。 */
    fun isTargetPassed(targetIndex: Int): Boolean = targetIndex in passedTargetIndices

    /** 段を処理確定済みとして記録した新しい状態を返す。 */
    fun withStageFired(stageId: VoiceAnnouncementId): VoiceAnnouncementSpeechState =
        copy(firedStageIds = firedStageIds.add(stageId))

    /** 案内地点を通過済みとして記録した新しい状態を返す。 */
    fun withTargetPassed(targetIndex: Int): VoiceAnnouncementSpeechState =
        copy(passedTargetIndices = passedTargetIndices.add(targetIndex))

    /** 指定段の発話を開始した状態を返す。発話中マークと既発話マークを同時に立てる。 */
    fun withSpeakingStarted(announcement: SpeakingAnnouncement): VoiceAnnouncementSpeechState =
        copy(
            firedStageIds = firedStageIds.add(announcement.stageId),
            speaking = announcement,
        )

    /** 指定段の発話を終了した状態を返す。発話中の段が一致するときだけ解除する。 */
    fun withSpeakingFinished(stageId: VoiceAnnouncementId): VoiceAnnouncementSpeechState {
        if (speaking?.stageId != stageId) return this

        return copy(speaking = null)
    }
}
