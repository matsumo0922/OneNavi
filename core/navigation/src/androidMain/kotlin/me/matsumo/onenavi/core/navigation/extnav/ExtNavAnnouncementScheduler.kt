package me.matsumo.onenavi.core.navigation.extnav

import io.github.aakira.napier.Napier
import me.matsumo.drive.supporter.api.guidance.domain.GuidanceCategory
import me.matsumo.drive.supporter.api.guidance.domain.GuidancePoint
import me.matsumo.drive.supporter.api.guidance.domain.SsmlPhrase
import me.matsumo.onenavi.core.navigation.extnav.ExtNavAnnouncementScheduler.Companion.IMMEDIATE_TRIGGER_THRESHOLD_METRES
import me.matsumo.onenavi.core.navigation.tts.SpeechQueueMode

/**
 * [ExtNavGuidanceTracker] の進捗から、各 [GuidancePoint] の [SsmlPhrase] を
 * 「手前何 m で読むべきか」に従って発話する。
 *
 * ### 選択ポリシー (GP ごとに 2 スロット)
 *
 * 外部ナビ API は 1 物理案内点に対して複数の前振り block (500m / 250m / 200m / 100m /
 * 直前…) を返してくるが、native の参照実装 (closed-source) は速度に応じて 1-2 個に
 * 間引いて発話する。OneNavi 側にその native ロジックは無いため、GP 単位で **prealarm
 * + immediate の 2 スロット** だけを埋める抑制ロジックで近似する。
 *
 * - `prealarm` スロット: trigger >= [IMMEDIATE_TRIGGER_THRESHOLD_METRES] の phrase から
 *   1 つだけ選ぶ。選択は「現在 distToGp から見て **最も小さい (= 最も直近の) trigger**」。
 *   250m/200m の双方が trigger 条件を満たすときは 200m 側を選ぶ、という挙動。
 * - `immediate` スロット: trigger < [IMMEDIATE_TRIGGER_THRESHOLD_METRES] の phrase を
 *   1 つだけ発話 (典型的には直前 59m トリガー)。
 * - [AnnouncementPriority.CRITICAL] (WrongWayDriving / WrongEntry / Zone30 等、安全系)
 *   は本抑制の対象外で、個別 dedupe (`SpokenKey`) + FLUSH 発話で割り込む。
 *
 * 詳細は `docs/spec/21_ext_nav_guide_proto_and_announcement.md` §4.4 / Task 3 を参照。
 */
