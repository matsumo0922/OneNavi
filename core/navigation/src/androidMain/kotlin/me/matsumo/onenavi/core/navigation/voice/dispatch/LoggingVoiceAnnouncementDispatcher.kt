package me.matsumo.onenavi.core.navigation.voice.dispatch

import io.github.aakira.napier.Napier

/**
 * 発話内容を Napier ログに出力するだけの dispatcher。実 TTS エンジン統合までの差し替え用 stub。
 *
 * 実発話は行わないため [speak] は即座に返る。発話タイミング・barge-in・キュー消化の挙動は scheduler 側で
 * 完結しており、本 stub をログ確認やテストで実エンジンの代わりに使える。
 */
internal class LoggingVoiceAnnouncementDispatcher : VoiceAnnouncementDispatcher {

    override suspend fun speak(ssml: String) {
        Napier.d(tag = TAG) { "speak: ssml=\"$ssml\"" }
    }

    private companion object {

        /** Logcat で発話 stub のログを絞り込むためのタグ。 */
        const val TAG = "VoiceAnnouncement"
    }
}
