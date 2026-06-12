package me.matsumo.onenavi.core.navigation.voice.scheduler

import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.matsumo.onenavi.core.navigation.extnav.ExtNavProgressSnapshot
import me.matsumo.onenavi.core.navigation.extnav.ExtNavRouteDistanceContext
import me.matsumo.onenavi.core.navigation.extnav.ExtNavRoutePayload
import me.matsumo.onenavi.core.navigation.voice.config.VoiceAnnouncementConfig
import me.matsumo.onenavi.core.navigation.voice.debug.VoiceAnnouncementDebugSnapshot
import me.matsumo.onenavi.core.navigation.voice.plan.AnnouncementStage
import me.matsumo.onenavi.core.navigation.voice.plan.AnnouncementTarget
import me.matsumo.onenavi.core.navigation.voice.plan.VoiceAnnouncementPlan
import me.matsumo.onenavi.core.navigation.voice.plan.VoiceAnnouncementPlanBuilder
import kotlin.math.roundToInt

/**
 * 案内 manager から音声案内をまとめて駆動する facade。発話プラン構築・tick 変換・発話実行系の配線を 1 つに束ね、
 * [me.matsumo.onenavi.core.navigation.newguidance.NewGuidanceManager] からは start / onSnapshot / stop だけで扱える。
 *
 * attach 時に payload と距離変換 context から発話プランを組み、tracker snapshot を発話 tick へ変換して実行系へ流す。
 * 距離変換 context は表示と発話で同じものを共有し、表示地点と発話地点がずれないようにする (合意条件 #4)。
 *
 * @property planBuilder payload + 距離変換 context から発話プランを構築する
 * @property tickFactory snapshot を発話 tick へ変換する
 * @property speechRunner 発話プランと tick を受けて発話を再生する実行系
 * @property prefetcher 近傍発話の音声合成を事前に走らせる先読み部品
 * @property config category gate / リードタイム等の発話設定
 * @property isDebugSnapshotEnabled デバッグスナップショットを計算してよい場合に true を返す
 */
