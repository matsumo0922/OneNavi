package me.matsumo.onenavi.core.navigation.voice.suppression

import me.matsumo.onenavi.core.navigation.voice.selector.VoiceAnnouncementSelection
import me.matsumo.onenavi.core.navigation.voice.selector.VoiceAnnouncementUrgency
import me.matsumo.onenavi.core.navigation.voice.selector.VoiceTick

/**
 * 選択された発話候補を、現在の発話状態に照らして PLAY / BARGE_IN / ENQUEUE に振り分ける。
 *
 * 発話中でなければ常に PLAY。発話中なら、新候補が発話中の段より緊急なときだけ BARGE_IN し、
 * そうでなければ ENQUEUE して worker に直列消化させる (= 後続案内を捨てない)。発話中の緊急度は
 * 残距離で変化するため、開始時点の値ではなく現 tick で再計算した値と比較する。
 *
 * 発話中の段の案内地点を通過済み (現在地より後ろ) のときは、その発話はすでに用済みなので
 * 新候補に道を譲る (BARGE_IN)。これがないと通過済み段の残距離が負になり「最緊急」と誤判定され、
 * 次地点の FINAL すら割り込めなくなる。
 */
internal class VoiceAnnouncementSelectionPolicy {

    /** 候補の扱いを判断する。 */
    fun decide(
        state: VoiceAnnouncementSpeechState,
        selection: VoiceAnnouncementSelection,
        tick: VoiceTick,
    ): VoiceAnnouncementDispatchDecision {
        val speaking = state.speaking ?: return VoiceAnnouncementDispatchDecision.PLAY

        val speakingUrgency = speaking.currentUrgency(tick)
        if (isSpeakingTargetPassed(speakingUrgency)) return VoiceAnnouncementDispatchDecision.BARGE_IN

        val isCandidateMoreUrgent = selection.urgency > speakingUrgency
        if (isCandidateMoreUrgent) return VoiceAnnouncementDispatchDecision.BARGE_IN

        return VoiceAnnouncementDispatchDecision.ENQUEUE
    }

    /** 発話中の段の案内地点が現在地より後ろ (= 通過済み) かを返す。残距離が負なら通過済み。 */
    private fun isSpeakingTargetPassed(speakingUrgency: VoiceAnnouncementUrgency): Boolean =
        speakingUrgency.remainingMeters < 0.0
}
