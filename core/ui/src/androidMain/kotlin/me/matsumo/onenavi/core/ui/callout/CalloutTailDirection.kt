package me.matsumo.onenavi.core.ui.callout

/**
 * Callout の吹き出し口が伸びる方向。矩形の 4 コーナーのうちどのコーナーを外側へ引っ張るかを表す。
 */
enum class CalloutTailDirection {
    TopLeft,
    TopRight,
    BottomLeft,
    BottomRight,
    ;

    val isTop: Boolean get() = this == TopLeft || this == TopRight
    val isBottom: Boolean get() = this == BottomLeft || this == BottomRight
    val isLeft: Boolean get() = this == TopLeft || this == BottomLeft
    val isRight: Boolean get() = this == TopRight || this == BottomRight
}
