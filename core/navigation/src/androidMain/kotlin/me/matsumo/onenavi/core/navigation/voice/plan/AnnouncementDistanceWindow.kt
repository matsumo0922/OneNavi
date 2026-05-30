package me.matsumo.onenavi.core.navigation.voice.plan

import androidx.compose.runtime.Immutable

/**
 * 中間段 (MIDDLE) が発話候補になれる geometry 累積距離の有効窓 (half-open `[enter, exit)`)。
 *
 * 外部ナビ API の参照実装は、同一案内地点の距離違い候補それぞれに「現在距離がこの範囲に入る間だけ
 * 選べる」という距離窓を持たせ、発話時に窓内の候補を 1 つだけ選ぶ。OneNavi では候補データに上限/下限
 * オフセットが無いため、同一案内地点の MIDDLE 段の発話トリガ距離を昇順に並べ、**隣り合う段の間で
 * 隙間なくタイル状に区切る**ことで窓を導出する ([VoiceAnnouncementPlanBuilder] が構築)。
 *
 * 窓を持つことで「現在地がトリガ距離を越えたら以後ずっと候補化される」片側しきい値の弊害
 * (route 途中から開始したとき背後の遠い予告まで一斉発火する等) を避け、各候補は自分の距離帯に
 * いる間だけ候補になる。半開区間にして隣接窓の重複発火を防ぐ。
 *
 * @property enterGeometryMeters 窓に入る geometry 累積距離 (m、含む)。段の発話トリガ点に一致する
 * @property exitGeometryMeters 窓から出る geometry 累積距離 (m、含まない)。次に近い段のトリガ点、または案内地点
 */
@Immutable
internal data class AnnouncementDistanceWindow(
    val enterGeometryMeters: Double,
    val exitGeometryMeters: Double,
) {

    /** 現在の geometry 累積距離が窓 `[enter, exit)` に入っているかを返す。 */
    fun contains(currentCumulativeMeters: Double): Boolean =
        currentCumulativeMeters >= enterGeometryMeters && currentCumulativeMeters < exitGeometryMeters
}
