package me.matsumo.onenavi.core.navigation.voice.scheduler

import me.matsumo.onenavi.core.navigation.newguidance.model.RouteMatchState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [isUsableForVoiceAnnouncement] の route 一致状態 → 発話可否マッピングのテスト。
 */
class VoiceTickFactoryTest {

    @Test
    fun `ON_ROUTE は発話可能`() {
        assertTrue(RouteMatchState.ON_ROUTE.isUsableForVoiceAnnouncement())
    }

    @Test
    fun `OFF_ROUTE 候補と確定は発話不能`() {
        assertFalse(RouteMatchState.OFF_ROUTE_CANDIDATE.isUsableForVoiceAnnouncement())
        assertFalse(RouteMatchState.OFF_ROUTE_CONFIRMED.isUsableForVoiceAnnouncement())
    }
}
