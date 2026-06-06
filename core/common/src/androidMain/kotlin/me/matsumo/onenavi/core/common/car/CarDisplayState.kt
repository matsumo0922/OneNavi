package me.matsumo.onenavi.core.common.car

import java.util.concurrent.atomic.AtomicBoolean

/**
 * 車載ディスプレイ(Android Auto)上で OneNavi が表示中かをプロセス全体で共有するフラグ。
 *
 * CarActivity のライフサイクルで更新し、案内音声の出力チャンネル選択などプロセス内 singleton から
 * 参照する。スマホ側 (MainActivity) からは更新されないため、車載中のみ true となる。
 */
object CarDisplayState {

    private val active = AtomicBoolean(false)

    /** 車載ディスプレイ上で表示中なら true。 */
    val isOnCar: Boolean get() = active.get()

    /** 車載ディスプレイ上での表示状態を設定する。 */
    fun setOnCar(isOnCar: Boolean) {
        active.set(isOnCar)
    }
}
