package me.matsumo.onenavi.core.navigation.voice.scheduler

import me.matsumo.onenavi.core.navigation.voice.dispatch.VoiceAnnouncementContentRenderer
import me.matsumo.onenavi.core.navigation.voice.dispatch.VoiceAnnouncementRequest
import me.matsumo.onenavi.core.navigation.voice.plan.VoiceAnnouncementId
import me.matsumo.onenavi.core.navigation.voice.plan.VoiceAnnouncementPlan
import me.matsumo.onenavi.core.navigation.voice.selector.VoiceAnnouncementSelection
import me.matsumo.onenavi.core.navigation.voice.selector.VoiceAnnouncementSelector
import me.matsumo.onenavi.core.navigation.voice.selector.VoiceTick
import me.matsumo.onenavi.core.navigation.voice.suppression.SpeakingAnnouncement
import me.matsumo.onenavi.core.navigation.voice.suppression.VoiceAnnouncementDispatchDecision
import me.matsumo.onenavi.core.navigation.voice.suppression.VoiceAnnouncementSelectionPolicy
import me.matsumo.onenavi.core.navigation.voice.suppression.VoiceAnnouncementSpeechState

/**
 * 発話プランと位置 tick から、発話状態を更新しつつ発話実行指示 ([VoiceAnnouncementCommand]) を確定する同期コア。
 *
 * coroutine や音声エンジンは持たず、純粋に状態遷移だけを行う。実際の発話再生・barge-in の cancel・発話完了の
 * 検知は実行系 ([VoiceAnnouncementController]) が担い、完了を [onSpeechFinished] でこのコアへ戻す。
 *
 * 本コアは状態 ([VoiceAnnouncementSpeechState]) と直列消化キューを単独で保持し、スレッドセーフではない。
 * [onTick] / [onSpeechFinished] / [attach] / [detach] は単一の実行系から直列に呼ぶこと。
 *
 * 各 tick の流れ:
 * 1. 通過済み案内地点を状態へ畳み込む ([VoiceAnnouncementSelector.passedTargetIndices])。
 * 2. 最緊急の発話候補を 1 件選ぶ ([VoiceAnnouncementSelector.select])。
 * 3. PLAY / BARGE_IN / ENQUEUE を判定し ([VoiceAnnouncementSelectionPolicy])、選ばれた段を必ず処理済みにする。
 *    level トリガなので処理済みにしないと同じ段が鳴り続ける。
 */
