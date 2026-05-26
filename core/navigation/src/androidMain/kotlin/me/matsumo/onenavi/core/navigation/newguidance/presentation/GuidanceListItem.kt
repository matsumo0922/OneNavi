package me.matsumo.onenavi.core.navigation.newguidance.presentation

import androidx.compose.runtime.Immutable
import me.matsumo.onenavi.core.model.ManeuverModifier
import me.matsumo.onenavi.core.model.ManeuverType
import me.matsumo.onenavi.core.model.RoadClass
import me.matsumo.onenavi.core.navigation.newguidance.semantic.FacilityKind
import me.matsumo.onenavi.core.navigation.newguidance.semantic.HighwayBoundary

/**
 * フルリスト表示 1 行分の presentation 値 (L3)。
 *
 * 1 イベントを 1 行に射影したもの。補助情報は [detail] の **唯一の枠**に畳み、複数の補足
 * (レーン / 料金 / 看板 / 境界 / 警告) からどれを出すかは [GuidanceListDetailPolicy] が
 * 1 度だけ優先順位で選ぶ。
 *
 * @property id LazyColumn 用の安定 ID
 * @property icon 行頭アイコン (曲がりアイコン または 施設バッジ)
 * @property title 行タイトル (交差点名 / 施設名 / 出口番号)。解決できなければ null で UI が既定文言を出す
 * @property detail 補助表示。出すものが無ければ null
 * @property distanceMeters 現在位置から地点までの残距離
 * @property etaEpochMillis 推定通過時刻。算出できなければ null
 * @property roadClass 地点付近の道路種別 (配色に使う)
 */
@Immutable
data class GuidanceListItem(
    val id: String,
    val icon: GuidanceListIcon,
    val title: String?,
    val detail: GuidanceListDetail?,
    val distanceMeters: Int,
    val etaEpochMillis: Long?,
    val roadClass: RoadClass,
)

/**
 * フルリスト行頭のアイコン種別。曲がりアイコンと施設バッジのどちらを描くかを型で表す。
 */
@Immutable
sealed interface GuidanceListIcon {

    /**
     * 曲がりアイコン (進路選択を伴う案内地点)。
     *
     * @property type マニューバ種別
     * @property modifier 方向修飾子
     */
    @Immutable
    data class Maneuver(
        val type: ManeuverType,
        val modifier: ManeuverModifier,
    ) : GuidanceListIcon

    /**
     * 施設バッジ (通過施設)。
     *
     * @property kind 施設種別
     */
    @Immutable
    data class FacilityBadge(
        val kind: FacilityKind,
    ) : GuidanceListIcon
}

/**
 * フルリスト行の補助表示 (1 枠)。L3 専用で domain には置かない。
 */
@Immutable
sealed interface GuidanceListDetail {

    /**
     * 推奨レーン。
     *
     * @property lane 表示するレーン
     */
    @Immutable
    data class Lanes(
        val lane: LanePresentation,
    ) : GuidanceListDetail

    /**
     * 料金。
     *
     * @property amountYen 料金 (円)
     */
    @Immutable
    data class Toll(
        val amountYen: Int,
    ) : GuidanceListDetail

    /**
     * 方面看板の案内文。
     *
     * @property text 方面文
     */
    @Immutable
    data class Signpost(
        val text: String,
    ) : GuidanceListDetail

    /**
     * 高速道路の入口 / 出口境界。
     *
     * @property kind 境界種別
     */
    @Immutable
    data class Boundary(
        val kind: HighwayBoundary,
    ) : GuidanceListDetail

    /**
     * レーン警告などの注意文。
     *
     * @property text 警告文
     */
    @Immutable
    data class Warning(
        val text: String,
    ) : GuidanceListDetail
}
