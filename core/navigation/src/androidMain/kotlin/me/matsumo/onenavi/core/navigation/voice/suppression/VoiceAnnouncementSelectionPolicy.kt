package me.matsumo.onenavi.core.navigation.voice.suppression

import me.matsumo.onenavi.core.navigation.voice.selector.VoiceAnnouncementSelection
import me.matsumo.onenavi.core.navigation.voice.selector.VoiceTick

/**
 * 選択された発話候補を、現在の発話状態に照らして PLAY / BARGE_IN / ENQUEUE に振り分ける。
 *
 * 発話中でなければ常に PLAY。発話中なら、新候補が発話中の段より緊急なときだけ BARGE_IN し、
 * そうでなければ ENQUEUE して worker に直列消化させる (= 後続案内を捨てない)。発話中の緊急度は
 * 残距離で変化するため、開始時点の値ではなく現 tick で再計算した値と比較する。
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
        val isCandidateMoreUrgent = selection.urgency > speakingUrgency
        if (isCandidateMoreUrgent) return VoiceAnnouncementDispatchDecision.BARGE_IN

        return VoiceAnnouncementDispatchDecision.ENQUEUE
    }
}
