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
     * route が発話不能状態なら常に null。各 target について通過済み・既処理を除外し、トリガ条件
     * (MIDDLE は到達判定、FINAL は到達リードタイム逆算) を満たす段のうち最緊急を返す。
     *
     * 1 tick につき最緊急 1 件だけを返す。選ばれなかった候補は既処理マークが付かない限り
     * 次 tick 以降に再評価されるため、同 tick で複数段を跨いでも取りこぼさない。
     */
    fun select(
        plan: VoiceAnnouncementPlan,
        tick: VoiceTick,
        state: VoiceAnnouncementSpeechState,
    ): VoiceAnnouncementSelection? {
        if (!tick.isRouteUsable) return null

        var best: VoiceAnnouncementSelection? = null

        for (targetIndex in plan.targets.indices) {
            val target = plan.targets[targetIndex]

            if (state.isTargetPassed(targetIndex)) continue
            if (!isTargetAhead(target, tick)) continue
            if (!areEarlierTargetsAnnounced(plan, targetIndex, state)) continue

            val candidate = selectStageForTarget(targetIndex, target, tick, state)
            best = moreUrgentOf(best, candidate)
        }

        return best
    }

    /**
     * 自分より手前 (route 順で前) の案内地点がすべて「発話済み or 通過済み」かを返す。
     *
     * 外部データには、ある案内地点の発話を 1km 級の手前でトリガする bare な段があり、これを放置すると
     * 手前の地点の案内中に後続地点の案内が割り込んでしまう (例: 1 つ目の交差点へ向かう途中で 2 つ目の
     * 交差点の方向だけが鳴る)。route 順を保つため、手前の地点がすべて区切り済みになるまで後続地点は
     * 候補にしない。区切り済みでなければ後続段は次 tick 以降に持ち越される (level 判定なので落ちない)。
     *
     * 「○m先」のような予告は手前の地点が通過する前に鳴るのが正常なため、「通過済み」だけでなく
     * 「その地点の FINAL (= 最寄り段) が発話済み」も区切り済みとみなす。これにより手前の地点の直前案内が
     * 出た後は、後続地点の予告が即座に解禁される。
     */
    private fun areEarlierTargetsAnnounced(
        plan: VoiceAnnouncementPlan,
        targetIndex: Int,
        state: VoiceAnnouncementSpeechState,
    ): Boolean {
        for (earlierIndex in 0 until targetIndex) {
            val earlierTarget = plan.targets[earlierIndex]
            if (isTargetAnnounced(earlierTarget, earlierIndex, state)) continue

            return false
        }

        return true
    }

    /** 案内地点が区切り済み (通過済み、または最寄り段が発話済み) かを返す。 */
    private fun isTargetAnnounced(
        target: AnnouncementTarget,
        targetIndex: Int,
        state: VoiceAnnouncementSpeechState,
    ): Boolean {
        if (state.isTargetPassed(targetIndex)) return true

        val nearestStage = target.stages.maxByOrNull { stage -> stage.triggerGeometryMeters } ?: return true

        return state.isStageFired(nearestStage.id)
    }

    /**
     * 現時点で通過し終えた (= これ以上発話する意味が無い) 案内地点の index を返す。
     *
     * scheduler はこれを [VoiceAnnouncementSpeechState.withTargetPassed] に流し、通過済み地点の
     * 未発話段を恒久的に抑止する。現在地が GP 位置に到達済みかで判定する level 方式で、毎 tick
     * 通過済みの全 index を返す (記録側が冪等に union する想定)。
     *
     * route が発話不能状態 (OFF_ROUTE_CONFIRMED 等) の tick では空を返す。投影距離だけが進んで
     * いる可能性があり、通過済みと誤記録すると復帰時に案内を失うため、発話状態は維持する。
     */
    fun passedTargetIndices(plan: VoiceAnnouncementPlan, tick: VoiceTick): List<Int> {
        if (!tick.isRouteUsable) return emptyList()

        val passed = mutableListOf<Int>()

        for (targetIndex in plan.targets.indices) {
            val target = plan.targets[targetIndex]
            if (!isTargetAhead(target, tick)) passed += targetIndex
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
            if (isOvertakenByProcessedStage(target, stage, state)) continue
            if (!isStageTriggered(stage, target, tick)) continue

            val urgency = VoiceAnnouncementUrgency.of(
                targetGeometryMeters = target.geometryMeters,
                currentCumulativeMeters = tick.currentCumulativeMeters,
                kind = stage.kind,
            )
            val candidate = VoiceAnnouncementSelection(
                targetIndex = targetIndex,
                targetGeometryMeters = target.geometryMeters,
                stage = stage,
                urgency = urgency,
            )

            best = moreUrgentOf(best, candidate)
        }

        return best
    }

    /**
     * 2 候補のうち緊急度が高い方を返す。null は「候補なし」を表す。
     *
     * urgency は target の残距離と種別だけで決まるため、同一 target の同種別段 (複数の MIDDLE 等)
     * では同値になる。その場合はより手前でトリガする ([AnnouncementStage.triggerGeometryMeters] が
     * 大きい) 段を採り、一度に複数段を跨いだとき近い予告を選ぶ (例: 2km と 1km を同時に越えたら 1km)。
     */
    private fun moreUrgentOf(
        current: VoiceAnnouncementSelection?,
        candidate: VoiceAnnouncementSelection?,
    ): VoiceAnnouncementSelection? {
        if (candidate == null) return current
        if (current == null) return candidate

        val urgencyComparison = candidate.urgency.compareTo(current.urgency)
        if (urgencyComparison > 0) return candidate
        if (urgencyComparison < 0) return current

        val isCandidateNearer = candidate.stage.triggerGeometryMeters > current.stage.triggerGeometryMeters

        return if (isCandidateNearer) candidate else current
    }

    /** 案内地点がまだ前方にあるか (通過していないか) を返す。 */
    private fun isTargetAhead(target: AnnouncementTarget, tick: VoiceTick): Boolean =
        tick.currentCumulativeMeters < target.geometryMeters

    /**
     * 同一 target 内で、この段より手前 (triggerGeometryMeters が大きい) の段が既に処理済みかを返す。
     *
     * level 判定では到達済みの未処理段がずっと候補に残るため、より近い予告や FINAL を鳴らした後に
     * 追い越された古い予告を蒸し返してしまう。これを防ぐための判定。
     * 例: 2km と 1km の予告で 1km を採ったら、追い越された 2km は捨てる。FINAL は最も手前に来るため、
     * FINAL を鳴らした後はその target の MIDDLE はすべて追い越し済みとして抑止される。
     */
    private fun isOvertakenByProcessedStage(
        target: AnnouncementTarget,
        stage: AnnouncementStage,
        state: VoiceAnnouncementSpeechState,
    ): Boolean {
        for (other in target.stages) {
            if (other.triggerGeometryMeters <= stage.triggerGeometryMeters) continue
            if (state.isStageFired(other.id)) return true
        }

        return false
    }

    /** 段のトリガ条件を満たすかを返す。MIDDLE は到達判定、FINAL は到達リードタイム逆算。 */
    private fun isStageTriggered(
        stage: AnnouncementStage,
        target: AnnouncementTarget,
        tick: VoiceTick,
    ): Boolean = when (stage.kind) {
        AnnouncementStageKind.MIDDLE -> isMiddleReached(stage, tick)
        AnnouncementStageKind.FINAL -> isFinalReached(target, tick)
    }

    /**
     * 中間段: 現在地がトリガ距離に到達したかを返す (level 判定)。
     *
     * 一度きりの発話保証は呼び出し側の既処理マークが担う。これにより同 tick で複数段を跨いで
     * 選ばれなかった段も、未処理なら次 tick 以降に再評価される。
     */
    private fun isMiddleReached(stage: AnnouncementStage, tick: VoiceTick): Boolean =
        stage.triggerGeometryMeters <= tick.currentCumulativeMeters

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
}
