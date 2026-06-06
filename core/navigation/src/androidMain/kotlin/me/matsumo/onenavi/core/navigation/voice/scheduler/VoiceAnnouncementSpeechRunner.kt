package me.matsumo.onenavi.core.navigation.voice.scheduler

import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import me.matsumo.onenavi.core.navigation.tts.MilestoneAnnouncementProvider
import me.matsumo.onenavi.core.navigation.tts.OpeningAnnouncementProvider
import me.matsumo.onenavi.core.navigation.voice.dispatch.VoiceAnnouncementContent
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
 * 開始アナウンス・経由地通過は「排他アナウンス」として案内発話へ割り込み、再生中は tick を保留する
 * ([awaitingExclusive])。目的地到達アナウンスは案内 session の終了 ([detach]) と同時に発火するため、
 * session に紐づかない [finalAnnouncementJob] で再生し、detach されても鳴り切らせる。
 *
 * @property scheduler 状態遷移を確定する同期コア
 * @property dispatcher 確定発話を実際に再生する出口
 * @property openingAnnouncementProvider 案内開始時に最初に発話する固定アナウンスの供給元
 * @property milestoneAnnouncementProvider 経由地通過 / 目的地到達のマイルストーン発話の供給元
 * @property scope ループと発話 coroutine を動かす scope
 */
