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

    /**
     * 案内地点が区切り済み (通過済み、または最寄り段が処理済み) かを返す。
     *
     * 最寄り段 (= 案内点に最も近い段、通常は FINAL) 自体が発話済みなら区切り済み。加えて、最寄り段と同一
     * グループの別段が発話済みでグループ消費された場合も区切り済みとみなす。最寄り段がグループ消費で発話
     * 不能になると自身は fired にならないため、ここで groupKey 消費も見ないと後続地点の解禁が案内点通過まで
     * 止まり、後続の予告窓を取り逃がす。
     */
    private fun isTargetAnnounced(
        target: AnnouncementTarget,
        targetIndex: Int,
        state: VoiceAnnouncementSpeechState,
    ): Boolean {
        if (state.isTargetPassed(targetIndex)) return true

        val nearestStage = target.stages.maxByOrNull { stage -> stage.triggerGeometryMeters } ?: return true
        if (state.isStageFired(nearestStage.id)) return true

        return nearestStage.groupKey in consumedGroupKeysOf(target, state)
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
     * 処理済みなら ([consumedGroupKeysOf]) そのグループの残りは選ばない (1 グループ 1 発話)。さらに、同一
     * グループに具体テンプレートの候補と汎用テンプレートの候補が同時に発話可能なときは汎用候補を避ける
     * ([applyGenericAvoidance]、外部ナビ API 参照実装の汎用回避)。FINAL が同 tick で発話可能になった場合は
     * 緊急度比較で最緊急 (= FINAL) を採用する。
     */
    private fun selectStageForTarget(
        targetIndex: Int,
        target: AnnouncementTarget,
        tick: VoiceTick,
        state: VoiceAnnouncementSpeechState,
    ): VoiceAnnouncementSelection? {
        val consumedGroupKeys = consumedGroupKeysOf(target, state)
        val speakableStages = speakableStagesOf(target, tick, state, consumedGroupKeys)
        val preferredStages = applyGenericAvoidance(speakableStages)
        var best: VoiceAnnouncementSelection? = null

        for (stage in preferredStages) {
            val candidate = selectionOf(targetIndex, target, stage, tick)
            best = moreUrgentOf(best, candidate)
        }

        return best
    }

    /** target の段のうち、現 tick で発話可能 (未処理かつ [isStageSpeakable]) な段を返す。 */
    private fun speakableStagesOf(
        target: AnnouncementTarget,
        tick: VoiceTick,
        state: VoiceAnnouncementSpeechState,
        consumedGroupKeys: Set<VoiceAnnouncementId>,
    ): List<AnnouncementStage> {
        val result = mutableListOf<AnnouncementStage>()

        for (stage in target.stages) {
            if (state.isStageFired(stage.id)) continue
            if (!isStageSpeakable(stage, target, tick, consumedGroupKeys)) continue

            result += stage
        }

        return result
    }

    /**
     * 汎用回避: 同一グループに具体テンプレートの MIDDLE 候補があれば、その汎用 MIDDLE 候補を除外する。
     *
     * 外部データは同一グループに、汎用テンプレート (「指定なし」) の言い換え (例: 「まもなく右方向です」) と
     * 具体テンプレート (例: 「およそ200m先 右方向です」) を併せ持つ。外部ナビ API の参照実装は窓に入る候補の
     * うち汎用より具体を優先するため、両方が同 tick で発話可能なら汎用を落として具体を残す。テキストではなく
     * 外部データのテンプレート ID ([AnnouncementStage.isGeneric]) で判定する。FINAL は対象にしない。
     */
    private fun applyGenericAvoidance(stages: List<AnnouncementStage>): List<AnnouncementStage> {
        val groupKeysWithSpecific = groupKeysWithSpecificMiddle(stages)

        return stages.filterNot { stage -> isAvoidableGeneric(stage, groupKeysWithSpecific) }
    }

    /** 具体テンプレート (非汎用) の MIDDLE 候補を持つ groupKey の集合を返す。 */
    private fun groupKeysWithSpecificMiddle(stages: List<AnnouncementStage>): Set<VoiceAnnouncementId> {
        val result = mutableSetOf<VoiceAnnouncementId>()

        for (stage in stages) {
            if (stage.kind != AnnouncementStageKind.MIDDLE) continue
            if (stage.isGeneric) continue

            result += stage.groupKey
        }

        return result
    }

    /** 同一グループに具体候補があるために避けるべき汎用 MIDDLE 段かを返す。 */
    private fun isAvoidableGeneric(
        stage: AnnouncementStage,
        groupKeysWithSpecific: Set<VoiceAnnouncementId>,
    ): Boolean {
        if (stage.kind != AnnouncementStageKind.MIDDLE) return false
        if (!stage.isGeneric) return false

        return stage.groupKey in groupKeysWithSpecific
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
     * 距離違いの候補は group_id ごとの代替候補で、発話するのは 1 グループ 1 つだけ。同一グループの段が
     * 処理済み (発話・割り込み・キュー投入・空畳み込み) になった時点でそのグループを消費したとみなし、以後
     * 同 groupKey の段は MIDDLE / FINAL を問わず選ばない (1 グループ 1 発話)。FINAL は通常は単独グループ
     * なので消費集合に自グループは入らず、到達リードタイムだけで鳴る。
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
     * 達しているかで判定する。
     *
     * MIDDLE / FINAL いずれも自身の groupKey が消費済み ([consumedGroupKeys] に含まれる) なら発話しない。
     * 外部データは距離違い候補を group_id で 1 枠に束ねており、参照実装はその枠を 1 回だけ発話して消費する。
     * FINAL も同一グループの候補が既に発話済みなら鳴らさない (= 1 グループ 1 発話)。FINAL が単独グループの
     * 通常ケースでは消費集合に自グループは入らないため、従来どおり到達リードタイムだけで鳴る。
     */
    private fun isStageSpeakable(
        stage: AnnouncementStage,
        target: AnnouncementTarget,
        tick: VoiceTick,
        consumedGroupKeys: Set<VoiceAnnouncementId>,
    ): Boolean {
        if (stage.groupKey in consumedGroupKeys) return false

        return when (stage.kind) {
            AnnouncementStageKind.MIDDLE -> isMiddleWindowActive(stage, tick)
            AnnouncementStageKind.FINAL -> isFinalReached(target, tick)
        }
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
