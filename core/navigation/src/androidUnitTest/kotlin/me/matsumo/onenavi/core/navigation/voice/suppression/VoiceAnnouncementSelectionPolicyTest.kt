package me.matsumo.onenavi.core.navigation.voice.suppression

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import me.matsumo.onenavi.core.navigation.voice.plan.AnnouncementStage
import me.matsumo.onenavi.core.navigation.voice.plan.AnnouncementStageKind
import me.matsumo.onenavi.core.navigation.voice.plan.VoiceAnnouncementId
import me.matsumo.onenavi.core.navigation.voice.selector.VoiceAnnouncementSelection
import me.matsumo.onenavi.core.navigation.voice.selector.VoiceAnnouncementUrgency
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [VoiceAnnouncementSelectionPolicy] の PLAY / BARGE_IN / SKIP 判定のテスト。
 */
class VoiceAnnouncementSelectionPolicyTest {

    private val policy = VoiceAnnouncementSelectionPolicy()

    @Test
    fun `発話中でなければ PLAY`() {
        val selection = selectionOf(id = "s", remainingMeters = 100.0, kind = AnnouncementStageKind.MIDDLE)

        val decision = policy.decide(state = VoiceAnnouncementSpeechState(), selection = selection)

        assertEquals(VoiceAnnouncementDispatchDecision.PLAY, decision)
    }

    @Test
    fun `発話中より緊急な候補は BARGE_IN`() {
        val speakingState =
            speakingStateOf(id = "speaking", remainingMeters = 300.0, kind = AnnouncementStageKind.MIDDLE)
        val urgentSelection = selectionOf(id = "urgent", remainingMeters = 50.0, kind = AnnouncementStageKind.FINAL)

        val decision = policy.decide(state = speakingState, selection = urgentSelection)

        assertEquals(VoiceAnnouncementDispatchDecision.BARGE_IN, decision)
    }

    @Test
    fun `発話中より緊急でない候補は SKIP`() {
        val speakingState = speakingStateOf(id = "speaking", remainingMeters = 50.0, kind = AnnouncementStageKind.FINAL)
        val laterSelection = selectionOf(id = "later", remainingMeters = 300.0, kind = AnnouncementStageKind.MIDDLE)

        val decision = policy.decide(state = speakingState, selection = laterSelection)

        assertEquals(VoiceAnnouncementDispatchDecision.SKIP, decision)
    }

    @Test
    fun `発話中と同緊急度の候補は SKIP`() {
        val speakingState =
            speakingStateOf(id = "speaking", remainingMeters = 100.0, kind = AnnouncementStageKind.MIDDLE)
        val sameSelection = selectionOf(id = "same", remainingMeters = 100.0, kind = AnnouncementStageKind.MIDDLE)

        val decision = policy.decide(state = speakingState, selection = sameSelection)

        assertEquals(VoiceAnnouncementDispatchDecision.SKIP, decision)
    }

    private fun speakingStateOf(
        id: String,
        remainingMeters: Double,
        kind: AnnouncementStageKind,
    ): VoiceAnnouncementSpeechState {
        val speaking = SpeakingAnnouncement(
            stageId = VoiceAnnouncementId(id),
            urgency = VoiceAnnouncementUrgency(remainingMeters = remainingMeters, kind = kind),
        )
        return VoiceAnnouncementSpeechState().withSpeakingStarted(speaking)
    }

    private fun selectionOf(
        id: String,
        remainingMeters: Double,
        kind: AnnouncementStageKind,
    ): VoiceAnnouncementSelection = VoiceAnnouncementSelection(
        targetIndex = 0,
        stage = stageOf(id, kind),
        urgency = VoiceAnnouncementUrgency(remainingMeters = remainingMeters, kind = kind),
    )

    private fun stageOf(id: String, kind: AnnouncementStageKind): AnnouncementStage =
        AnnouncementStage(
            id = VoiceAnnouncementId(id),
            kind = kind,
            triggerSourceMeters = 0.0,
            triggerGeometryMeters = 0.0,
            pieces = persistentListOf(),
            categories = persistentSetOf(),
        )
}
