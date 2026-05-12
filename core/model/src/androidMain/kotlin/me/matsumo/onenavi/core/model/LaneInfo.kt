package me.matsumo.onenavi.core.model

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList

/**
 * 交差点のレーン情報。
 * Google Navigation SDK の turn-by-turn feed から変換される。
 *
 * @param directions このレーンが示す進行方向の一覧。
 * @param activeDirection 推奨レーンの場合に強調すべき方向。なければ null。
 * @param isRecommended 推奨レーンかどうか。
 */
@Immutable
data class LaneInfo(
    val directions: ImmutableList<ManeuverModifier>,
    val activeDirection: ManeuverModifier?,
    val isRecommended: Boolean,
)