internal class VoiceAnnouncementScheduler(
    private val selector: VoiceAnnouncementSelector,
    private val policy: VoiceAnnouncementSelectionPolicy,
    private val contentRenderer: VoiceAnnouncementContentRenderer,
) {

    private var plan: VoiceAnnouncementPlan? = null
    private var state = VoiceAnnouncementSpeechState()
    private val pendingQueue = ArrayDeque<VoiceAnnouncementRequest>()

    /**
     * 発話プランを attach し、発話状態とキューを初期化する。route が切り替わるたびに呼ぶ。
     *
     * @param plan attach する発話プラン
     */
    fun attach(plan: VoiceAnnouncementPlan) {
        this.plan = plan
        state = VoiceAnnouncementSpeechState()
        pendingQueue.clear()
    }

    /** 発話プラン・状態・キューを破棄する。案内終了時に呼ぶ。 */
    fun detach() {
        plan = null
        state = VoiceAnnouncementSpeechState()
        pendingQueue.clear()
    }

    /**
     * 位置 tick を 1 件処理し、発話実行指示を返す。実行する発話が無ければ null。
     *
     * @param tick 現在地スカラを発話レイヤ向けに絞った tick
     * @return 実行系への発話指示。発話を起こさない (候補なし / ENQUEUE / 発話内容なし) 場合は null
     */
    fun onTick(tick: VoiceTick): VoiceAnnouncementCommand? {
        val currentPlan = plan ?: return null
        recordPassedTargets(currentPlan, tick)

        val selection = selector.select(currentPlan, tick, state) ?: return null

        return dispatchSelection(selection, tick)
    }

    /**
     * 発話完了通知を処理し、キューに次段があれば発話指示を返す。
     *
     * 発話中マークが一致するときだけ解除する。barge-in で既に別段へ差し替わっている場合は解除されず、
     * 現発話の完了を待ってからキューを消化する。
     *
     * @param stageId 完了した発話段の id
     * @return キュー消化による次発話指示。無ければ null
     */
    fun onSpeechFinished(stageId: VoiceAnnouncementId): VoiceAnnouncementCommand? {
        state = state.withSpeakingFinished(stageId)
        if (state.speaking != null) return null

        return dispatchNextFromQueue()
    }

    /** 通過し終えた案内地点を状態へ畳み込む。記録側が冪等に union するため毎 tick 全件渡してよい。 */
    private fun recordPassedTargets(plan: VoiceAnnouncementPlan, tick: VoiceTick) {
        val passedIndices = selector.passedTargetIndices(plan, tick)

        for (targetIndex in passedIndices) {
            state = state.withTargetPassed(targetIndex)
        }
    }

    /**
     * 選択結果を PLAY / BARGE_IN / ENQUEUE に振り分けて実行指示を確定する。
     *
     * 発話内容が空 (全 category OFF 等) の段は発話を起こさないが、level トリガで鳴り続けないよう
     * 処理済みマークだけ付けて畳む。
     */
    private fun dispatchSelection(
        selection: VoiceAnnouncementSelection,
        tick: VoiceTick,
    ): VoiceAnnouncementCommand? {
        val content = contentRenderer.render(selection.stage)
        if (content == null) {
            state = state.withStageFired(selection.stage.id)
            return null
        }

        val request = VoiceAnnouncementRequest.from(selection, content)
        val decision = policy.decide(state, selection, tick)

        return when (decision) {
            VoiceAnnouncementDispatchDecision.PLAY -> startSpeaking(request)
            VoiceAnnouncementDispatchDecision.BARGE_IN -> interruptAndSpeak(request)
            VoiceAnnouncementDispatchDecision.ENQUEUE -> enqueue(request)
        }
    }

    /** キュー先頭のうち、通過済みでない最初の発話を取り出して開始指示にする。無ければ null。 */
    private fun dispatchNextFromQueue(): VoiceAnnouncementCommand? {
        while (pendingQueue.isNotEmpty()) {
            val request = pendingQueue.removeFirst()
            if (state.isTargetPassed(request.targetIndex)) continue

            return startSpeaking(request)
        }

        return null
    }

    /** 発話中マークを立てて開始指示を返す。発話中が無い前提 (PLAY / キュー消化) で使う。 */
    private fun startSpeaking(request: VoiceAnnouncementRequest): VoiceAnnouncementCommand {
        state = state.withSpeakingStarted(speakingOf(request))

        return VoiceAnnouncementCommand.StartSpeaking(request)
    }

    /** 発話中を中断する前提で発話中マークを差し替え、中断+開始指示を返す。 */
    private fun interruptAndSpeak(request: VoiceAnnouncementRequest): VoiceAnnouncementCommand {
        state = state.withSpeakingStarted(speakingOf(request))

        return VoiceAnnouncementCommand.InterruptAndSpeak(request)
    }

    /** 発話中のため処理済みにしてキューへ積む。発話は起こさないので指示は返さない。 */
    private fun enqueue(request: VoiceAnnouncementRequest): VoiceAnnouncementCommand? {
        state = state.withStageFired(request.stageId)
        pendingQueue.addLast(request)

        return null
    }

    /** リクエストから発話中レコードを作る。緊急度は保持せず位置と種別だけを持ち回る。 */
    private fun speakingOf(request: VoiceAnnouncementRequest): SpeakingAnnouncement = SpeakingAnnouncement(
        stageId = request.stageId,
        targetGeometryMeters = request.targetGeometryMeters,
        kind = request.kind,
    )
}
