package me.matsumo.onenavi.feature.map.state

/**
 * 地図カメラの表示 perspective。
 */
internal object MapCameraPerspective {

    /** 進行方向を上にした 3D 表示。 */
    const val TILTED = 0

    /** 北を上にした 2D 表示。 */
    const val TOP_DOWN_NORTH_UP = 1
}
