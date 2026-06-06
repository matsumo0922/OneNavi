package me.matsumo.onenavi.core.navigation.tts

import me.matsumo.onenavi.core.navigation.voice.dispatch.VoiceAnnouncementContent
import me.matsumo.onenavi.core.resource.Res
import me.matsumo.onenavi.core.resource.tts_follow_traffic_rules
import me.matsumo.onenavi.core.resource.tts_navigation_started
import org.jetbrains.compose.resources.getString

/**
 * 音声案内の開始時に必ず最初に発話する固定アナウンスの内容を供給する。
 */
internal fun interface OpeningAnnouncementProvider {

    /** 開始アナウンスの発話内容を返す。文言解決のため suspend。 */
    suspend fun content(): VoiceAnnouncementContent
}

/**
 * strings.xml の固定文言から開始アナウンスを組み立てる実装。
 *
 * [Res.string.tts_navigation_started] (「音声案内を開始します。」) と
 * [Res.string.tts_follow_traffic_rules] (「実際の交通規制に従って走行してください。」) を連結し、
 * Google Cloud TTS 向けに `<speak>` で囲んだ SSML にする。
 */
internal class DefaultOpeningAnnouncementProvider : OpeningAnnouncementProvider {

    override suspend fun content(): VoiceAnnouncementContent {
        val started = getString(Res.string.tts_navigation_started)
        val followRules = getString(Res.string.tts_follow_traffic_rules)
        val ssml = PhonemeConverter.toGoogleCloudSsml(started + followRules)
        return VoiceAnnouncementContent(ssml = ssml, cue = null)
    }
}
