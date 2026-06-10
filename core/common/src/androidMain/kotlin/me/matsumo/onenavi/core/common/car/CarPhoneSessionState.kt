package me.matsumo.onenavi.core.common.car

/**
 * 車載表示とスマホ表示のプロセス内同期状態。
 *
 * @property activeSurfaces 現在表示中の表示面
 */
data class CarPhoneSessionState(
    val activeSurfaces: Set<OneNaviDisplaySurface> = emptySet(),
) {

    /** Android Auto の仮想表示が有効なら true。 */
    val isAndroidAutoVirtualDisplayActive: Boolean
        get() = OneNaviDisplaySurface.AndroidAutoVirtualDisplay in activeSurfaces

    /** スマホ表示が有効なら true。 */
    val isPhoneActive: Boolean
        get() = OneNaviDisplaySurface.Phone in activeSurfaces
}