internal class VoiceAnnouncementSpeechRunner(
    private val scheduler: VoiceAnnouncementScheduler,
    private val dispatcher: VoiceAnnouncementDispatcher,
    private val openingAnnouncementProvider: OpeningAnnouncementProvider,
    private val milestoneAnnouncementProvider: MilestoneAnnouncementProvider,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {

    private var eventChannel: Channel<SpeechEvent>? = null
    private var loopJob: Job? = null
    private var speechJob: Job? = null
    private var finalAnnouncementJob: Job? = null
    private var awaitingExclusive = false
    private var pendingTick: VoiceTick? = null

    /**
     * 発話プランを attach し、event ループを開始する。進行中のセッションがあれば破棄してから始める。
     *
     * @param plan 駆動する発話プラン
     * @param announceOpening true なら案内 tick の処理前に開始アナウンスを発話する (初回開始時のみ true)
     */
    fun attach(
        plan: VoiceAnnouncementPlan,
        announceOpening: Boolean = false,
    ) {
        detach()
        finalAnnouncementJob?.cancel()
        finalAnnouncementJob = null
        scheduler.attach(plan)

        val channel = Channel<SpeechEvent>(Channel.UNLIMITED)
        eventChannel = channel
        loopJob = launchEventLoop(channel)

        if (announceOpening) startExclusiveSpeech(channel) { openingAnnouncementProvider.content() }
    }

    /**
     * 位置 tick を 1 件投入する。attach 前 / detach 後は無視する。
     *
     * @param tick 発話判定用の tick
     */
    fun submit(tick: VoiceTick) {
        eventChannel?.trySend(SpeechEvent.Tick(tick))
    }

    /**
     * 経由地通過アナウンスを発話する。進行中の案内発話へ割り込み、完了まで案内 tick を保留する。
     *
     * 案内は継続するため event ループ経由で処理し、割り込み後に案内発話を再開できるよう scheduler の発話中マークを解除する。
     */
    fun announceWaypointApproach() {
        eventChannel?.trySend(SpeechEvent.WaypointMilestone)
    }

    /**
     * 目的地到達アナウンスを発話する。直後に案内 session が終了 ([detach]) しても鳴り切るよう、
     * session に紐づかない [finalAnnouncementJob] で再生する。進行中の案内発話へは割り込む。
     */
    fun announceDestinationReached() {
        speechJob?.cancel()
        finalAnnouncementJob?.cancel()
        finalAnnouncementJob = scope.launch {
            playAnnouncement { milestoneAnnouncementProvider.destinationReached() }
        }
    }

    /** event ループと進行中の発話を止め、コアの状態を破棄する。目的地到達アナウンスは鳴らし切るため止めない。 */
    fun detach() {
        eventChannel?.close()
        loopJob?.cancel()
        speechJob?.cancel()
        eventChannel = null
        loopJob = null
        speechJob = null
        awaitingExclusive = false
        pendingTick = null
        scheduler.detach()
    }

    /** event を 1 件ずつ直列に処理するループを開始する。 */
    private fun launchEventLoop(channel: Channel<SpeechEvent>): Job = scope.launch {
        for (event in channel) {
            handleEvent(event, channel)
        }
    }

    /**
     * event を 1 件処理する。排他アナウンス (開始 / 経由地通過) 中は最新の案内 tick だけを [pendingTick] に保持して
     * 案内発話と重ならないようにし、アナウンス完了 ([SpeechEvent.ExclusiveFinished]) 時にその最新 tick を再評価する。
     * これにより排他中に発話窓へ入った案内を、新しい GPS tick を待たずに完了直後へ持ち越せる。
     */
    private fun handleEvent(event: SpeechEvent, channel: Channel<SpeechEvent>) {
        when (event) {
            is SpeechEvent.ExclusiveFinished -> {
                awaitingExclusive = false
                speechJob = null
                drainPendingTick(channel)
            }
            is SpeechEvent.WaypointMilestone -> startWaypointMilestone(channel)
            is SpeechEvent.Tick -> if (awaitingExclusive) pendingTick = event.tick else execute(resolveCommand(event), channel)
            is SpeechEvent.SpeechFinished -> execute(resolveCommand(event), channel)
        }
    }

    /** 排他アナウンス中に保持した最新 tick があれば、完了後に 1 件だけ再評価する。 */
    private fun drainPendingTick(channel: Channel<SpeechEvent>) {
        val tick = pendingTick ?: return
        pendingTick = null
        execute(resolveCommand(SpeechEvent.Tick(tick)), channel)
    }

    /** event をコアに渡して発話実行指示を得る。 */
    private fun resolveCommand(event: SpeechEvent): VoiceAnnouncementCommand? = when (event) {
        is SpeechEvent.Tick -> scheduler.onTick(event.tick)
        is SpeechEvent.SpeechFinished -> scheduler.onSpeechFinished(event.stageId)
        is SpeechEvent.ExclusiveFinished, is SpeechEvent.WaypointMilestone -> null
    }

    /**
     * 経由地通過アナウンスを排他発話する。進行中の案内発話へ割り込んでから発話し、割り込み後の tick で案内発話を
     * 再開できるよう scheduler の発話中マークを解除する。
     */
    private fun startWaypointMilestone(channel: Channel<SpeechEvent>) {
        scheduler.onMilestoneInterrupted()
        startExclusiveSpeech(channel) { milestoneAnnouncementProvider.waypointApproach() }
    }

    /**
     * 排他アナウンスを発話する。進行中の発話へ割り込み、完了まで [awaitingExclusive] で案内 tick を保留して重なりを防ぐ。
     * 合成失敗・API キー未設定でも dispatcher 側が graceful に no-op で返り、完了 event を送るためループは詰まらない。
     */
    private fun startExclusiveSpeech(
        channel: Channel<SpeechEvent>,
        contentProvider: suspend () -> VoiceAnnouncementContent,
    ) {
        awaitingExclusive = true
        speechJob?.cancel()
        speechJob = scope.launch {
            playAnnouncement(contentProvider)
            channel.trySend(SpeechEvent.ExclusiveFinished)
        }
    }

    /** 固定アナウンスを 1 件再生する。barge-in の cancel は再 throw し、それ以外の失敗はログして無音扱いにする。 */
    private suspend fun playAnnouncement(contentProvider: suspend () -> VoiceAnnouncementContent) {
        try {
            dispatcher.speak(contentProvider())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            Napier.w(tag = TAG, throwable = error) { "fixed announcement failed" }
        }
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

        /** 経由地通過アナウンスの発話を要求する。 */
        data object WaypointMilestone : SpeechEvent

        /** 排他アナウンス (開始 / 経由地通過) の発話が完了した。 */
        data object ExclusiveFinished : SpeechEvent
    }

    private companion object {

        /** Logcat で発話実行系のログを絞り込むためのタグ。 */
        const val TAG = "VoiceAnnouncement"
    }
}
