package me.matsumo.onenavi.core.navigation.tts

import me.matsumo.onenavi.core.navigation.voice.dispatch.VoiceAnnouncementContent
import me.matsumo.onenavi.core.resource.Res
import me.matsumo.onenavi.core.resource.tts_destination_approach
import me.matsumo.onenavi.core.resource.tts_navigation_finished
import me.matsumo.onenavi.core.resource.tts_waypoint_approach
import org.jetbrains.compose.resources.getString

/**
 * 経由地通過・目的地到達のマイルストーン発話の内容を供給する。
 */
internal interface MilestoneAnnouncementProvider {

    /** 経由地通過時の発話内容を返す。文言解決のため suspend。 */
    suspend fun waypointApproach(): VoiceAnnouncementContent

    /** 目的地到達時の発話内容を返す。文言解決のため suspend。 */
    suspend fun destinationReached(): VoiceAnnouncementContent
}

/**
 * strings.xml の固定文言からマイルストーン発話を組み立てる実装。
 *
 * 経由地通過は [Res.string.tts_waypoint_approach] (「経由地付近です。」)、目的地到達は
 * [Res.string.tts_destination_approach] (「目的地付近です。」) と [Res.string.tts_navigation_finished]
 * (「運転、お疲れ様でした。」) を連結し、Google Cloud TTS 向けに `<speak>` で囲んだ SSML にする。
 */
internal class DefaultMilestoneAnnouncementProvider : MilestoneAnnouncementProvider {

    override suspend fun waypointApproach(): VoiceAnnouncementContent {
        val approach = getString(Res.string.tts_waypoint_approach)
        return VoiceAnnouncementContent(ssml = PhonemeConverter.toGoogleCloudSsml(approach), cue = null)
    }

    override suspend fun destinationReached(): VoiceAnnouncementContent {
        val approach = getString(Res.string.tts_destination_approach)
        val finished = getString(Res.string.tts_navigation_finished)
        val ssml = PhonemeConverter.toGoogleCloudSsml(approach + finished)
        return VoiceAnnouncementContent(ssml = ssml, cue = null)
    }
}
