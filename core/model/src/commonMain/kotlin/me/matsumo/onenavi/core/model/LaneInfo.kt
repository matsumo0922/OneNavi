package me.matsumo.onenavi.core.model

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList

/**
 * 交差点のレーン情報。
 * Google Navigation SDK の turn-by-turn feed から変換される。
 *
 * @param directions このレーンが示す進行方向の一覧（"left" / "straight" / "right" / "slight left" 等）
 * @param activeDirection 推奨レーンの場合、強調すべき方向（無ければ null）
 * @param isRecommended 推奨レーンかどうか（BannerComponents.active）
 */
@Immutable
data class LaneInfo(
    val directions: ImmutableList<String>,
    val activeDirection: String?,
    val isRecommended: Boolean,
)
