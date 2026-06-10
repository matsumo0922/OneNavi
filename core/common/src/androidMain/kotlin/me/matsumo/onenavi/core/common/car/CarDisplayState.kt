package me.matsumo.onenavi.core.common.car

import java.util.concurrent.atomic.AtomicInteger

/**
 * 車載ディスプレイ(Android Auto)上で OneNavi が表示中かをプロセス全体で共有するフラグ。
 *
 * Android Auto の session ライフサイクルで更新し、案内音声の出力チャンネル選択などプロセス内
 * singleton から参照する。スマホ側 (MainActivity) からは更新されないため、車載中のみ true となる。
 */
object CarDisplayState {

    private val activeDisplayCount = AtomicInteger(0)

    /** 車載ディスプレイ上で表示中なら true。 */
    val isOnCar: Boolean get() = activeDisplayCount.get() > 0

    /** 車載ディスプレイ上の表示 entry point が開始したことを記録する。 */
    fun registerCarDisplay() {
        activeDisplayCount.incrementAndGet()
    }

    /** 車載ディスプレイ上の表示 entry point が終了したことを記録する。 */
    fun unregisterCarDisplay() {
        activeDisplayCount.updateAndGet { currentCount ->
            maxOf(currentCount - 1, 0)
        }
    }
}