internal class VoiceAnnouncementController(
    private val planBuilder: VoiceAnnouncementPlanBuilder,
    private val tickFactory: VoiceTickFactory,
    private val speechRunner: VoiceAnnouncementSpeechRunner,
    private val prefetcher: VoiceAnnouncementPrefetcher,
    private val config: VoiceAnnouncementConfig,
    private val isDebugSnapshotEnabled: () -> Boolean = { false },
) {

    private val _debugSnapshot = MutableStateFlow<VoiceAnnouncementDebugSnapshot?>(null)

    /** UI が読む TTS 発話予定のデバッグスナップショット。 */
    val debugSnapshot: StateFlow<VoiceAnnouncementDebugSnapshot?> = _debugSnapshot.asStateFlow()

    /**
     * 音声案内を開始する。発話プランを構築して実行系へ attach する。
     *
     * @param payload 案内対象の payload (guidancePoints / announcementBlocks を含む)
     * @param distanceContext tracker attach 時と同一の source→geometry 距離変換 context
     * @param announceOpening true なら案内発話に先立って開始アナウンスを発話する (初回開始時のみ true、リルート貼り直しは false)
     * @param initialSnapshot attach 時点の tracker snapshot。あれば現在距離基準で初回先読みする
     */
    fun start(
        payload: ExtNavRoutePayload,
        distanceContext: ExtNavRouteDistanceContext,
        announceOpening: Boolean,
        initialSnapshot: ExtNavProgressSnapshot? = null,
    ) {
        val plan = planBuilder.build(
            payload = payload,
            distanceContext = distanceContext,
            config = config,
        )
        val initialCumulativeMeters = initialSnapshot
            ?.let { snapshot -> tickFactory.from(snapshot).currentCumulativeMeters }

        _debugSnapshot.value = null
        logPlan(plan)
        speechRunner.attach(
            plan = plan,
            announceOpening = announceOpening,
            initialCumulativeMeters = initialCumulativeMeters,
        )
        prefetcher.attach(
            plan = plan,
            currentCumulativeMeters = initialCumulativeMeters,
        )
    }

    /**
     * tracker snapshot を 1 件受け取り、発話 tick へ変換して実行系へ流す。
     *
     * @param snapshot tracker が発行した進捗 snapshot
     */
    fun onSnapshot(snapshot: ExtNavProgressSnapshot) {
        val tick = tickFactory.from(snapshot)
        speechRunner.submit(tick)
        prefetcher.onTick(tick)
        requestDebugSnapshotIfEnabled()
    }

    /** 経由地通過アナウンスを発話する。進行中の案内発話へ割り込んでから発話する。 */
    fun announceWaypointApproach() {
        speechRunner.announceWaypointApproach()
    }

    /** 目的地到達アナウンスを発話する。案内 session 終了と同時でも鳴り切る。 */
    fun announceDestinationReached() {
        speechRunner.announceDestinationReached()
    }

    /** 音声案内を停止し、発話プランと進行中の発話を破棄する。 */
    fun stop() {
        speechRunner.detach()
        prefetcher.detach()
        _debugSnapshot.value = null
    }

    /** feature toggle が有効な場合だけ、発話実行系へ debug snapshot の読み取りを依頼する。 */
    private fun requestDebugSnapshotIfEnabled() {
        if (!isDebugSnapshotEnabled()) {
            _debugSnapshot.value = null
            return
        }

        val isRequested = speechRunner.requestDebugSnapshot(
            fetchStateProvider = prefetcher::fetchStateOf,
            receiver = { snapshot -> _debugSnapshot.value = snapshot },
        )
        if (!isRequested) _debugSnapshot.value = null
    }

    // ---------------------------------------------------------------------
    // 診断ログ (issue #41 Phase 3 実機検証用、確認後に撤去予定)
    // ---------------------------------------------------------------------

    /** attach 時にプラン全体をダンプする。重複ブロック・全段構成・トリガ距離を裏取りするため。 */
    private fun logPlan(plan: VoiceAnnouncementPlan) {
        Napier.d(tag = TAG) { "plan routeId=${plan.routeId} targets=${plan.targets.size}" }
        logCategoryDiagnostics(plan)

        for (targetIndex in plan.targets.indices) {
            val target = plan.targets[targetIndex]
            Napier.d(tag = TAG) {
                "target[$targetIndex] gp=${target.guidancePointIndex} geo=${target.geometryMeters} " +
                    "stages=${target.stages.size}"
            }
            logStages(target)
        }
    }

    /** category ごとに stage 数・window 有無・名目トリガ距離を集計して attach 時に出力する。 */
    private fun logCategoryDiagnostics(plan: VoiceAnnouncementPlan) {
        val diagnosticsByCategory = linkedMapOf<String, VoiceAnnouncementCategoryPlanDiagnostics>()

        for (target in plan.targets) {
            for (stage in target.stages) {
                recordCategoryDiagnostics(diagnosticsByCategory, target, stage)
            }
        }

        for ((categoryName, diagnostics) in diagnosticsByCategory) {
            Napier.d(tag = TAG) {
                "category[$categoryName] blocks=${diagnostics.blockCount} " +
                    "windowed=${diagnostics.windowedBlockCount} withoutWindow=${diagnostics.withoutWindowBlockCount()} " +
                    "triggerRemainingMeters=${diagnostics.triggerRemainingMeters.sorted()}"
            }
        }
    }

    /** 1 stage を category 別診断値へ畳み込む。 */
    private fun recordCategoryDiagnostics(
        diagnosticsByCategory: MutableMap<String, VoiceAnnouncementCategoryPlanDiagnostics>,
        target: AnnouncementTarget,
        stage: AnnouncementStage,
    ) {
        val remainingMeters = target.geometryMeters - stage.triggerGeometryMeters
        val triggerRemainingMeters = remainingMeters.coerceAtLeast(0.0).roundToInt()

        if (stage.categories.isEmpty()) {
            recordCategoryDiagnostics(
                diagnosticsByCategory = diagnosticsByCategory,
                categoryName = UNCATEGORIZED_CATEGORY_NAME,
                stage = stage,
                triggerRemainingMeters = triggerRemainingMeters,
            )
            return
        }

        for (category in stage.categories) {
            recordCategoryDiagnostics(
                diagnosticsByCategory = diagnosticsByCategory,
                categoryName = category.name,
                stage = stage,
                triggerRemainingMeters = triggerRemainingMeters,
            )
        }
    }

    /** category 名 1 つぶんの診断値を更新する。 */
    private fun recordCategoryDiagnostics(
        diagnosticsByCategory: MutableMap<String, VoiceAnnouncementCategoryPlanDiagnostics>,
        categoryName: String,
        stage: AnnouncementStage,
        triggerRemainingMeters: Int,
    ) {
        val diagnostics = diagnosticsByCategory.getOrPut(categoryName) {
            VoiceAnnouncementCategoryPlanDiagnostics()
        }
        diagnostics.blockCount += 1
        diagnostics.triggerRemainingMeters += triggerRemainingMeters

        if (stage.middleWindow != null) {
            diagnostics.windowedBlockCount += 1
        }
    }

    /** target 内の各段の id / kind / トリガ距離 / 発話プレビューを出力する。 */
    private fun logStages(target: AnnouncementTarget) {
        for (stage in target.stages) {
            Napier.d(tag = TAG) {
                "  stage id=${stage.id.value} kind=${stage.kind} trigSrc=${stage.triggerSourceMeters} " +
                    "trigGeo=${stage.triggerGeometryMeters} text=\"${stagePreview(stage)}\""
            }
        }
    }

    /** 段の発話素片の text を結合してプレビュー文字列にする。 */
    private fun stagePreview(stage: AnnouncementStage): String =
        stage.pieces.joinToString(separator = "") { piece -> piece.text }

    private companion object {

        /** プランダンプの診断ログを絞り込むためのタグ。 */
        const val TAG = "VoiceAnnouncementPlan"

        /** category が空の stage を診断ログで束ねる名前。 */
        const val UNCATEGORIZED_CATEGORY_NAME = "Uncategorized"
    }

    /**
     * attach 時のカテゴリ別プラン診断を集計する可変値。
     *
     * @property blockCount category に紐づく stage 数
     * @property windowedBlockCount MIDDLE window を持つ stage 数
     * @property triggerRemainingMeters stage の名目トリガ残距離リスト
     */
    private class VoiceAnnouncementCategoryPlanDiagnostics(
        var blockCount: Int = 0,
        var windowedBlockCount: Int = 0,
        val triggerRemainingMeters: MutableList<Int> = mutableListOf(),
    ) {

        /** window を持たない stage 数。 */
        fun withoutWindowBlockCount(): Int = blockCount - windowedBlockCount
    }
}
