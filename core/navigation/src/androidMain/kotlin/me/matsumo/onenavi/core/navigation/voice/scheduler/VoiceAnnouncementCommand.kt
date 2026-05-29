package me.matsumo.onenavi.core.navigation.voice.scheduler

import me.matsumo.onenavi.core.navigation.voice.dispatch.VoiceAnnouncementRequest

/**
 * 同期スケジューラ ([VoiceAnnouncementScheduler]) が、発話実行系へ「音声エンジンに何をさせるか」を
 * 伝える指示。スケジューラ自身は coroutine も音声エンジンも持たず、状態遷移を確定してこの指示だけを返す。
 *
 * ENQUEUE (発話中に積むだけ) はこの場で音声エンジンを動かさないため指示を伴わず、スケジューラは null を返す。
 */
internal sealed interface VoiceAnnouncementCommand {

    /** 発話させるリクエスト。 */
    val request: VoiceAnnouncementRequest

    /**
     * 発話中が無い状態から発話を開始する。PLAY と、発話完了後のキュー消化による次発話に対応する。
     *
     * @property request 開始する発話
     */
    data class StartSpeaking(
        override val request: VoiceAnnouncementRequest,
    ) : VoiceAnnouncementCommand

    /**
     * 進行中の発話を中断してから新しい発話を開始する。BARGE_IN に対応する。
     *
     * 実行系は進行中発話の coroutine を cancel してから [request] を発話する。
     *
     * @property request 中断後に開始する発話
     */
    data class InterruptAndSpeak(
        override val request: VoiceAnnouncementRequest,
    ) : VoiceAnnouncementCommand
}
