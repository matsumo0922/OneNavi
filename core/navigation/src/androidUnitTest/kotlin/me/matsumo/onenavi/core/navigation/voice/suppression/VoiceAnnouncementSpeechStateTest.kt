package me.matsumo.onenavi.core.navigation.voice.suppression

import me.matsumo.onenavi.core.navigation.voice.plan.AnnouncementStageKind
import me.matsumo.onenavi.core.navigation.voice.plan.VoiceAnnouncementId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [VoiceAnnouncementSpeechState] の不変更新 (発話済み / 通過済み / 発話中) のテスト。
 */
class VoiceAnnouncementSpeechStateTest {

    @Test
    fun `発話開始で既発話マークと発話中マークが同時に立つ`() {
        val announcement = speakingOf("s")

        val started = VoiceAnnouncementSpeechState().withSpeakingStarted(announcement)

        assertTrue(started.isStageFired(VoiceAnnouncementId("s")))
        assertEquals(announcement, started.speaking)
    }

    @Test
    fun `発話終了は発話中の段が一致するときだけ解除する`() {
        val started = VoiceAnnouncementSpeechState().withSpeakingStarted(speakingOf("s"))

        val finishedOther = started.withSpeakingFinished(VoiceAnnouncementId("other"))
        val finishedMatch = started.withSpeakingFinished(VoiceAnnouncementId("s"))

        assertEquals(started.speaking, finishedOther.speaking)
        assertNull(finishedMatch.speaking)
        // 解除しても既発話マークは残り、再発話されない。
        assertTrue(finishedMatch.isStageFired(VoiceAnnouncementId("s")))
    }

    @Test
    fun `通過済みマークは案内地点 index 単位で記録される`() {
        val state = VoiceAnnouncementSpeechState()
            .withTargetPassed(0)
            .withTargetPassed(2)

        assertTrue(state.isTargetPassed(0))
        assertFalse(state.isTargetPassed(1))
        assertTrue(state.isTargetPassed(2))
    }

    private fun speakingOf(id: String): SpeakingAnnouncement = SpeakingAnnouncement(
        stageId = VoiceAnnouncementId(id),
        targetGeometryMeters = 1_000.0,
        kind = AnnouncementStageKind.MIDDLE,
    )
}
