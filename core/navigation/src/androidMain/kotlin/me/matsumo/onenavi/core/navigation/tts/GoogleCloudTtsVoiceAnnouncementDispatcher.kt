package me.matsumo.onenavi.core.navigation.tts

import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import me.matsumo.onenavi.core.navigation.voice.dispatch.VoiceAnnouncementContent
import me.matsumo.onenavi.core.navigation.voice.dispatch.VoiceAnnouncementDispatcher

/**
 * 確定発話を Google Cloud TTS で合成し、ローカル効果音と合わせて再生する dispatcher。
 *
 * 1 発話の合成→再生に専念し、キュー消化・FLUSH/ADD・barge-in は scheduler 側
 * ([me.matsumo.onenavi.core.navigation.voice.scheduler.VoiceAnnouncementSpeechRunner]) が担う。
 * フォールバック (Android 標準 TTS) は持たず、合成失敗時は当該発話を無音にする graceful no-op。
 *
 * @property api Google Cloud TTS API クライアント
 * @property audioPlayer PCM 再生プレイヤー
 * @property chimePlayer ローカル案内効果音プレイヤー
 * @property audioFocusManager 発話中の AudioFocus 管理
 * @property audioChannelResolver 発話ごとの出力チャンネル決定
 * @property apiKey 空なら一切合成を試みない (no-op)
 */
internal class GoogleCloudTtsVoiceAnnouncementDispatcher(
    private val api: GoogleCloudTtsApi,
    private val audioPlayer: PcmAudioPlayer,
    private val chimePlayer: GuidanceChimePlayer,
    private val audioFocusManager: TtsAudioFocusManager,
    private val audioChannelResolver: NavigationAudioChannelResolver,
    private val apiKey: String,
) : VoiceAnnouncementDispatcher {

    /** 認証・リクエスト不正など恒久エラーで落ちたら、プロセス中は以後一切合成しない。 */
    private var sessionDisabled = false

    override suspend fun speak(content: VoiceAnnouncementContent) {
        if (!content.hasOutput) return

        val speechAudio = synthesize(content.ssml)
        if (content.cue == null && speechAudio == null) return

        val channel = audioChannelResolver.resolve()
        audioFocusManager.request(channel)

        try {
            content.cue?.let { cue -> chimePlayer.playAndAwait(cue, channel) }
            speechAudio?.let { audio -> audioPlayer.playAndAwait(audio, channel = channel) }
        } finally {
            audioFocusManager.abandon()
        }
    }

    /** SSML があれば音声合成し、合成できない場合は null を返す。 */
    private suspend fun synthesize(ssml: String?): ByteArray? {
        if (ssml.isNullOrBlank()) return null
        if (sessionDisabled) return null
        if (apiKey.isBlank()) {
            Napier.w(tag = TAG) { "GOOGLE_CLOUD_TTS_API_KEY is blank; voice announcement disabled" }
            return null
        }

        return try {
            Napier.d(tag = TAG) { "voice synthesize: $ssml" }
            api.synthesize(ssml)
        } catch (error: GoogleCloudTtsException) {
            handleSynthesizeError(error)
            null
        } catch (error: Throwable) {
            error.throwIfCancellation()
            Napier.w(tag = TAG, throwable = error) { "voice synthesize failed (transient)" }
            null
        }
    }

    /** coroutine のキャンセルだけは発話失敗として握りつぶさず呼び出し元へ返す。 */
    private fun Throwable.throwIfCancellation() {
        if (this is CancellationException) throw this
    }

    /** 合成失敗を恒久/一時に分類する。恒久ならセッションを無効化し error を 1 度だけ出す。 */
    private fun handleSynthesizeError(error: GoogleCloudTtsException) {
        if (error.statusCode in PERMANENT_STATUS_CODES) {
            sessionDisabled = true

            Napier.e(tag = TAG, throwable = error) {
                "voice synthesize permanently disabled: HTTP ${error.statusCode}"
            }
        } else {
            Napier.w(tag = TAG, throwable = error) {
                "voice synthesize failed (transient): HTTP ${error.statusCode}"
            }
        }
    }

    private companion object {

        /** Logcat で発話ログを絞り込むためのタグ。 */
        const val TAG = "VoiceAnnouncement(GoogleCloudTts)"

        /** リトライしても直らない恒久エラーの HTTP ステータス。 */
        val PERMANENT_STATUS_CODES = setOf(400, 401, 403, 404)
    }
}
