package me.matsumo.onenavi.core.model

import androidx.compose.runtime.Immutable

/**
 * レーン案内で発話する推奨車線の位置。
 *
 * `NavigationLaneSnapshot.isRecommended` の集合から判定する。
 * いずれにも当たらない場合はイベント自体を生成しない（v1 では沈黙が正しい挙動）。
 */
@Immutable
enum class LanePosition {
    /** 最左側の車線が推奨。 */
    LEFT,

    /** 最右側の車線が推奨。 */
    RIGHT,

    /** 3 車線以上で中央のみが推奨（両端は非推奨）。 */
    CENTER,
}
