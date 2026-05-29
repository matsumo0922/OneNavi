package me.matsumo.onenavi.core.navigation.voice.selector

import me.matsumo.onenavi.core.navigation.voice.config.VoiceAnnouncementConfig
import me.matsumo.onenavi.core.navigation.voice.plan.AnnouncementStage
import me.matsumo.onenavi.core.navigation.voice.plan.AnnouncementStageKind
import me.matsumo.onenavi.core.navigation.voice.plan.AnnouncementTarget
import me.matsumo.onenavi.core.navigation.voice.plan.VoiceAnnouncementPlan
import me.matsumo.onenavi.core.navigation.voice.suppression.VoiceAnnouncementSpeechState
import kotlin.math.max

/**
 * 発話プランと現 tick・既発話状態から、その tick で発話すべき最も緊急な距離段を 1 件選ぶ。
 *
 * 位置に対する純粋な判定器で、状態は引数の [VoiceAnnouncementSpeechState] にのみ依存する。
 * barge-in / skip の最終判断は suppression レイヤが担い、本クラスは「何が鳴りたいか」までを返す。
 */
internal class VoiceAnnouncementSelector(
    private val config: VoiceAnnouncementConfig,
) {

    /**
     * 現 tick で発話すべき距離段を選ぶ。該当が無ければ null。
     *
     * route が発話不能状態なら常に null。各 target について通過済み・既発話を除外し、トリガ条件
     * (MIDDLE は越え判定、FINAL は到達リードタイム逆算) を満たす段のうち最緊急を返す。
     */
    fun select(
        plan: VoiceAnnouncementPlan,
        tick: VoiceTick,
        state: VoiceAnnouncementSpeechState,
    ): VoiceAnnouncementSelection? {
        if (!tick.isRouteUsable) return null

        var best: VoiceAnnouncementSelection? = null

        for (targetIndex in plan.targets.indices) {
            if (state.isTargetPassed(targetIndex)) continue

            val target = plan.targets[targetIndex]
            if (!isTargetAhead(target, tick)) continue

            val candidate = selectStageForTarget(targetIndex, target, tick, state)
            best = moreUrgentOf(best, candidate)
        }

        return best
    }

    /**
     * この tick で通過し終えた (= これ以上発話する意味が無い) 案内地点の index を返す。
     *
     * scheduler はこれを [VoiceAnnouncementSpeechState.withTargetPassed] に流し、通過済み地点の
     * 未発話段を恒久的に抑止する。判定は前 tick→現 tick の区間で GP 位置を跨いだかで行う。
     */
    fun passedTargetIndices(plan: VoiceAnnouncementPlan, tick: VoiceTick): List<Int> {
        val passed = mutableListOf<Int>()

        for (targetIndex in plan.targets.indices) {
            val target = plan.targets[targetIndex]
            if (isTargetJustPassed(target, tick)) passed += targetIndex
        }

        return passed
    }

    /**
     * target 内で発話すべき最緊急の段を選ぶ。該当が無ければ null。
     *
     * 1 tick の移動量が大きく複数段を同時に跨いだ場合でも、最も緊急な段だけを採用する。
     */
    private fun selectStageForTarget(
        targetIndex: Int,
        target: AnnouncementTarget,
        tick: VoiceTick,
        state: VoiceAnnouncementSpeechState,
    ): VoiceAnnouncementSelection? {
        var best: VoiceAnnouncementSelection? = null

        for (stage in target.stages) {
            if (state.isStageFired(stage.id)) continue
            if (!isStageTriggered(stage, target, tick)) continue

            val urgency = urgencyOf(stage, target, tick)
            val candidate = VoiceAnnouncementSelection(
                targetIndex = targetIndex,
                stage = stage,
                urgency = urgency,
            )
            best = moreUrgentOf(best, candidate)
        }

        return best
    }

    /** 2 候補のうち緊急度が高い方を返す。null は「候補なし」を表す。 */
    private fun moreUrgentOf(
        current: VoiceAnnouncementSelection?,
        candidate: VoiceAnnouncementSelection?,
    ): VoiceAnnouncementSelection? {
        if (candidate == null) return current
        if (current == null) return candidate

        val isCandidateMoreUrgent = candidate.urgency > current.urgency

        return if (isCandidateMoreUrgent) candidate else current
    }

    /** 案内地点がまだ前方にあるか (通過していないか) を返す。 */
    private fun isTargetAhead(target: AnnouncementTarget, tick: VoiceTick): Boolean =
        tick.currentCumulativeMeters < target.geometryMeters

    /** この tick で案内地点の位置を跨いだか (= ちょうど通過したか) を返す。 */
    private fun isTargetJustPassed(target: AnnouncementTarget, tick: VoiceTick): Boolean {
        val wasAhead = target.geometryMeters > tick.previousCumulativeMeters
        val nowReached = target.geometryMeters <= tick.currentCumulativeMeters

        return wasAhead && nowReached
    }

    /** 段のトリガ条件を満たすかを返す。MIDDLE は越え判定、FINAL は到達リードタイム逆算。 */
    private fun isStageTriggered(
        stage: AnnouncementStage,
        target: AnnouncementTarget,
        tick: VoiceTick,
    ): Boolean = when (stage.kind) {
        AnnouncementStageKind.MIDDLE -> isMiddleCrossed(stage, tick)
        AnnouncementStageKind.FINAL -> isFinalReached(target, tick)
    }

    /** 中間段: トリガ距離を前 tick→現 tick の区間 (previous, current] で跨いだかを返す。 */
    private fun isMiddleCrossed(stage: AnnouncementStage, tick: VoiceTick): Boolean {
        val triggerMeters = stage.triggerGeometryMeters
        val justEntered = triggerMeters > tick.previousCumulativeMeters
        val reached = triggerMeters <= tick.currentCumulativeMeters

        return justEntered && reached
    }

    /** 直前段: 現在地が到達リードタイムぶん手前に達したかを返す。手前距離は速度から逆算する。 */
    private fun isFinalReached(target: AnnouncementTarget, tick: VoiceTick): Boolean {
        val leadDistanceMeters = finalLeadDistanceMeters(tick.speedMetersPerSecond)
        val fireBoundaryMeters = target.geometryMeters - leadDistanceMeters

        return tick.currentCumulativeMeters >= fireBoundaryMeters
    }

    /** 速度から FINAL の手前距離を求める。速度が無い / 低速でも最小手前距離を保証する。 */
    private fun finalLeadDistanceMeters(speedMetersPerSecond: Double?): Double {
        val speed = speedMetersPerSecond ?: return config.minLeadMeters
        val leadByTimeMeters = speed * config.leadTimeSeconds

        return max(leadByTimeMeters, config.minLeadMeters)
    }

    /** 段の緊急度を残距離と種別から求める。 */
    private fun urgencyOf(
        stage: AnnouncementStage,
        target: AnnouncementTarget,
        tick: VoiceTick,
    ): VoiceAnnouncementUrgency {
        val remainingMeters = target.geometryMeters - tick.currentCumulativeMeters

        return VoiceAnnouncementUrgency(
            remainingMeters = remainingMeters,
            kind = stage.kind,
        )
    }
}
