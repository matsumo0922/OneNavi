package me.matsumo.onenavi.core.navigation.newguidance.model

/**
 * 案内中の位置が実測値か推定値かを表す種別。
 */
enum class VehiclePositionSource {
    /** 端末または車両側から観測された位置を route に投影した状態。 */
    OBSERVED,

    /** 測位途絶中に route geometry と速度から推定した状態。 */
    DEAD_RECKONING,

    /** 案内開始直後など、実測でも推定でもない初期表示用の状態。 */
    INITIAL,
}
