package me.matsumo.onenavi.core.navigation.voice.selector

import me.matsumo.onenavi.core.navigation.voice.plan.AnnouncementStageKind
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * [VoiceAnnouncementUrgency] の緊急度順序のテスト。「より緊急なら大きい」を保証する。
 */
class VoiceAnnouncementUrgencyTest {

    @Test
    fun `残距離が小さいほど緊急`() {
        val near = VoiceAnnouncementUrgency(remainingMeters = 50.0, kind = AnnouncementStageKind.MIDDLE)
        val far = VoiceAnnouncementUrgency(remainingMeters = 500.0, kind = AnnouncementStageKind.MIDDLE)

        assertTrue(near > far)
    }

    @Test
    fun `同残距離では FINAL が MIDDLE より緊急`() {
        val finalStage = VoiceAnnouncementUrgency(remainingMeters = 100.0, kind = AnnouncementStageKind.FINAL)
        val middleStage = VoiceAnnouncementUrgency(remainingMeters = 100.0, kind = AnnouncementStageKind.MIDDLE)

        assertTrue(finalStage > middleStage)
    }

    @Test
    fun `残距離差は種別差より優先される`() {
        val nearMiddle = VoiceAnnouncementUrgency(remainingMeters = 50.0, kind = AnnouncementStageKind.MIDDLE)
        val farFinal = VoiceAnnouncementUrgency(remainingMeters = 300.0, kind = AnnouncementStageKind.FINAL)

        assertTrue(nearMiddle > farFinal)
    }
}