class ExtNavAnnouncementScheduler(
    private val speaker: ExtNavSsmlSpeaker,
    private val hysteresisMetres: Double = DEFAULT_HYSTERESIS_METRES,
) {
    private val gpSlotStates: MutableMap<Int, GpSlotState> = mutableMapOf()
    private val criticalSpoken: MutableSet<SpokenKey> = mutableSetOf()
    private var utteranceCounter: Int = 0

    /**
     * セッション開始時にクリアする。
     */
    fun reset() {
        gpSlotStates.clear()
        criticalSpoken.clear()
        utteranceCounter = 0
    }

    /**
     * tracker からの進捗更新ごとに呼ぶ。
     * まだ発話していない phrase のうち、距離条件に達したものをキューに投入する。
     *
     * ### 発火対象の GP
     *
     * `upcomingGuidancePoints` は距離順に並んでいるため、prealarm / immediate スロットは
     * **先頭 (= 現在最も近い未到達 GP) だけ** を対象にする。後続 GP の prealarm が遠方で
     * 先に条件を満たしても、現在対象の GP の immediate が鳴るまでは待たせる。
     *
     * これをしないと「gp=0 の 500m 前振り」と「gp=2 の 2000m 前振り」が同時刻に連続発話
     * されたり、「gp=4 の 1000m 前振り」が「gp=2 の直前発話」より先に出たりして、案内の
     * 時系列整合が壊れる。
     *
     * CRITICAL (安全系) のみ全 lookahead GP を個別 dedupe で即時発話対象にする。
     */
    fun onProgress(snapshot: ExtNavProgressSnapshot) {
        val candidates = snapshot.upcomingGuidancePoints.take(LOOKAHEAD_GP_COUNT)
        candidates.forEachIndexed { position, gp ->
            val distanceToGp = gp.distanceFromStartMetres - snapshot.progressedMetres
            if (distanceToGp < -MAX_OVERSHOOT_METRES) return@forEachIndexed
            // CRITICAL は全 GP について個別 dedupe で即時発話。
            fireCriticalPhrases(gp, distanceToGp)
            // prealarm / immediate のスロット発話は「現在最も近い未到達 GP」だけに限定し、
            // 後続 GP に追い越し発話をさせない。
            if (position == 0) {
                processGuidancePointSlots(gp, distanceToGp)
            }
        }
    }

    private fun processGuidancePointSlots(gp: GuidancePoint, distanceToGp: Double) {
        val slotCandidates = gp.phrases.filter { phrase ->
            phrase.category !in SUPPRESSED_SPEECH_CATEGORIES &&
                priorityOf(phrase.category) != AnnouncementPriority.CRITICAL
        }
        if (slotCandidates.isEmpty()) return

        val state = gpSlotStates.getOrPut(gp.index) { GpSlotState() }

        // immediate を先に評価。距離が既に近接帯に入っていれば prealarm は飛ばす。
        if (!state.immediateFired) {
            val chosen = slotCandidates
                .filter { it.distanceMetres < IMMEDIATE_TRIGGER_THRESHOLD_METRES }
                .filter { distanceToGp <= it.distanceMetres + hysteresisMetres }
                .maxByOrNull { it.distanceMetres }
            if (chosen != null) {
                speak(gp, chosen, distanceToGp, SpeechQueueMode.ADD, slot = "immediate")
                state.immediateFired = true
                state.prealarmFired = true
                return
            }
        }

        if (!state.prealarmFired) {
            val chosen = slotCandidates
                .filter { it.distanceMetres >= IMMEDIATE_TRIGGER_THRESHOLD_METRES }
                .filter { distanceToGp <= it.distanceMetres + hysteresisMetres }
                .minByOrNull { it.distanceMetres }
            if (chosen != null) {
                speak(gp, chosen, distanceToGp, SpeechQueueMode.ADD, slot = "prealarm")
                state.prealarmFired = true
            }
        }
    }

    private fun fireCriticalPhrases(gp: GuidancePoint, distanceToGp: Double) {
        for (phrase in gp.phrases) {
            if (priorityOf(phrase.category) != AnnouncementPriority.CRITICAL) continue
            if (distanceToGp > phrase.distanceMetres + hysteresisMetres) continue
            val key = SpokenKey(
                gpIndex = gp.index,
                categoryId = phrase.category.id,
                distanceMetres = phrase.distanceMetres,
            )
            if (key in criticalSpoken) continue
            speak(gp, phrase, distanceToGp, SpeechQueueMode.FLUSH, slot = "critical")
            criticalSpoken += key
        }
    }

    private fun speak(
        gp: GuidancePoint,
        phrase: SsmlPhrase,
        distanceToGp: Double,
        mode: SpeechQueueMode,
        slot: String,
    ) {
        val utteranceId = "gp-${gp.index}-${phrase.category.id}-${phrase.distanceMetres}-${utteranceCounter++}"
        val priority = priorityOf(phrase.category)
        Napier.i(tag = TAG) {
            "[NAVDBG] speak: gp=${gp.index} slot=$slot cat=${phrase.category.id} " +
                "trigger=${phrase.distanceMetres}m distToGp=${distanceToGp.toInt()}m " +
                "priority=$priority phrase=\"${phrase.ssml}\""
        }
        speaker.speakSsml(
            ssml = phrase.ssml,
            utteranceId = utteranceId,
            queueMode = mode,
        )
    }

    internal fun priorityOf(category: GuidanceCategory): AnnouncementPriority = when (category) {
        GuidanceCategory.WrongWayDriving,
        GuidanceCategory.WrongEntry,
        GuidanceCategory.Zone30,
        -> AnnouncementPriority.CRITICAL

        GuidanceCategory.IntersectionGuide,
        GuidanceCategory.IntersectionGuideSoon,
        GuidanceCategory.HighwayRecommendedLane,
        GuidanceCategory.Merge,
        GuidanceCategory.MergeAttention,
        GuidanceCategory.HighwayLaneReduction,
        -> AnnouncementPriority.HIGH

        else -> AnnouncementPriority.NORMAL
    }

    internal data class SpokenKey(
        val gpIndex: Int,
        val categoryId: Int,
        val distanceMetres: Int,
    )

    /**
     * 1 GP あたりの発話状態。`prealarm` と `immediate` の 2 スロットをそれぞれ 1 回までに制限する。
     * `immediate` を発話したタイミングで `prealarm` も立てるため、ジャンプ到着時に両方連打される
     * ことは無い。
     */
    private data class GpSlotState(
        var prealarmFired: Boolean = false,
        var immediateFired: Boolean = false,
    )

    companion object {
        private const val TAG = "ExtNavAnnouncementScheduler"
        internal const val DEFAULT_HYSTERESIS_METRES: Double = 15.0
        internal const val LOOKAHEAD_GP_COUNT: Int = 5
        internal const val MAX_OVERSHOOT_METRES: Double = 50.0

        /**
         * 「直前トリガー」(immediate) と「前振りトリガー」(prealarm) を分ける境界。
         *
         * 外部ナビ API は交差点案内で 59m 直前トリガーを返すため、70m を境界にすれば
         * 59m / 50m 系が immediate、100m 以上が prealarm に自然に分類される。
         */
        internal const val IMMEDIATE_TRIGGER_THRESHOLD_METRES: Int = 70

        /**
         * 発話対象から抑制するカテゴリ。
         *
         * [GuidanceCategory.SpeedAdjustment] は外部ナビ API の template 105
         * (速度に応じたガイダンス発話) で、交差点案内 (id=1/2) と重複する距離前振り
         * フレーズが通過時点 (distanceMetres=0) で仕掛けられるため、全 GP で連呼されて
         * しまう。事故多発・オービス・踏切などの注意喚起系はそのまま発話対象に残す。
         */
        internal val SUPPRESSED_SPEECH_CATEGORIES: Set<GuidanceCategory> = setOf(
            GuidanceCategory.SpeedAdjustment,
        )
    }
}

/**
 * 発話の優先度。CRITICAL は進行中の発話を打ち切って割り込む。
 */
enum class AnnouncementPriority {
    CRITICAL,
    HIGH,
    NORMAL,
}
