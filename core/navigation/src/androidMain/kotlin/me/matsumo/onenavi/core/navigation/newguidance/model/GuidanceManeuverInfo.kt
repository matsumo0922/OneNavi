package me.matsumo.onenavi.core.navigation.newguidance.model

import androidx.compose.runtime.Immutable
import me.matsumo.onenavi.core.model.ManeuverModifier
import me.matsumo.onenavi.core.model.ManeuverType
import me.matsumo.onenavi.core.model.RoutePoint

/**
 * TBT バナーと発話スケジューラが参照する案内地点情報。
 *
 * @param type マニューバ種別
 * @param modifier 左右・直進などの方向修飾子
 * @param location 案内地点の route geometry 上の座標
 * @param distanceToManeuverMeters 現在位置から案内地点までの距離
 * @param intersectionName 交差点名
 * @param exitNumber 出口番号
 * @param guidancePointIndex 元の案内ポイント index
 */
@Immutable
data class GuidanceManeuverInfo(
    val type: ManeuverType,
    val modifier: ManeuverModifier,
    val location: RoutePoint,
    val distanceToManeuverMeters: Int,
    val intersectionName: String?,
    val exitNumber: String?,
    val guidancePointIndex: Int,
)
