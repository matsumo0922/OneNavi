package me.matsumo.onenavi.core.navigation.newguidance.presentation

import androidx.compose.runtime.Immutable
import me.matsumo.onenavi.core.model.ManeuverModifier
import me.matsumo.onenavi.core.model.ManeuverType
import me.matsumo.onenavi.core.model.RoutePoint
import me.matsumo.onenavi.core.navigation.newguidance.presentation.ManeuverCallout.Companion.NO_GUIDANCE_POINT_INDEX

/**
 * 1 件の主案内を表示・地図オーバーレイ向けに射影した presentation 値 (L3)。
 *
 * コンパクトバナーの主案内枠・フォローアップ枠、地図上の maneuver CallOut マーカー、
 * 案内地点フォーカスカメラが共通で読む。semantic の [me.matsumo.onenavi.core.navigation.newguidance.semantic.GuidanceManeuver]
 * に、現在地からの残距離と GP index・座標といった位置依存値を載せたもの。
 *
 * @property type マニューバ種別
 * @property modifier 左右・直進などの方向修飾子
 * @property location 案内地点の route geometry 上の座標 (CallOut マーカーの固定先)
 * @property geometryDistanceFromStartMeters route geometry 上の始点から案内地点までの累積距離
 * @property distanceToManeuverMeters 現在位置から案内地点までの残距離。負値は 0 に丸める
 * @property intersectionName 交差点名や分岐名。無ければ null
 * @property exitNumber 出口番号。無ければ null
 * @property guidancePointIndex 元の案内ポイント index。紐付かない場合は [NO_GUIDANCE_POINT_INDEX]
 */
@Immutable
data class ManeuverCallout(
    val type: ManeuverType,
    val modifier: ManeuverModifier,
    val location: RoutePoint,
    val geometryDistanceFromStartMeters: Double,
    val distanceToManeuverMeters: Int,
    val intersectionName: String?,
    val exitNumber: String?,
    val guidancePointIndex: Int,
) {
    companion object {
        /** 主案内に GP index が紐付かない場合の番兵値。 */
        const val NO_GUIDANCE_POINT_INDEX: Int = -1
    }
}
