package me.matsumo.onenavi.core.navigation.voice.scheduler

import io.github.aakira.napier.Napier
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import me.matsumo.onenavi.core.navigation.voice.config.VoiceAnnouncementConfig
import me.matsumo.onenavi.core.navigation.voice.debug.VoiceAnnouncementDebugFetchState
import me.matsumo.onenavi.core.navigation.voice.debug.VoiceAnnouncementDebugItem
import me.matsumo.onenavi.core.navigation.voice.debug.VoiceAnnouncementDebugRecentItem
import me.matsumo.onenavi.core.navigation.voice.debug.VoiceAnnouncementDebugResult
import me.matsumo.onenavi.core.navigation.voice.debug.VoiceAnnouncementDebugSnapshot
import me.matsumo.onenavi.core.navigation.voice.debug.VoiceAnnouncementDebugStageKind
import me.matsumo.onenavi.core.navigation.voice.dispatch.VoiceAnnouncementContent
import me.matsumo.onenavi.core.navigation.voice.dispatch.VoiceAnnouncementContentRenderer
import me.matsumo.onenavi.core.navigation.voice.dispatch.VoiceAnnouncementRequest
import me.matsumo.onenavi.core.navigation.voice.plan.AnnouncementStage
import me.matsumo.onenavi.core.navigation.voice.plan.AnnouncementStageKind
import me.matsumo.onenavi.core.navigation.voice.plan.AnnouncementTarget
import me.matsumo.onenavi.core.navigation.voice.plan.VoiceAnnouncementId
import me.matsumo.onenavi.core.navigation.voice.plan.VoiceAnnouncementPlan
import me.matsumo.onenavi.core.navigation.voice.selector.VoiceAnnouncementPreviewSelection
import me.matsumo.onenavi.core.navigation.voice.selector.VoiceAnnouncementSelection
import me.matsumo.onenavi.core.navigation.voice.selector.VoiceAnnouncementSelector
import me.matsumo.onenavi.core.navigation.voice.selector.VoiceAnnouncementUrgency
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
 * 4. MIDDLE を発話中にした場合は同じ tick で他に発話可能な MIDDLE を再選抜し、後続発話としてキューへ積む。
 */
