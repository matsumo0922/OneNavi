package me.matsumo.onenavi.core.navigation.tts

import me.matsumo.onenavi.core.navigation.voice.debug.VoiceAnnouncementDebugFetchState
import me.matsumo.onenavi.core.navigation.voice.dispatch.VoiceAnnouncementContent
import me.matsumo.onenavi.core.navigation.voice.dispatch.VoiceAnnouncementDispatcher

/**
 * 確定発話を Google Cloud TTS で合成し、ローカル効果音と合わせて再生する dispatcher。
 *
 * 1 発話の合成→再生に専念し、キュー消化・FLUSH/ADD・barge-in は scheduler 側
 * ([me.matsumo.onenavi.core.navigation.voice.scheduler.VoiceAnnouncementSpeechRunner]) が担う。
 * フォールバック (Android 標準 TTS) は持たず、合成失敗時は当該発話を無音にする graceful no-op。
 *
 * @property synthesizer Google Cloud TTS の合成・キャッシュ・先読みを担う合成器
 * @property audioPlayer PCM 再生プレイヤー
 * @property chimePlayer ローカル案内効果音プレイヤー
 * @property audioFocusManager 発話中の AudioFocus 管理
 * @property audioChannelResolver 発話ごとの出力チャンネル決定
 */
internal class GoogleCloudTtsVoiceAnnouncementDispatcher(
    private val synthesizer: CachedGoogleCloudTtsSynthesizer,
    private val audioPlayer: PcmAudioPlayer,
    private val chimePlayer: GuidanceChimePlayer,
    private val audioFocusManager: TtsAudioFocusManager,
    private val audioChannelResolver: NavigationAudioChannelResolver,
) : VoiceAnnouncementDispatcher {

    override suspend fun speak(content: VoiceAnnouncementContent) {
        if (!content.hasOutput) return

        val speechAudio = synthesizer.synthesize(content.ssml)
        if (content.cue == null && speechAudio == null) return

        val channel = audioChannelResolver.resolve()
        val focusToken = audioFocusManager.request(channel)

        try {
            content.cue?.let { cue -> chimePlayer.playAndAwait(cue, channel) }
            speechAudio?.let { audio -> audioPlayer.playAndAwait(audio, channel = channel) }
        } finally {
            audioFocusManager.abandon(focusToken)
        }
    }

    override fun prefetch(content: VoiceAnnouncementContent) {
        synthesizer.prefetch(content.ssml)
    }

    override fun debugFetchState(content: VoiceAnnouncementContent): VoiceAnnouncementDebugFetchState =
        synthesizer.debugFetchState(content.ssml)

    override fun clearPrefetch() {
        synthesizer.clearPrefetch()
    }
}
