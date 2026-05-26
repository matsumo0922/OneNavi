package me.matsumo.onenavi.core.navigation.newguidance.model

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import me.matsumo.onenavi.core.model.ManeuverModifier
import me.matsumo.onenavi.core.model.ManeuverType
import me.matsumo.onenavi.core.model.RoadClass
import me.matsumo.onenavi.core.model.RoutePoint

/**
 * 案内中パネルの 1 行分の表示データ。
 */
@Immutable
sealed interface GuidancePanelItem {
    val id: String
    val location: RoutePoint
    val distanceFromStartMeters: Double
    val distanceToItemMeters: Int
    val etaEpochMillis: Long?
    val subtitle: GuidancePanelSubtitle?
    val laneGuidance: LaneGuidance?
}

/**
 * 案内中パネル行の補助表示。
 */
@Immutable
sealed interface GuidancePanelSubtitle

/**
 * 方面看板や分岐案内などの案内文。
 *
 * @param text 案内文
 */
@Immutable
data class GuidanceTextPanelSubtitle(
    val text: String,
) : GuidancePanelSubtitle

/**
 * 料金所で表示する料金。
 *
 * @param amountYen 料金（円）
 */
@Immutable
data class TollPanelSubtitle(
    val amountYen: Int,
) : GuidancePanelSubtitle

/**
 * 高速道路の入口を示す補助表示。
 */
@Immutable
data object EntrancePanelSubtitle : GuidancePanelSubtitle

/**
 * 高速道路の出口を示す補助表示。
 */
@Immutable
data object ExitPanelSubtitle : GuidancePanelSubtitle

/**
 * 推奨レーンを示す補助表示。
 *
 * @param lanes 左から右の順に並んだレーン情報
 */
@Immutable
data class RecommendedLanesPanelSubtitle(
    val lanes: ImmutableList<Lane>,
) : GuidancePanelSubtitle

/**
 * 進路選択を伴う案内地点のパネル行。
 *
 * @param id パネル行を識別する安定 ID
 * @param location ルート上の地点座標
 * @param distanceFromStartMeters ルート始点からの累積距離
 * @param distanceToItemMeters 現在位置から地点までの距離
 * @param etaEpochMillis 推定通過時刻。計算できない場合は null
 * @param type マニューバ種別
 * @param modifier 左右・直進などの方向修飾子
 * @param intersectionName 交差点名や分岐名
 * @param exitNumber 出口番号
 * @param roadClass 地点付近の道路種別
 * @param facility 施設を伴う案内地点の場合の施設種別
 * @param subtitle 補助表示。表示すべき情報が無い場合は null
 * @param laneGuidance 案内地点のレーンガイダンス。無い場合は null
 */
@Immutable
data class ManeuverPanelItem(
    override val id: String,
    override val location: RoutePoint,
    override val distanceFromStartMeters: Double,
    override val distanceToItemMeters: Int,
    override val etaEpochMillis: Long?,
    val type: ManeuverType,
    val modifier: ManeuverModifier,
    val intersectionName: String?,
    val exitNumber: String?,
    val roadClass: RoadClass,
    val facility: GuidancePanelFacility?,
    override val subtitle: GuidancePanelSubtitle?,
    override val laneGuidance: LaneGuidance?,
) : GuidancePanelItem

/**
 * 進路選択を伴わない通過施設のパネル行。
 *
 * @param id パネル行を識別する安定 ID
 * @param location ルート上の地点座標
 * @param distanceFromStartMeters ルート始点からの累積距離
 * @param distanceToItemMeters 現在位置から地点までの距離
 * @param etaEpochMillis 推定通過時刻。計算できない場合は null
 * @param name 施設名
 * @param kind 施設種別
 * @param roadClass 地点付近の道路種別
 * @param services SA / PA のサービス情報
 * @param subtitle 補助表示。表示すべき情報が無い場合は null
 * @param laneGuidance 施設のレーンガイダンス。無い場合は null
 */
@Immutable
data class FacilityPanelItem(
    override val id: String,
    override val location: RoutePoint,
    override val distanceFromStartMeters: Double,
    override val distanceToItemMeters: Int,
    override val etaEpochMillis: Long?,
    val name: String,
    val kind: GuidancePanelFacility,
    val roadClass: RoadClass,
    val services: ImmutableList<String>,
    override val subtitle: GuidancePanelSubtitle?,
    override val laneGuidance: LaneGuidance?,
) : GuidancePanelItem

/**
 * 案内中パネルに表示できる施設種別。
 */
enum class GuidancePanelFacility {
    IC,
    JCT,
    SA,
    PA,
    TOLL_GATE,
}
