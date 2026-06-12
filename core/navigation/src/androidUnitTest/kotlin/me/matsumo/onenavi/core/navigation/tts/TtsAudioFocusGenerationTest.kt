package me.matsumo.onenavi.core.navigation.tts

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [TtsAudioFocusGeneration] の世代トークン判定のテスト。
 */
class TtsAudioFocusGenerationTest {

    @Test
    fun `新しい世代が発行されるまでは同じ token を現役として扱う`() {
        val generation = TtsAudioFocusGeneration()

        val focusToken = generation.issueToken()

        assertTrue(generation.ownsFocus(focusToken))
    }

    @Test
    fun `後続世代が発行されると古い token は現役でなくなる`() {
        val generation = TtsAudioFocusGeneration()

        val firstFocusToken = generation.issueToken()
        val secondFocusToken = generation.issueToken()

        assertFalse(generation.ownsFocus(firstFocusToken))
        assertTrue(generation.ownsFocus(secondFocusToken))
    }

    @Test
    fun `割り込みが連鎖しても最新 token だけが現役になる`() {
        val generation = TtsAudioFocusGeneration()

        val firstFocusToken = generation.issueToken()
        val secondFocusToken = generation.issueToken()
        val thirdFocusToken = generation.issueToken()

        assertFalse(generation.ownsFocus(firstFocusToken))
        assertFalse(generation.ownsFocus(secondFocusToken))
        assertTrue(generation.ownsFocus(thirdFocusToken))
    }
}
