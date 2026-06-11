package me.matsumo.onenavi.core.common.car

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 車載ディスプレイ(Android Auto)上で OneNavi が表示中かをプロセス全体で共有するフラグ。
 *
 * Android Auto の session ライフサイクルで更新し、案内音声の出力チャンネル選択などプロセス内
 * singleton から参照する。スマホ側 (MainActivity) からは更新されないため、車載中のみ true となる。
 */
object CarDisplayState {

    /** 車載ディスプレイ entry point 数の増減単位。 */
    private const val ACTIVE_DISPLAY_COUNT_INCREMENT = 1

    private val lock = Any()
    private val _isOnCarFlow = MutableStateFlow(false)
    private var activeDisplayCount = 0

    /** 車載ディスプレイ上で表示中なら true。 */
    val isOnCar: Boolean get() = _isOnCarFlow.value

    /** 車載ディスプレイの接続状態を通知する flow。 */
    val isOnCarFlow: StateFlow<Boolean> = _isOnCarFlow.asStateFlow()

    /** 車載ディスプレイ上の表示 entry point が開始したことを記録する。 */
    fun registerCarDisplay() {
        synchronized(lock) {
            activeDisplayCount += ACTIVE_DISPLAY_COUNT_INCREMENT
            publishStateLocked()
        }
    }

    /** 車載ディスプレイ上の表示 entry point が終了したことを記録する。 */
    fun unregisterCarDisplay() {
        synchronized(lock) {
            activeDisplayCount = maxOf(activeDisplayCount - ACTIVE_DISPLAY_COUNT_INCREMENT, 0)
            publishStateLocked()
        }
    }

    private fun publishStateLocked() {
        _isOnCarFlow.value = activeDisplayCount > 0
    }
}
