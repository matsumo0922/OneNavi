package me.matsumo.onenavi.feature.home.map.components

import androidx.compose.runtime.Immutable

/**
 * 吹き出しの視覚スタイル定義。
 * ルートプレビューやナビゲーション中の交差点吹き出しなど、
 * 用途に応じたファクトリメソッドでインスタンスを生成する。
 *
 * @param backgroundColor 背景色（ARGB Int）
 * @param textColor テキスト色（ARGB Int）
 * @param shadowColor 影色（ARGB Int）
 * @param textSizeSp テキストサイズ（SP）
 */
@Immutable
data class RouteCalloutStyle(
    val backgroundColor: Int,
    val textColor: Int,
    val shadowColor: Int,
    val textSizeSp: Float,
) {
    companion object {
        /** ルートプレビュー用の吹き出しスタイルを返す。 */
        fun forRoute(isPrimary: Boolean): RouteCalloutStyle = if (isPrimary) {
            RouteCalloutStyle(
                backgroundColor = 0xFF4285F4.toInt(),
                textColor = 0xFFFFFFFF.toInt(),
                shadowColor = 0x40000000,
                textSizeSp = 14f,
            )
        } else {
            RouteCalloutStyle(
                backgroundColor = 0xFFFFFFFF.toInt(),
                textColor = 0xFF333333.toInt(),
                shadowColor = 0x40000000,
                textSizeSp = 14f,
            )
        }
    }
}
