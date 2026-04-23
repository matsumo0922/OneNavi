package me.matsumo.onenavi.core.navigation.extnav

import io.github.aakira.napier.Napier
import me.matsumo.drive.supporter.api.guidance.domain.GuidanceCategory
import me.matsumo.drive.supporter.api.guidance.domain.GuidancePoint
import me.matsumo.drive.supporter.api.guidance.domain.SsmlPhrase
import me.matsumo.onenavi.core.navigation.tts.SpeechQueueMode

/**
 * [ExtNavGuidanceTracker] の進捗から、各 [GuidancePoint] の [SsmlPhrase] を
 * 「手前何 m で読むべきか」に従って発話する。
 *
 * dedupe: `(gp.index, phrase.category.id, phrase.distanceMetres)` で一意。同じ trigger は
 * 1 セッションに 1 回しか発火しない。
 *
 * priority:
 * - CRITICAL: WrongWayDriving / WrongEntry / Zone30 等、安全系 → FLUSH + 即発話
 * - HIGH: IntersectionGuide / IntersectionGuideSoon / Merge 等 → ADD
 * - NORMAL: それ以外 → ADD
 */
class ExtNavAnnouncementScheduler(
    private val speaker: ExtNavSsmlSpeaker,
    private val hysteresisMetres: Double = DEFAULT_HYSTERESIS_METRES,
) {
    private val spoken: MutableSet<SpokenKey> = mutableSetOf()
    private var utteranceCounter: Int = 0

    /**
     * セッション開始時にクリアする。
     */
    fun reset() {
        spoken.clear()
        utteranceCounter = 0
    }

    /**
     * tracker からの進捗更新ごとに呼ぶ。
     * まだ発話していない phrase のうち、距離条件に達したものをキューに投入する。
     */
    fun onProgress(snapshot: ExtNavProgressSnapshot) {
        val candidates = snapshot.upcomingGuidancePoints.take(LOOKAHEAD_GP_COUNT)
        for (gp in candidates) {
            val distanceToGp = gp.distanceFromStartMetres - snapshot.progressedMetres
            if (distanceToGp < -MAX_OVERSHOOT_METRES) continue

            for (phrase in gp.phrases) {
                if (phrase.category in SUPPRESSED_SPEECH_CATEGORIES) continue

                val key = SpokenKey(
                    gpIndex = gp.index,
                    categoryId = phrase.category.id,
                    distanceMetres = phrase.distanceMetres,
                )
                if (key in spoken) continue

                val triggerDistance = phrase.distanceMetres.toDouble()
                val withinTrigger = distanceToGp <= triggerDistance + hysteresisMetres

                if (withinTrigger) {
                    val priority = priorityOf(phrase.category)
                    val utteranceId = "gp-${gp.index}-${phrase.category.id}-${phrase.distanceMetres}-${utteranceCounter++}"
                    val queueMode = if (priority == AnnouncementPriority.CRITICAL) {
                        SpeechQueueMode.FLUSH
                    } else {
                        SpeechQueueMode.ADD
                    }
                    Napier.i(tag = TAG) {
                        "[NAVDBG] speak: gp=${gp.index} cat=${phrase.category.id} " +
                            "trigger=${phrase.distanceMetres}m distToGp=${distanceToGp.toInt()}m " +
                            "priority=$priority phrase=\"${phrase.ssml}\""
                    }
                    speaker.speakSsml(
                        ssml = phrase.ssml,
                        utteranceId = utteranceId,
                        queueMode = queueMode,
                    )
                    spoken += key
                }
            }
        }
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

    companion object {
        private const val TAG = "ExtNavAnnouncementScheduler"
        internal const val DEFAULT_HYSTERESIS_METRES: Double = 15.0
        internal const val LOOKAHEAD_GP_COUNT: Int = 5
        internal const val MAX_OVERSHOOT_METRES: Double = 50.0

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
