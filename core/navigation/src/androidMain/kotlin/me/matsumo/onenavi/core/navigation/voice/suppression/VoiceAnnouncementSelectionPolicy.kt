package me.matsumo.onenavi.core.navigation.voice.suppression

import me.matsumo.onenavi.core.navigation.voice.selector.VoiceAnnouncementSelection

/**
 * 選択された発話候補を、現在の発話状態に照らして PLAY / BARGE_IN / SKIP に振り分ける。
 *
 * 発話中でなければ常に PLAY。発話中なら、新候補が発話中の段より緊急なときだけ BARGE_IN し、
 * そうでなければ SKIP する。FINAL は最も緊急になるため、結果として直前案内は割り込み発話される。
 */
internal class VoiceAnnouncementSelectionPolicy {

    /** 候補の扱いを判断する。 */
    fun decide(
        state: VoiceAnnouncementSpeechState,
        selection: VoiceAnnouncementSelection,
    ): VoiceAnnouncementDispatchDecision {
        val speaking = state.speaking ?: return VoiceAnnouncementDispatchDecision.PLAY

        val isCandidateMoreUrgent = selection.urgency > speaking.urgency
        if (isCandidateMoreUrgent) return VoiceAnnouncementDispatchDecision.BARGE_IN

        return VoiceAnnouncementDispatchDecision.SKIP
    }
}
