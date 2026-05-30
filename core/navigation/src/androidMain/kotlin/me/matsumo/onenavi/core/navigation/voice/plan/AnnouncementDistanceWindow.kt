package me.matsumo.onenavi.core.navigation.voice.plan

import androidx.compose.runtime.Immutable

/**
 * 中間段 (MIDDLE) が発話候補になれる geometry 累積距離の有効窓 (half-open `[enter, exit)`)。
 *
 * 外部ナビ API の参照実装は、同一案内地点の距離違い候補それぞれに「現在距離がこの範囲に入る間だけ
 * 選べる」という距離窓 (案内点までの残距離の下限/上限) を持たせ、発話時に窓内の候補を 1 つだけ選ぶ。
 * OneNavi も外部データの発話有効範囲 ([GuideAnnouncementWindow]) を source→geometry 変換して窓にする
 * ([VoiceAnnouncementPlanBuilder] が構築)。発話有効範囲がデータに無い block は、名目トリガ距離 (delta)
 * 周りの狭い帯を窓にする (案内点まで広げると遠方トリガ block が手前案内の直後に鳴る誤発話の原因になる)。
 *
 * 窓を持つことで「現在地がトリガ距離を越えたら以後ずっと候補化される」片側しきい値の弊害
 * (route 途中から開始したとき背後の遠い予告まで一斉発火する等) を避け、各候補は自分の距離帯に
 * いる間だけ候補になる。半開区間にして隣接窓の重複発火を防ぐ。
 *
 * @property enterGeometryMeters 窓に入る geometry 累積距離 (m、含む)。案内点から最も遠い側の端
 * @property exitGeometryMeters 窓から出る geometry 累積距離 (m、含まない)。案内点に最も近い側の端
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
