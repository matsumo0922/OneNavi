package me.matsumo.onenavi.core.navigation.voice.config

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.ImmutableSet
import me.matsumo.drive.supporter.api.guidance.domain.GuidanceCategory

/**
 * 中間段 (MIDDLE) の発話開始距離を category 別に上書きする設定。
 *
 * ここで返す距離は「案内地点 (GP) からの source 系手前距離 (m)」であり、plan 構築側が
 * 必ず source→geometry 変換を通す。直前段 (FINAL) は到達リードタイム逆算で別軸に扱うため
 * 上書き対象にしない。
 */
internal sealed interface VoiceAnnouncementDistanceOverrides {

    /**
     * 指定 category 群に対する手前距離リストを返す。上書きしないなら null。
     *
     * @param categories 対象 block が持つ category 群
     * @return GP からの source 系手前距離 (m) のリスト。上書きなしなら null
     */
    fun overrideFor(categories: ImmutableSet<GuidanceCategory>): ImmutableList<Double>?

    /** 一切上書きしないデフォルト。 */
    data object None : VoiceAnnouncementDistanceOverrides {
        override fun overrideFor(categories: ImmutableSet<GuidanceCategory>): ImmutableList<Double>? = null
    }

    /**
     * category 別に手前距離リストで上書きする設定。
     *
     * 1 つの block を距離リストの数だけ複製して MIDDLE 段化する (= 1 block → N stage)。複製した各段は
     * それぞれ別グループとして扱われ、指定した距離ごとに 1 回ずつ鳴る (グループ消費で 1 回に潰れない)。
     * 例: `IntersectionGuide → [2000.0, 1000.0, 300.0]` で 3 段の予告にそろえる。
     * N 社の挙動からは離れるため、OneNavi 独自 UX として明示的に有効化するときだけ使う。
     *
     * @property overrides category と手前距離リスト (m) の対応
     */
    @Immutable
    data class ByCategory(
        val overrides: ImmutableMap<GuidanceCategory, ImmutableList<Double>>,
    ) : VoiceAnnouncementDistanceOverrides {

        override fun overrideFor(categories: ImmutableSet<GuidanceCategory>): ImmutableList<Double>? {
            for (category in categories) {
                val distances = overrides[category]
                if (distances != null) return distances
            }
            return null
        }
    }
}
