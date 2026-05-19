package me.matsumo.onenavi.core.navigation.newguidance.model

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import me.matsumo.onenavi.core.model.ManeuverModifier

/**
 * 案内地点に紐づくレーンガイダンス。
 *
 * @param lanes 左から右の順に並んだレーン情報
 */
@Immutable
data class LaneGuidance(
    val lanes: ImmutableList<Lane>,
)

/**
 * 1 車線ぶんの進行方向と推奨状態。
 *
 * @param allowedDirections この車線で許可される進行方向
 * @param recommendedDirection 推奨される進行方向
 * @param isActive 案内中の推奨車線として強調するか
 */
@Immutable
data class Lane(
    val allowedDirections: ImmutableList<ManeuverModifier>,
    val recommendedDirection: ManeuverModifier?,
    val isActive: Boolean,
)
