package me.matsumo.onenavi.core.model

import androidx.compose.runtime.Immutable

/**
 * 道なり案内の段階。
 *
 * 次のステップまでの距離に応じて [SHORT] / [LONG] を選択し、
 * ステップ遷移直後に 1 回のみ発話する。
 */
@Immutable
enum class StraightforwardLevel {
    /** 1000m 以上 5000m 未満。「しばらく道なりです。」 */
    SHORT,

    /** 5000m 以上。「5km以上道なりです。」 */
    LONG,
}
