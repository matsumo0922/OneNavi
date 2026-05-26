package me.matsumo.onenavi.core.navigation.newguidance.semantic

import androidx.compose.runtime.Immutable
import me.matsumo.onenavi.core.model.ManeuverModifier
import me.matsumo.onenavi.core.model.ManeuverType

/**
 * 主案内 (アクション)。曲がる・合流する・到着する等の「何をするか」。
 *
 * 施設や看板は補足情報 ([GuidanceEventDetails]) 側に置き、主案内とは排他にしない。
 * 到着 (ARRIVE) も独立 variant を作らず [type] で表す。
 *
 * @property type マニューバ種別。
 * @property modifier 左右・直進などの方向修飾子。
 * @property intersectionName 交差点名 / 分岐名。無ければ null。
 * @property exitNumber 出口番号。無ければ null。
 */
@Immutable
data class GuidanceManeuver(
    val type: ManeuverType,
    val modifier: ManeuverModifier,
    val intersectionName: String?,
    val exitNumber: String?,
)
