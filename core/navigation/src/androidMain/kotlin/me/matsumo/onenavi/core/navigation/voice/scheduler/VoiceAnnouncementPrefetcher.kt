package me.matsumo.onenavi.core.navigation.voice.scheduler

import me.matsumo.onenavi.core.navigation.voice.debug.VoiceAnnouncementDebugFetchState
import me.matsumo.onenavi.core.navigation.voice.dispatch.VoiceAnnouncementContent
import me.matsumo.onenavi.core.navigation.voice.dispatch.VoiceAnnouncementContentRenderer
import me.matsumo.onenavi.core.navigation.voice.dispatch.VoiceAnnouncementDispatcher
import me.matsumo.onenavi.core.navigation.voice.plan.AnnouncementStage
import me.matsumo.onenavi.core.navigation.voice.plan.AnnouncementStageKind
import me.matsumo.onenavi.core.navigation.voice.plan.AnnouncementTarget
import me.matsumo.onenavi.core.navigation.voice.plan.VoiceAnnouncementId
import me.matsumo.onenavi.core.navigation.voice.plan.VoiceAnnouncementPlan
import me.matsumo.onenavi.core.navigation.voice.selector.VoiceTick

/**
 * 発話プランの近傍 stage を dispatcher の先読みキューへ流す部品。
 *
 * scheduler の発話状態は変更せず、category gate 適用後の発話内容だけを事前に dispatcher へ渡す。
 *
 * @property contentRenderer 発話 stage を実際の SSML / cue へ変換する renderer
 * @property dispatcher 先読み request の投入先
 */
internal class VoiceAnnouncementPrefetcher(
    private val contentRenderer: VoiceAnnouncementContentRenderer,
    private val dispatcher: VoiceAnnouncementDispatcher,
) {

    private var plan: VoiceAnnouncementPlan? = null
    private val prefetchedStageIds = mutableSetOf<VoiceAnnouncementId>()
    private val prefetchedSsmlKeys = mutableSetOf<String>()

    /**
     * 発話プランを attach し、現在距離が分かっている場合だけ近傍発話を先読みする。
     *
     * @param plan attach する発話プラン
     * @param currentCumulativeMeters 現在地の route 累積距離。不明な場合は snapshot 更新まで先読みしない
     */
    fun attach(
        plan: VoiceAnnouncementPlan,
        currentCumulativeMeters: Double? = null,
    ) {
        dispatcher.clearPrefetch()
        prefetchedStageIds.clear()
        prefetchedSsmlKeys.clear()
        this.plan = plan
        currentCumulativeMeters?.let { meters -> prefetchFrom(meters) }
    }

    /**
     * 現在位置から見た近傍発話を先読みする。
     *
     * @param tick 発話判定用の tick
     */
    fun onTick(tick: VoiceTick) {
        if (!tick.isRouteUsable) return

        prefetchFrom(currentCumulativeMeters = tick.currentCumulativeMeters)
    }

    /**
     * デバッグ表示向けに、発話内容の TTS 音声取得状態を返す。
     *
     * @param content category gate / 結合を適用済みの発話内容
     * @return dispatcher が保持する TTS 音声取得状態
     */
    fun fetchStateOf(content: VoiceAnnouncementContent): VoiceAnnouncementDebugFetchState =
        dispatcher.debugFetchState(content)

    /** 発話プランと先読み済み状態を破棄する。 */
    fun detach() {
        plan = null
        prefetchedStageIds.clear()
        prefetchedSsmlKeys.clear()
        dispatcher.clearPrefetch()
    }

    /** 現在距離から近い target の stage を先読みする。 */
    private fun prefetchFrom(currentCumulativeMeters: Double) {
        val currentPlan = plan ?: return
        val targets = targetsToPrefetch(
            plan = currentPlan,
            currentCumulativeMeters = currentCumulativeMeters,
        )

        for (target in targets) {
            prefetchTarget(
                target = target,
                currentCumulativeMeters = currentCumulativeMeters,
            )
        }
    }

    /** 現在位置から見て先読み対象にする target を返す。 */
    private fun targetsToPrefetch(
        plan: VoiceAnnouncementPlan,
        currentCumulativeMeters: Double,
    ): List<AnnouncementTarget> = plan.targets
        .filter { target -> target.isNearAhead(currentCumulativeMeters) }
        .take(MAX_PREFETCH_TARGETS)

    /** target 内の未来 stage を先読みする。 */
    private fun prefetchTarget(
        target: AnnouncementTarget,
        currentCumulativeMeters: Double,
    ) {
        for (stage in target.stages) {
            if (!stage.isStillUseful(currentCumulativeMeters)) continue

            prefetchStage(stage)
        }
    }

    /** stage を発話内容にして重複を避けながら先読みする。 */
    private fun prefetchStage(stage: AnnouncementStage) {
        if (!recordPrefetchedStage(stage)) return

        val content = contentRenderer.render(stage) ?: return
        val ssml = content.ssml ?: return
        if (ssml.isBlank()) return
        if (!recordPrefetched(ssml)) return

        dispatcher.prefetch(content)
    }

    /** stage が未先読みなら記録して true を返す。 */
    private fun recordPrefetchedStage(stage: AnnouncementStage): Boolean =
        prefetchedStageIds.add(stage.id)

    /** SSML が未先読みなら記録して true を返す。 */
    private fun recordPrefetched(ssml: String): Boolean =
        prefetchedSsmlKeys.add(ssml)

    /** target が現在位置より前方かつ近傍範囲内かを返す。 */
    private fun AnnouncementTarget.isNearAhead(currentCumulativeMeters: Double): Boolean {
        val distanceAheadMeters = geometryMeters - currentCumulativeMeters
        if (distanceAheadMeters <= 0.0) return false

        return distanceAheadMeters <= PREFETCH_LOOKAHEAD_METERS
    }

    /** stage が今後発話される可能性をまだ持っているかを返す。 */
    private fun AnnouncementStage.isStillUseful(currentCumulativeMeters: Double): Boolean = when (kind) {
        AnnouncementStageKind.MIDDLE -> middleWindow?.exitGeometryMeters?.let { exitMeters ->
            currentCumulativeMeters < exitMeters
        } == true
        AnnouncementStageKind.FINAL -> true
    }

    /** 先読み範囲の定数定義。 */
    private companion object {

        /** 先読みする最大 target 数。 */
        const val MAX_PREFETCH_TARGETS = 3

        /** 先読みする最大前方距離。 */
        const val PREFETCH_LOOKAHEAD_METERS = 5_000.0
    }
}