internal class VoiceAnnouncementScheduler(
    private val selector: VoiceAnnouncementSelector,
    private val policy: VoiceAnnouncementSelectionPolicy,
    private val contentRenderer: VoiceAnnouncementContentRenderer,
    private val config: VoiceAnnouncementConfig,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) {

    private var plan: VoiceAnnouncementPlan? = null
    private var state = VoiceAnnouncementSpeechState()
    private var latestTick: VoiceTick? = null
    private val pendingQueue = ArrayDeque<VoiceAnnouncementRequest>()
    private val recentAnnouncements = ArrayDeque<VoiceAnnouncementDebugRecentItem>()
    private val speakingDebugItems = mutableMapOf<VoiceAnnouncementId, VoiceAnnouncementDebugPendingItem>()

    /**
     * 発話プランを attach し、発話状態とキューを初期化する。route が切り替わるたびに呼ぶ。
     *
     * @param plan attach する発話プラン
     * @param initialCumulativeMeters attach 時点の現在位置。既に大きく食い込んだ FINAL を抑止するために使う
     */
    fun attach(
        plan: VoiceAnnouncementPlan,
        initialCumulativeMeters: Double? = null,
    ) {
        this.plan = plan
        state = VoiceAnnouncementSpeechState()
        latestTick = null
        pendingQueue.clear()
        recentAnnouncements.clear()
        speakingDebugItems.clear()

        if (initialCumulativeMeters != null) {
            skipLateFinalStagesAtAttach(plan, initialCumulativeMeters)
        }
    }

    /** 発話プラン・状態・キューを破棄する。案内終了時に呼ぶ。 */
    fun detach() {
        plan = null
        state = VoiceAnnouncementSpeechState()
        latestTick = null
        pendingQueue.clear()
        recentAnnouncements.clear()
        speakingDebugItems.clear()
    }

    /**
     * 位置 tick を 1 件処理し、発話実行指示を返す。実行する発話が無ければ null。
     *
     * @param tick 現在地スカラを発話レイヤ向けに絞った tick
     * @return 実行系への発話指示。発話を起こさない (候補なし / ENQUEUE / 発話内容なし) 場合は null
     */
    fun onTick(tick: VoiceTick): VoiceAnnouncementCommand? {
        val currentPlan = plan ?: return null
        latestTick = tick
        recordPassedTargets(currentPlan, tick)

        val selection = selector.select(currentPlan, tick, state)
        if (state.speaking == null) {
            queueCandidateAt(tick)?.let { queuedRequest ->
                if (selection == null || !selection.isMoreUrgentThan(queuedRequest, tick)) {
                    return dispatchQueuedRequest(currentPlan, queuedRequest, tick)
                }
            }
        }

        if (selection == null) return null

        return dispatchSelection(currentPlan, selection, tick)
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
    fun onSpeechFinished(
        stageId: VoiceAnnouncementId,
        wasSpoken: Boolean = true,
    ): VoiceAnnouncementCommand? {
        recordSpeechFinished(stageId, wasSpoken)
        state = state.withSpeakingFinished(stageId)
        if (state.speaking != null) return null

        val tick = latestTick ?: return null

        return dispatchNextFromQueue(tick)
    }

    /**
     * マイルストーン発話 (経由地通過 / 目的地到達) が進行中の案内発話へ割り込んだことをコアへ通知する。
     *
     * 発話中マークを解除し、割り込み後の tick で案内発話を再開できるようにする。割り込まれた段は既に処理確定済みのため
     * 再選択されない。
     */
    fun onMilestoneInterrupted() {
        recordCurrentSpeechNotSpoken()
        state = state.withSpeakingCleared()
    }

    /**
     * デバッグ表示用に直近の発話予定を返す。
     *
     * @param fetchStateProvider render 済み発話内容に対する TTS 取得状態の読み取り関数
     * @return 現在の発話予定。プランまたは tick が無い場合は null
     */
    fun debugSnapshot(
        fetchStateProvider: (VoiceAnnouncementContent) -> VoiceAnnouncementDebugFetchState,
    ): VoiceAnnouncementDebugSnapshot? {
        val currentPlan = plan ?: return null
        val tick = latestTick ?: return null
        val nowMillis = currentTimeMillis()
        dropExpiredRecentAnnouncements(nowMillis)

        val previews = selector.previewUpcoming(
            plan = currentPlan,
            tick = tick,
            state = state,
            limit = DEBUG_SNAPSHOT_SCAN_LIMIT,
        )
        val items = previews
            .mapNotNull { preview -> debugItemOf(preview, fetchStateProvider) }
            .take(DEBUG_SNAPSHOT_ITEM_LIMIT)
            .toImmutableList()

        return VoiceAnnouncementDebugSnapshot(
            upcomingAnnouncements = items,
            recentAnnouncements = recentAnnouncements.toImmutableList(),
        )
    }

    /** selector の候補を UI 表示用 item に変換する。 */
    private fun debugItemOf(
        preview: VoiceAnnouncementPreviewSelection,
        fetchStateProvider: (VoiceAnnouncementContent) -> VoiceAnnouncementDebugFetchState,
    ): VoiceAnnouncementDebugItem? {
        val content = contentRenderer.render(preview.selection.stage) ?: return null
        val stage = preview.selection.stage

        return VoiceAnnouncementDebugItem(
            stageId = stage.id.value,
            targetIndex = preview.selection.targetIndex,
            text = content.displayText,
            remainingMeters = preview.remainingMeters,
            stageKind = stage.kind.toDebugStageKind(),
            fetchState = fetchStateProvider(content),
            isRouteOrderBlocked = preview.isRouteOrderBlocked,
            categories = stage.categories
                .map { category -> category.name }
                .toImmutableList(),
        )
    }

    /** 内部 stage 種別を公開 debug model の種別へ変換する。 */
    private fun AnnouncementStageKind.toDebugStageKind(): VoiceAnnouncementDebugStageKind = when (this) {
        AnnouncementStageKind.MIDDLE -> VoiceAnnouncementDebugStageKind.MIDDLE
        AnnouncementStageKind.FINAL -> VoiceAnnouncementDebugStageKind.FINAL
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
     * 処理済みマークだけ付けて畳む。発話内容が**同一案内地点で既に発話確定済みの内容キーと一致**する場合も
     * 発話を起こさず畳む。距離違い候補は plan 構築時に raw text で dedup 済みだが、category gate 適用後に
     * 別段が同一内容へ畳まれることがあるため、render 後の内容キーでもう一度抑止する (外部ナビ API 参照実装の
     * 同一文言抑止に対応)。発話に進む場合は実発話開始時に内容キーを発話済みとして記録する。
     */
    private fun dispatchSelection(
        plan: VoiceAnnouncementPlan,
        selection: VoiceAnnouncementSelection,
        tick: VoiceTick,
    ): VoiceAnnouncementCommand? {
        val content = contentRenderer.render(selection.stage)
        if (content == null) {
            logSkippedEmpty(selection, tick)
            recordSelectionNotSpoken(selection, stageText(selection.stage))
            state = state.withStageFired(selection.stage.id)
            return null
        }

        if (state.isContentSpoken(selection.targetIndex, content.dedupeKey)) {
            logSkippedDuplicateContent(selection, content, tick)
            recordSelectionNotSpoken(selection, content.displayText)
            state = state.withStageFired(selection.stage.id)
            return null
        }

        val request = VoiceAnnouncementRequest.from(selection, content)
        val decision = policy.decide(state, selection, tick)
        logDecision(decision, selection, tick)

        return when (decision) {
            VoiceAnnouncementDispatchDecision.PLAY -> {
                val command = startSpeaking(request)
                enqueueDeferredMiddleCandidates(plan, tick, request)

                command
            }
            VoiceAnnouncementDispatchDecision.BARGE_IN -> interruptAndSpeak(request)
            VoiceAnnouncementDispatchDecision.ENQUEUE -> {
                enqueue(request)
                enqueueDeferredMiddleCandidates(plan, tick, request)

                null
            }
        }
    }

    /**
     * キュー先頭のうち、最新 tick でも有効な最初の発話を取り出して開始指示にする。無ければ null。
     *
     * MIDDLE は距離窓にいる間だけ有効な予告なので、発話待ちの間に窓を過ぎたものはここで捨てる。
     */
    private fun dispatchNextFromQueue(tick: VoiceTick): VoiceAnnouncementCommand? {
        val currentPlan = plan ?: return null
        val request = queueCandidateAt(tick) ?: return null

        return dispatchQueuedRequest(currentPlan, request, tick)
    }

    /**
     * キュー先頭から最新 tick で発話可能な最初の候補を返す。
     *
     * 古くなった候補はここで破棄するが、実際に鳴らしていないため発話済み content にはしない。
     */
    private fun queueCandidateAt(tick: VoiceTick): VoiceAnnouncementRequest? {
        if (!tick.isRouteUsable) return null

        while (pendingQueue.isNotEmpty()) {
            val request = pendingQueue.first()
            if (state.isTargetPassed(request.targetIndex)) {
                pendingQueue.removeFirst()
                recordRequestNotSpoken(request)
                Napier.d(tag = TAG) { "drain-skip passed stage=${request.stageId.value} target=${request.targetIndex}" }
                continue
            }
            if (request.isStaleAt(tick)) {
                pendingQueue.removeFirst()
                recordRequestNotSpoken(request)
                Napier.d(tag = TAG) {
                    "drain-skip stale stage=${request.stageId.value} kind=${request.kind} current=${tick.currentCumulativeMeters}"
                }
                continue
            }
            if (state.isContentSpoken(request.targetIndex, request.content.dedupeKey)) {
                pendingQueue.removeFirst()
                recordRequestNotSpoken(request)
                logSkippedQueuedDuplicateContent(request, tick)
                continue
            }

            return request
        }

        return null
    }

    /** キュー投入後に MIDDLE の有効範囲を過ぎていたら true を返す。target 通過済みは呼び出し側で判定する。 */
    private fun VoiceAnnouncementRequest.isStaleAt(tick: VoiceTick): Boolean = when (kind) {
        AnnouncementStageKind.MIDDLE -> isMiddleRequestStaleAt(tick)
        AnnouncementStageKind.FINAL -> false
    }

    /** MIDDLE の stale 判定。ENQUEUE 済み候補は窓終端後も短い猶予を許す。 */
    private fun VoiceAnnouncementRequest.isMiddleRequestStaleAt(tick: VoiceTick): Boolean {
        val window = middleWindow ?: return true
        val currentCumulativeMeters = tick.currentCumulativeMeters
        if (currentCumulativeMeters < window.enterGeometryMeters) return true

        val exitGeometryMeters = window.exitGeometryMeters + config.queuedStaleGraceMeters

        return currentCumulativeMeters >= exitGeometryMeters
    }

    /** attach 時点で名目距離より深く食い込んだ FINAL を処理済みにして、距離句の破綻を避ける。 */
    private fun skipLateFinalStagesAtAttach(
        plan: VoiceAnnouncementPlan,
        currentCumulativeMeters: Double,
    ) {
        for (target in plan.targets) {
            for (stage in target.stages) {
                if (!shouldSkipLateFinalStage(target, stage, currentCumulativeMeters)) continue

                state = state.withStageFired(stage.id)
                Napier.d(tag = TAG) {
                    "attach-skip late-final stage=${stage.id.value} targetGeo=${target.geometryMeters} " +
                        "triggerGeo=${stage.triggerGeometryMeters} current=$currentCumulativeMeters"
                }
            }
        }
    }

    /** attach 時点の現在地から、距離句が大きく外れる FINAL かを返す。 */
    private fun shouldSkipLateFinalStage(
        target: AnnouncementTarget,
        stage: AnnouncementStage,
        currentCumulativeMeters: Double,
    ): Boolean {
        if (stage.kind != AnnouncementStageKind.FINAL) return false
        if (currentCumulativeMeters <= stage.triggerGeometryMeters) return false

        val nominalLeadMeters = target.geometryMeters - stage.triggerGeometryMeters
        if (nominalLeadMeters < config.lateFinalSkipMinimumTriggerMeters) return false

        val remainingMeters = target.geometryMeters - currentCumulativeMeters
        val skipBoundaryMeters = nominalLeadMeters * config.lateFinalSkipRatio

        return remainingMeters < skipBoundaryMeters
    }

    /** queue candidate と新規 selection を現在 tick の緊急度で比較する。 */
    private fun VoiceAnnouncementSelection.isMoreUrgentThan(
        request: VoiceAnnouncementRequest,
        tick: VoiceTick,
    ): Boolean {
        val requestUrgency = VoiceAnnouncementUrgency.of(
            targetGeometryMeters = request.targetGeometryMeters,
            currentCumulativeMeters = tick.currentCumulativeMeters,
            kind = request.kind,
        )

        return urgency > requestUrgency
    }

    /** キュー先頭の候補を取り出して発話開始指示にする。 */
    private fun dispatchQueuedRequest(
        plan: VoiceAnnouncementPlan,
        request: VoiceAnnouncementRequest,
        tick: VoiceTick,
    ): VoiceAnnouncementCommand {
        pendingQueue.removeFirst()
        Napier.d(tag = TAG) { "drain stage=${request.stageId.value}" }
        val command = startSpeaking(request)
        enqueueDeferredMiddleCandidates(plan, tick, request)

        return command
    }

    /** MIDDLE が発話中の同一 tick で発話可能な別 MIDDLE を、発話終了後の後続発話としてキューへ積む。 */
    private fun enqueueDeferredMiddleCandidates(
        plan: VoiceAnnouncementPlan,
        tick: VoiceTick,
        request: VoiceAnnouncementRequest,
    ) {
        if (request.kind != AnnouncementStageKind.MIDDLE) return

        while (true) {
            val selection = selector.select(plan, tick, state) ?: return
            if (selection.stage.kind != AnnouncementStageKind.MIDDLE) return
            if (!enqueueDeferredMiddleCandidate(selection, tick)) return
        }
    }

    /** 同一 tick の MIDDLE 候補 1 件を queue へ積めるなら積み、続行可否を返す。 */
    private fun enqueueDeferredMiddleCandidate(
        selection: VoiceAnnouncementSelection,
        tick: VoiceTick,
    ): Boolean {
        val content = contentRenderer.render(selection.stage)
        if (content == null) {
            logSkippedEmpty(selection, tick)
            recordSelectionNotSpoken(selection, stageText(selection.stage))
            state = state.withStageFired(selection.stage.id)
            return true
        }

        if (state.isContentSpoken(selection.targetIndex, content.dedupeKey)) {
            logSkippedDuplicateContent(selection, content, tick)
            recordSelectionNotSpoken(selection, content.displayText)
            state = state.withStageFired(selection.stage.id)
            return true
        }

        val decision = policy.decide(state, selection, tick)
        if (decision != VoiceAnnouncementDispatchDecision.ENQUEUE) return false

        logDecision(decision, selection, tick)
        enqueue(VoiceAnnouncementRequest.from(selection, content))

        return true
    }

    /** 発話中マークを立てて開始指示を返す。発話中が無い前提 (PLAY / キュー消化) で使う。 */
    private fun startSpeaking(request: VoiceAnnouncementRequest): VoiceAnnouncementCommand {
        state = state
            .withContentSpoken(request.targetIndex, request.content.dedupeKey)
            .withSpeakingStarted(speakingOf(request))
        speakingDebugItems[request.stageId] = pendingDebugItemOf(request)

        return VoiceAnnouncementCommand.StartSpeaking(request)
    }

    /** 発話中を中断する前提で発話中マークを差し替え、中断+開始指示を返す。 */
    private fun interruptAndSpeak(request: VoiceAnnouncementRequest): VoiceAnnouncementCommand {
        recordCurrentSpeechNotSpoken()
        state = state
            .withContentSpoken(request.targetIndex, request.content.dedupeKey)
            .withSpeakingStarted(speakingOf(request))
        speakingDebugItems[request.stageId] = pendingDebugItemOf(request)

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

    /** 発話完了通知を debug 表示用の直近結果へ反映する。 */
    private fun recordSpeechFinished(stageId: VoiceAnnouncementId, wasSpoken: Boolean) {
        val pendingItem = speakingDebugItems.remove(stageId) ?: return
        val result = if (wasSpoken) {
            VoiceAnnouncementDebugResult.SPOKEN
        } else {
            VoiceAnnouncementDebugResult.NOT_SPOKEN
        }

        addRecentAnnouncement(pendingItem, result)
    }

    /** 現在発話中の段を未発話終了として記録する。 */
    private fun recordCurrentSpeechNotSpoken() {
        val stageId = state.speaking?.stageId ?: return
        val pendingItem = speakingDebugItems.remove(stageId) ?: return

        addRecentAnnouncement(pendingItem, VoiceAnnouncementDebugResult.NOT_SPOKEN)
    }

    /** 選択済みだが実発話しない段を未発話終了として記録する。 */
    private fun recordSelectionNotSpoken(selection: VoiceAnnouncementSelection, text: String) {
        addRecentAnnouncement(
            item = pendingDebugItemOf(selection, text),
            result = VoiceAnnouncementDebugResult.NOT_SPOKEN,
        )
    }

    /** キューから破棄した段を未発話終了として記録する。 */
    private fun recordRequestNotSpoken(request: VoiceAnnouncementRequest) {
        speakingDebugItems.remove(request.stageId)
        addRecentAnnouncement(
            item = pendingDebugItemOf(request),
            result = VoiceAnnouncementDebugResult.NOT_SPOKEN,
        )
    }

    /** 直近結果を追加し、表示期限を過ぎた結果を落とす。 */
    private fun addRecentAnnouncement(
        item: VoiceAnnouncementDebugPendingItem,
        result: VoiceAnnouncementDebugResult,
    ) {
        val nowMillis = currentTimeMillis()
        dropExpiredRecentAnnouncements(nowMillis)
        recentAnnouncements.addLast(
            VoiceAnnouncementDebugRecentItem(
                stageId = item.stageId.value,
                targetIndex = item.targetIndex,
                text = item.text,
                stageKind = item.stageKind,
                result = result,
                completedAtEpochMillis = nowMillis,
                categories = item.categories,
            ),
        )

        while (recentAnnouncements.size > DEBUG_RECENT_SNAPSHOT_ITEM_LIMIT) {
            recentAnnouncements.removeFirst()
        }
    }

    /** 表示期限を過ぎた直近結果を取り除く。 */
    private fun dropExpiredRecentAnnouncements(nowMillis: Long) {
        while (recentAnnouncements.isNotEmpty()) {
            val recentItem = recentAnnouncements.first()
            val elapsedMillis = nowMillis - recentItem.completedAtEpochMillis
            if (elapsedMillis <= DEBUG_RECENT_RESULT_RETENTION_MILLIS) return

            recentAnnouncements.removeFirst()
        }
    }

    /** 発話リクエストを結果確定前の debug 表示素材に変換する。 */
    private fun pendingDebugItemOf(request: VoiceAnnouncementRequest): VoiceAnnouncementDebugPendingItem =
        VoiceAnnouncementDebugPendingItem(
            stageId = request.stageId,
            targetIndex = request.targetIndex,
            text = request.content.displayText,
            stageKind = request.kind.toDebugStageKind(),
            categories = request.categories,
        )

    /** 選択結果を結果確定前の debug 表示素材に変換する。 */
    private fun pendingDebugItemOf(
        selection: VoiceAnnouncementSelection,
        text: String,
    ): VoiceAnnouncementDebugPendingItem = VoiceAnnouncementDebugPendingItem(
        stageId = selection.stage.id,
        targetIndex = selection.targetIndex,
        text = text,
        stageKind = selection.stage.kind.toDebugStageKind(),
        categories = selection.stage.categories
            .map { category -> category.name }
            .toImmutableList(),
    )

    /** stage の raw piece から fallback 表示文を作る。 */
    private fun stageText(stage: AnnouncementStage): String =
        stage.pieces.joinToString(separator = "") { piece -> piece.text }

    // ---------------------------------------------------------------------
    // 診断ログ (issue #41 Phase 3 実機検証用、確認後に撤去予定)
    // ---------------------------------------------------------------------

    /** 発話確定時に decision と段・距離を出力する。重複が別 stage か再発火かを stage id で見分けるため。 */
    private fun logDecision(
        decision: VoiceAnnouncementDispatchDecision,
        selection: VoiceAnnouncementSelection,
        tick: VoiceTick,
    ) {
        Napier.d(tag = TAG) {
            "decide=$decision stage=${selection.stage.id.value} kind=${selection.stage.kind} " +
                "trigGeo=${selection.stage.triggerGeometryMeters} current=${tick.currentCumulativeMeters} " +
                "remaining=${selection.urgency.remainingMeters} speed=${tick.speedMetersPerSecond}"
        }
    }

    /** 発話内容が空で畳んだ段を出力する。 */
    private fun logSkippedEmpty(selection: VoiceAnnouncementSelection, tick: VoiceTick) {
        Napier.d(tag = TAG) {
            "skip-empty stage=${selection.stage.id.value} kind=${selection.stage.kind} " +
                "current=${tick.currentCumulativeMeters}"
        }
    }

    /** 同一案内地点で発話済みの SSML と一致したため畳んだ段を出力する。 */
    private fun logSkippedDuplicateContent(
        selection: VoiceAnnouncementSelection,
        content: VoiceAnnouncementContent,
        tick: VoiceTick,
    ) {
        Napier.d(tag = TAG) {
            "skip-dupcontent stage=${selection.stage.id.value} target=${selection.targetIndex} " +
                "current=${tick.currentCumulativeMeters} content=\"${content.dedupeKey}\""
        }
    }

    /** キュー消化時に同一内容が発話済みだった段を出力する。 */
    private fun logSkippedQueuedDuplicateContent(
        request: VoiceAnnouncementRequest,
        tick: VoiceTick,
    ) {
        Napier.d(tag = TAG) {
            "drain-skip dupcontent stage=${request.stageId.value} target=${request.targetIndex} " +
                "current=${tick.currentCumulativeMeters} content=\"${request.content.dedupeKey}\""
        }
    }

    /**
     * 発話結果が確定するまで保持する debug 表示素材。
     *
     * @property stageId 発話段 id
     * @property targetIndex 発話対象の plan 内 index
     * @property text UI に出す読み上げ文
     * @property stageKind 発話段の種別
     * @property categories 発話段に紐づく category 名
     */
    private data class VoiceAnnouncementDebugPendingItem(
        val stageId: VoiceAnnouncementId,
        val targetIndex: Int,
        val text: String,
        val stageKind: VoiceAnnouncementDebugStageKind,
        val categories: ImmutableList<String>,
    )

    private companion object {

        /** 発話判定の診断ログを絞り込むためのタグ。 */
        const val TAG = "VoiceAnnouncementDecision"

        /** UI に表示する発話予定数。 */
        const val DEBUG_SNAPSHOT_ITEM_LIMIT = 5

        /** category gate で空になる段を見越して selector から読む最大候補数。 */
        const val DEBUG_SNAPSHOT_SCAN_LIMIT = 12

        /** 発話結果を debug card に残す時間。 */
        const val DEBUG_RECENT_RESULT_RETENTION_MILLIS = 3_000L

        /** debug card に残す直近結果数。 */
        const val DEBUG_RECENT_SNAPSHOT_ITEM_LIMIT = 3
    }
}
