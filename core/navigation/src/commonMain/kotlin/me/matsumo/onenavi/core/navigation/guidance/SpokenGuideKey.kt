package me.matsumo.onenavi.core.navigation.guidance

import androidx.compose.runtime.Immutable
import me.matsumo.onenavi.core.model.DistanceBucket

/**
 * 重複発話抑制のためのキー。
 *
 * 同一ステップ内で既に特定カテゴリ × バケットの案内を発話したか否かを判定する。
 * ステップをまたぐと `stepCounter` が変わるため、同一キーで再抑制されることは無い。
 */
@Immutable
data class SpokenGuideKey(
    val stepCounter: Int,
    val category: Category,
    val bucket: DistanceBucket?,
) {
    /** 発話カテゴリ。 */
    enum class Category {
        /** マニューバ予告（ターン／分岐／合流）。 */
        MANEUVER,

        /** レーン案内。 */
        LANE,

        /** 道なり案内。 */
        STRAIGHTFORWARD,
    }
}
