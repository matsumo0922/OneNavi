package me.matsumo.onenavi.car.vd

/**
 * VD 入力デバッグ state を Compose へ publish してよいかを判定する gate。
 */
internal object CarVirtualDisplayProbeDebugStateGate {

    /**
     * 入力 state を debug overlay 向けに publish してよい場合に true を返す。
     *
     * @param isDebugOverlayEnabled VD デバッグオーバーレイが有効か
     * @return 入力 state を publish してよい場合は true
     */
    fun shouldPublishInputState(isDebugOverlayEnabled: Boolean): Boolean {
        return isDebugOverlayEnabled
    }
}
