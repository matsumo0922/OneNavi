package me.matsumo.onenavi.core.navigation.tts

import android.content.Context
import android.media.AudioAttributes
import me.matsumo.onenavi.core.navigation.R
import me.matsumo.onenavi.core.navigation.voice.dispatch.VoiceAnnouncementCue

/**
 * TTS では読ませない案内効果音を raw resource から再生するプレイヤー。
 *
 * @property context raw resource を読むための Android context
 * @property audioPlayer WAV を再生する PCM プレイヤー
 */
internal class GuidanceChimePlayer(
    private val context: Context,
    private val audioPlayer: PcmAudioPlayer,
) {

    private val guidanceChimeAudio: ByteArray by lazy {
        context.resources.openRawResource(R.raw.guidance_chime).use { inputStream ->
            inputStream.readBytes()
        }
    }

    /**
     * 指定された案内効果音を再生し、完了まで suspend する。
     *
     * @param cue 再生する案内効果音
     * @param channel 出力する音声チャンネル (usage)。発話と揃えて duck を一貫させる。
     */
    suspend fun playAndAwait(
        cue: VoiceAnnouncementCue,
        channel: NavigationAudioChannel,
    ) {
        when (cue) {
            VoiceAnnouncementCue.CHIME -> audioPlayer.playAndAwait(
                audio = guidanceChimeAudio,
                contentType = AudioAttributes.CONTENT_TYPE_SONIFICATION,
                channel = channel,
            )
        }
    }
}
