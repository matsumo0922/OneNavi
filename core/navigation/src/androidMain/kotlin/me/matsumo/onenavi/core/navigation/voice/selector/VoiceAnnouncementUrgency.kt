package me.matsumo.onenavi.core.navigation.voice.selector

import androidx.compose.runtime.Immutable
import me.matsumo.onenavi.core.navigation.voice.plan.AnnouncementStageKind

/**
 * 発話候補の緊急度。複数候補から発話すべき 1 件を選ぶときと、発話中の段への barge-in 要否判定に使う。
 *
 * 案内地点までの残距離が小さいほど緊急で、同残距離なら FINAL を MIDDLE より緊急とみなす。
 * [compareTo] は「より緊急なら大きい」を返すため、`>` や `maxOf` がそのまま緊急度比較になる。
 *
 * @property remainingMeters 案内地点までの geometry 残距離 (m)。小さいほど緊急
 * @property kind 距離段の種別。同残距離での tie-break に使う
 */
@Immutable
internal data class VoiceAnnouncementUrgency(
    val remainingMeters: Double,
    val kind: AnnouncementStageKind,
) : Comparable<VoiceAnnouncementUrgency> {

    override fun compareTo(other: VoiceAnnouncementUrgency): Int {
        val byRemaining = other.remainingMeters.compareTo(remainingMeters)
        if (byRemaining != 0) return byRemaining

        return kindRank().compareTo(other.kindRank())
    }

    /** 同残距離時の tie-break ランク。FINAL を上位 (より緊急) とする。 */
    private fun kindRank(): Int = when (kind) {
        AnnouncementStageKind.MIDDLE -> 0
        AnnouncementStageKind.FINAL -> 1
    }

    internal companion object {

        /**
         * 案内地点位置と現在累積距離から残距離を求めて緊急度を構築する。
         *
         * 緊急度は現在地に対する相対量なので、発話中の段でも tick ごとに再計算して比較する。
         *
         * @param targetGeometryMeters 案内地点の geometry 累積距離 (m)
         * @param currentCumulativeMeters 現 tick の geometry 累積距離 (m)
         * @param kind 距離段の種別
         */
        fun of(
            targetGeometryMeters: Double,
            currentCumulativeMeters: Double,
            kind: AnnouncementStageKind,
        ): VoiceAnnouncementUrgency = VoiceAnnouncementUrgency(
            remainingMeters = targetGeometryMeters - currentCumulativeMeters,
            kind = kind,
        )
    }
}
