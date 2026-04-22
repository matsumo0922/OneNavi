package me.matsumo.onenavi.core.navigation.tts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PhonemeConverterTest {

    @Test
    fun `plain text replaces phoneme with kana`() {
        val ssml = "直進方向、<phoneme alphabet=\"x-toshiba-ruby\" ph=\"しゅとこう\">首都高</phoneme>方面です。"
        assertEquals("直進方向、しゅとこう方面です。", PhonemeConverter.toPlainText(ssml))
    }

    @Test
    fun `plain text falls back to body when ph is empty`() {
        val ssml = "<phoneme alphabet=\"x-toshiba-ruby\" ph=\"\">首都高</phoneme>"
        assertEquals("首都高", PhonemeConverter.toPlainText(ssml))
    }

    @Test
    fun `plain text strips other ssml tags`() {
        val ssml = "<speak>直進、<break time=\"200ms\"/>次です</speak>"
        assertEquals("直進、次です", PhonemeConverter.toPlainText(ssml))
    }

    @Test
    fun `google cloud ssml wraps in speak and rewrites toshiba ruby`() {
        val ssml = "直進方向、<phoneme alphabet=\"x-toshiba-ruby\" ph=\"しゅとこう\">首都高</phoneme>方面です。"
        val converted = PhonemeConverter.toGoogleCloudSsml(ssml)
        assertTrue(converted.startsWith("<speak>"))
        assertTrue(converted.endsWith("</speak>"))
        assertTrue(converted.contains("<sub alias=\"しゅとこう\">首都高</sub>"))
    }

    @Test
    fun `google cloud ssml preserves existing speak wrapper`() {
        val ssml = "<speak>直進方向、<phoneme alphabet=\"x-toshiba-ruby\" ph=\"しゅとこう\">首都高</phoneme>方面です。</speak>"
        val converted = PhonemeConverter.toGoogleCloudSsml(ssml)
        assertTrue(converted.startsWith("<speak>"))
        assertEquals(1, converted.split("<speak>").size - 1)
    }

    @Test
    fun `google cloud ssml preserves unknown alphabet phoneme`() {
        val ssml = "<phoneme alphabet=\"ipa\" ph=\"ɕuˈtokoː\">首都高</phoneme>"
        val converted = PhonemeConverter.toGoogleCloudSsml(ssml)
        assertTrue(converted.contains("alphabet=\"ipa\""))
    }
}
