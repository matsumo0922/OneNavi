package me.matsumo.onenavi.core.navigation.voice.scheduler

import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import me.matsumo.onenavi.core.navigation.voice.dispatch.VoiceAnnouncementDispatcher
import me.matsumo.onenavi.core.navigation.voice.dispatch.VoiceAnnouncementRequest
import me.matsumo.onenavi.core.navigation.voice.plan.VoiceAnnouncementId
import me.matsumo.onenavi.core.navigation.voice.plan.VoiceAnnouncementPlan
import me.matsumo.onenavi.core.navigation.voice.selector.VoiceTick

/**
 * 同期コア ([VoiceAnnouncementScheduler]) を coroutine で駆動する発話実行系。
 *
 * tick と発話完了を単一の event ループへ直列化することで、コアの状態をロックなしで一貫させる。発話自体は
 * 別 coroutine ([speechJob]) で再生し、ループを塞がない (barge-in 判定を続けられる)。barge-in 指示が来たら
 * 進行中の発話 coroutine を cancel してから新しい発話を始める。
 *
 * - tick・発話完了 → event ループ (単一 coroutine) でコアを呼び、状態遷移を確定
 * - 発話再生 → [speechJob] (個別 coroutine)。完了時に自分で完了 event をループへ送る
 *
 * @property scheduler 状態遷移を確定する同期コア
 * @property dispatcher 確定発話を実際に再生する出口
 * @property scope ループと発話 coroutine を動かす scope
 */
internal class VoiceAnnouncementSpeechRunner(
    private val scheduler: VoiceAnnouncementScheduler,
    private val dispatcher: VoiceAnnouncementDispatcher,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {

    private var eventChannel: Channel<SpeechEvent>? = null
    private var loopJob: Job? = null
    private var speechJob: Job? = null

    /**
     * 発話プランを attach し、event ループを開始する。進行中のセッションがあれば破棄してから始める。
     *
     * @param plan 駆動する発話プラン
     */
    fun attach(plan: VoiceAnnouncementPlan) {
        detach()
        scheduler.attach(plan)

        val channel = Channel<SpeechEvent>(Channel.UNLIMITED)
        eventChannel = channel
        loopJob = launchEventLoop(channel)
    }

    /**
     * 位置 tick を 1 件投入する。attach 前 / detach 後は無視する。
     *
     * @param tick 発話判定用の tick
     */
    fun submit(tick: VoiceTick) {
        eventChannel?.trySend(SpeechEvent.Tick(tick))
    }

    /** event ループと進行中の発話を止め、コアの状態を破棄する。 */
    fun detach() {
        eventChannel?.close()
        loopJob?.cancel()
        speechJob?.cancel()
        eventChannel = null
        loopJob = null
        speechJob = null
        scheduler.detach()
    }

    /** event を 1 件ずつ直列に処理するループを開始する。 */
    private fun launchEventLoop(channel: Channel<SpeechEvent>): Job = scope.launch {
        for (event in channel) {
            val command = resolveCommand(event)
            execute(command, channel)
        }
    }

    /** event をコアに渡して発話実行指示を得る。 */
    private fun resolveCommand(event: SpeechEvent): VoiceAnnouncementCommand? = when (event) {
        is SpeechEvent.Tick -> scheduler.onTick(event.tick)
        is SpeechEvent.SpeechFinished -> scheduler.onSpeechFinished(event.stageId)
    }

    /** 発話実行指示を音声エンジンへ反映する。 */
    private fun execute(command: VoiceAnnouncementCommand?, channel: Channel<SpeechEvent>) {
        when (command) {
            null -> Unit
            is VoiceAnnouncementCommand.StartSpeaking -> startSpeech(command.request, channel)
            is VoiceAnnouncementCommand.InterruptAndSpeak -> {
                speechJob?.cancel()
                startSpeech(command.request, channel)
            }
        }
    }

    /**
     * 発話 coroutine を起こす。完了したら完了 event をループへ戻し、キュー消化を進める。
     *
     * barge-in による cancel は協調キャンセルとして再 throw する (発話中マークは割り込み側が差し替え済み)。
     * それ以外の発話失敗はログして完了として扱い、speaking が詰まってキューが止まるのを防ぐ。
     */
    private fun startSpeech(request: VoiceAnnouncementRequest, channel: Channel<SpeechEvent>) {
        speechJob = scope.launch {
            try {
                dispatcher.speak(request.content)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                Napier.w(tag = TAG, throwable = error) { "voice dispatch failed: ${request.stageId.value}" }
            }
            channel.trySend(SpeechEvent.SpeechFinished(request.stageId))
        }
    }

    /**
     * event ループへ流す内部イベント。tick 投入と発話完了通知を 1 本のループに直列化する。
     */
    private sealed interface SpeechEvent {

        /**
         * 位置 tick を投入する。
         *
         * @property tick 発話判定用の tick
         */
        data class Tick(val tick: VoiceTick) : SpeechEvent

        /**
         * 発話が完了した。
         *
         * @property stageId 完了した発話段の id
         */
        data class SpeechFinished(val stageId: VoiceAnnouncementId) : SpeechEvent
    }

    private companion object {

        /** Logcat で発話実行系のログを絞り込むためのタグ。 */
        const val TAG = "VoiceAnnouncement"
    }
}
