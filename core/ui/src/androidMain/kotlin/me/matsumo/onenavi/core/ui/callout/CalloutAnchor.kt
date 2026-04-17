package me.matsumo.onenavi.core.ui.callout

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import kotlinx.collections.immutable.ImmutableList

/**
 * Callout が指し示すアンカー点の情報。
 *
 * 画面座標（px）で指定する。地図のパン/ズームで変化する値を呼び出し側で計算して渡す。
 */
sealed interface CalloutAnchor {
    val id: Any
    val primaryPoint: Offset

    /**
     * 複数候補の中から重ならない位置を選んでよいアンカー。ルートプレビュー向け。
     *
     * @param id アンカーを識別する任意の値
     * @param primaryPoint 候補が全て不採用だった場合に使うフォールバック点
     * @param candidates tail 先端の候補点。並び順は優先度を表す（先頭が最優先）
     */
    @Immutable
    data class Flexible(
        override val id: Any,
        override val primaryPoint: Offset,
        val candidates: ImmutableList<Offset>,
    ) : CalloutAnchor

    /**
     * tail 先端を [primaryPoint] に必ず固定するアンカー。ナビ中の交差点/事故表示向け。
     *
     * @param id アンカーを識別する任意の値
     * @param primaryPoint tail 先端が指し示す画面座標
     */
    @Immutable
    data class Fixed(
        override val id: Any,
        override val primaryPoint: Offset,
    ) : CalloutAnchor
}
