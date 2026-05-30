package me.matsumo.onenavi.core.navigation.voice.suppression

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import me.matsumo.onenavi.core.navigation.voice.plan.AnnouncementStage
import me.matsumo.onenavi.core.navigation.voice.plan.AnnouncementStageKind
import me.matsumo.onenavi.core.navigation.voice.plan.VoiceAnnouncementId
import me.matsumo.onenavi.core.navigation.voice.selector.VoiceAnnouncementSelection
import me.matsumo.onenavi.core.navigation.voice.selector.VoiceAnnouncementUrgency
import me.matsumo.onenavi.core.navigation.voice.selector.VoiceTick
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [VoiceAnnouncementSelectionPolicy] の PLAY / BARGE_IN / ENQUEUE 判定のテスト。
 *
 * 発話中の緊急度を現 tick で再計算して比較すること (開始時点の値を使わないこと) を含めて検証する。
 */
class VoiceAnnouncementSelectionPolicyTest {

    private val policy = VoiceAnnouncementSelectionPolicy()

    @Test
    fun `発話中でなければ PLAY`() {
        val tick = tickOf(current = 100.0)
        val selection =
            selectionOf(id = "s", targetGeometryMeters = 200.0, tick = tick, kind = AnnouncementStageKind.MIDDLE)

        val decision = policy.decide(state = VoiceAnnouncementSpeechState(), selection = selection, tick = tick)

        assertEquals(VoiceAnnouncementDispatchDecision.PLAY, decision)
    }

    @Test
    fun `発話中より緊急な候補は BARGE_IN`() {
        val tick = tickOf(current = 100.0)
        // 発話中は遠い地点 (残 900)、新候補は近い地点 (残 100) → 新候補が緊急。
        val speakingState = speakingStateOf(id = "speaking", targetGeometryMeters = 1_000.0)
        val urgentSelection =
            selectionOf(id = "urgent", targetGeometryMeters = 200.0, tick = tick, kind = AnnouncementStageKind.FINAL)

        val decision = policy.decide(state = speakingState, selection = urgentSelection, tick = tick)

        assertEquals(VoiceAnnouncementDispatchDecision.BARGE_IN, decision)
    }

    @Test
    fun `発話中より緊急でない候補は ENQUEUE で捨てない`() {
        val tick = tickOf(current = 100.0)
        // 発話中は近い地点 (残 100)、新候補は遠い地点 (残 900) → 新候補は非緊急。
        val speakingState = speakingStateOf(id = "speaking", targetGeometryMeters = 200.0)
        val laterSelection =
            selectionOf(id = "later", targetGeometryMeters = 1_000.0, tick = tick, kind = AnnouncementStageKind.MIDDLE)

        val decision = policy.decide(state = speakingState, selection = laterSelection, tick = tick)

        assertEquals(VoiceAnnouncementDispatchDecision.ENQUEUE, decision)
    }

    @Test
    fun `発話中と同緊急度の候補は ENQUEUE`() {
        val tick = tickOf(current = 100.0)
        val speakingState = speakingStateOf(id = "speaking", targetGeometryMeters = 500.0)
        val sameSelection =
            selectionOf(id = "same", targetGeometryMeters = 500.0, tick = tick, kind = AnnouncementStageKind.MIDDLE)

        val decision = policy.decide(state = speakingState, selection = sameSelection, tick = tick)

        assertEquals(VoiceAnnouncementDispatchDecision.ENQUEUE, decision)
    }

    @Test
    fun `発話中の緊急度は現 tick で再計算され近い発話を奥の中間段で中断しない`() {
        // 発話中は手前地点 (geo 1000)。発話が進み現在地は 850m (残 150 = かなり緊急)。
        val tick = tickOf(current = 850.0)
        val speakingState = speakingStateOf(id = "near", targetGeometryMeters = 1_000.0)
        // 奥地点 (geo 1350) の中間段が今 trigger。残 500。
        val farSelection =
            selectionOf(id = "far", targetGeometryMeters = 1_350.0, tick = tick, kind = AnnouncementStageKind.MIDDLE)

        val decision = policy.decide(state = speakingState, selection = farSelection, tick = tick)

        // 残 150 < 残 500 なので発話中が緊急 → 中断せず ENQUEUE。
        // 開始時 urgency を固定保持していると誤って BARGE_IN になる回帰ケース。
        assertEquals(VoiceAnnouncementDispatchDecision.ENQUEUE, decision)
    }

    @Test
    fun `発話中 target を通過済みなら次地点の候補に道を譲る`() {
        // A (geo 500) の中間段を発話中のまま A を通過 (現在地 510m, 残 -10)。
        val tick = tickOf(current = 510.0)
        val speakingState = speakingStateOf(id = "passedA", targetGeometryMeters = 500.0)
        // 次地点 B (geo 550) の FINAL が trigger。残 40。
        val nextSelection =
            selectionOf(id = "nextB", targetGeometryMeters = 550.0, tick = tick, kind = AnnouncementStageKind.FINAL)

        val decision = policy.decide(state = speakingState, selection = nextSelection, tick = tick)

        // 通過済み発話の残距離は負だが「最緊急」とはせず、新候補に BARGE_IN で譲る。
        assertEquals(VoiceAnnouncementDispatchDecision.BARGE_IN, decision)
    }

    private fun speakingStateOf(
        id: String,
        targetGeometryMeters: Double,
    ): VoiceAnnouncementSpeechState {
        val speaking = SpeakingAnnouncement(
            stageId = VoiceAnnouncementId(id),
            targetGeometryMeters = targetGeometryMeters,
            kind = AnnouncementStageKind.MIDDLE,
        )
        return VoiceAnnouncementSpeechState().withSpeakingStarted(speaking)
    }

    private fun selectionOf(
        id: String,
        targetGeometryMeters: Double,
        tick: VoiceTick,
        kind: AnnouncementStageKind,
    ): VoiceAnnouncementSelection = VoiceAnnouncementSelection(
        targetIndex = 0,
        targetGeometryMeters = targetGeometryMeters,
        stage = stageOf(id, kind),
        urgency = VoiceAnnouncementUrgency.of(
            targetGeometryMeters = targetGeometryMeters,
            currentCumulativeMeters = tick.currentCumulativeMeters,
            kind = kind,
        ),
    )

    private fun stageOf(id: String, kind: AnnouncementStageKind): AnnouncementStage =
        AnnouncementStage(
            id = VoiceAnnouncementId(id),
            groupKey = VoiceAnnouncementId("$id-group"),
            kind = kind,
            triggerSourceMeters = 0.0,
            triggerGeometryMeters = 0.0,
            middleWindow = null,
            pieces = persistentListOf(),
            categories = persistentSetOf(),
        )

    private fun tickOf(current: Double): VoiceTick = VoiceTick(
        currentCumulativeMeters = current,
        speedMetersPerSecond = null,
        isRouteUsable = true,
    )
}
