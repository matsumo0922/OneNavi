package me.matsumo.onenavi.core.navigation.voice.selector

import me.matsumo.onenavi.core.navigation.voice.config.VoiceAnnouncementConfig
import me.matsumo.onenavi.core.navigation.voice.plan.AnnouncementStage
import me.matsumo.onenavi.core.navigation.voice.plan.AnnouncementStageKind
import me.matsumo.onenavi.core.navigation.voice.plan.AnnouncementTarget
import me.matsumo.onenavi.core.navigation.voice.plan.VoiceAnnouncementId
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
     * route が発話不能状態なら常に null。各 target について通過済み・既処理を除外し、発話可能な段
     * (MIDDLE は距離窓に現在地が入っている段、FINAL は到達リードタイム逆算で発話する段) のうち最緊急を返す。
     *
     * 同一案内地点の距離違い MIDDLE 群は「代替候補」で、いずれか 1 段が処理済みになるとグループ全体を消費する
     * (= 1 発話機会につき 1 つだけ選ぶ)。これにより「500m先 → 300m → 200m → …」と距離違いが連発するのを防ぐ。
     *
     * 1 tick につき最緊急 1 件だけを返す。選ばれなかった候補は既処理マークが付かない限り次 tick 以降に
     * 再評価されるため、同 tick で複数 target を跨いでも取りこぼさない。
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
     * 候補にしない。区切り済みでなければ後続段は次 tick 以降に持ち越され、距離窓内にいる間は候補に残るため、
     * 区切り後にその時点の距離帯の予告が鳴る (距離窓を通り過ぎた遠い予告は自然に対象外になる)。
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
     * 距離違いの MIDDLE は外部データの group_id ごとに代替候補として束ねられ、同一グループのいずれか 1 段が
     * 処理済みなら ([consumedGroupKeysOf]) そのグループの残りは選ばない (1 グループ 1 発話)。距離窓が
     * 非重複なので同時に複数候補が成立することは稀だが、FINAL が同 tick で発話可能になった場合は緊急度比較で
     * 最緊急 (= FINAL) を採用する。
     */
    private fun selectStageForTarget(
        targetIndex: Int,
        target: AnnouncementTarget,
        tick: VoiceTick,
        state: VoiceAnnouncementSpeechState,
    ): VoiceAnnouncementSelection? {
        val consumedGroupKeys = consumedGroupKeysOf(target, state)
        var best: VoiceAnnouncementSelection? = null

        for (stage in target.stages) {
            if (state.isStageFired(stage.id)) continue
            if (!isStageSpeakable(stage, target, tick, consumedGroupKeys)) continue

            val candidate = selectionOf(targetIndex, target, stage, tick)
            best = moreUrgentOf(best, candidate)
        }

        return best
    }

    /** target / stage / 現在地から発話候補 (緊急度付き) を作る。 */
    private fun selectionOf(
        targetIndex: Int,
        target: AnnouncementTarget,
        stage: AnnouncementStage,
        tick: VoiceTick,
    ): VoiceAnnouncementSelection {
        val urgency = VoiceAnnouncementUrgency.of(
            targetGeometryMeters = target.geometryMeters,
            currentCumulativeMeters = tick.currentCumulativeMeters,
            kind = stage.kind,
        )

        return VoiceAnnouncementSelection(
            targetIndex = targetIndex,
            targetGeometryMeters = target.geometryMeters,
            stage = stage,
            urgency = urgency,
        )
    }

    /**
     * 既に処理済みの段が属する groupKey の集合 (= 消費済みグループ) を返す。
     *
     * 距離違いの MIDDLE は group_id ごとの代替候補で、発話するのは 1 グループ 1 つだけ。同一グループの段が
     * 処理済み (発話・割り込み・キュー投入・空畳み込み) になった時点でそのグループを消費したとみなし、以後
     * 同 groupKey の MIDDLE は選ばない。FINAL は自身の groupKey の消費に左右されず、自身の既処理マークと
     * 到達リードタイムだけで判定される (= FINAL 用の判定で消費集合は参照しない)。
     */
    private fun consumedGroupKeysOf(
        target: AnnouncementTarget,
        state: VoiceAnnouncementSpeechState,
    ): Set<VoiceAnnouncementId> = target.stages
        .filter { stage -> state.isStageFired(stage.id) }
        .map { stage -> stage.groupKey }
        .toSet()

    /**
     * 2 候補のうち緊急度が高い方を返す。null は「候補なし」を表す。
     *
     * urgency は target の残距離と種別だけで決まるため、残距離が等しい別 target の同種別段では同値になる。
     * その場合はより手前でトリガする ([AnnouncementStage.triggerGeometryMeters] が大きい) 段を採る。
     * 同一 target の MIDDLE 群は距離窓がタイル状で同時には 1 つしか候補化しないため、ここで競合しない。
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
     * 段が現 tick で発話可能かを返す。MIDDLE は距離窓に現在地が入っているか、FINAL は到達リードタイムに
     * 達しているかで判定する。MIDDLE は自身の groupKey が消費済み ([consumedGroupKeys] に含まれる) なら発話しない。
     */
    private fun isStageSpeakable(
        stage: AnnouncementStage,
        target: AnnouncementTarget,
        tick: VoiceTick,
        consumedGroupKeys: Set<VoiceAnnouncementId>,
    ): Boolean = when (stage.kind) {
        AnnouncementStageKind.MIDDLE -> stage.groupKey !in consumedGroupKeys && isMiddleWindowActive(stage, tick)
        AnnouncementStageKind.FINAL -> isFinalReached(target, tick)
    }

    /**
     * 中間段: 現在地が距離窓 [enter, exit) に入っているかを返す。
     *
     * 窓は外部データの発話有効範囲由来で、現在地が属する距離帯の候補だけが発話可能になる。これにより
     * route 途中から開始したとき背後の遠い予告まで一斉発火する片側しきい値の弊害を避ける。
     * 窓を持たない (= 想定外の) MIDDLE 段は発話しない。
     */
    private fun isMiddleWindowActive(stage: AnnouncementStage, tick: VoiceTick): Boolean {
        val window = stage.middleWindow ?: return false

        return window.contains(tick.currentCumulativeMeters)
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
}
