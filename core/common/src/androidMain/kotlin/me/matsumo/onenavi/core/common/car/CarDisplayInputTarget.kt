package me.matsumo.onenavi.core.common.car

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 車載 display の surface 座標で表した入力対象矩形。
 *
 * @property left 矩形の左端 px。
 * @property top 矩形の上端 px。
 * @property right 矩形の右端 px。
 * @property bottom 矩形の下端 px。
 */
@Immutable
data class CarDisplayInputTargetRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {

    val width: Float
        get() = right - left

    val height: Float
        get() = bottom - top

    val centerX: Float
        get() = left + width / 2f

    val centerY: Float
        get() = top + height / 2f

    val isValid: Boolean
        get() = width > 0f && height > 0f
}

/** 車載 display 上で前面入力対象を host 側へ伝える reporter。 */
interface CarDisplayInputTargetReporter {

    /** scroll / fling を優先して届けたい矩形を更新する。 */
    fun updateScrollTargetRect(rect: CarDisplayInputTargetRect?)
}

/** 何も報告しない既定 reporter。 */
private object NoOpCarDisplayInputTargetReporter : CarDisplayInputTargetReporter {

    override fun updateScrollTargetRect(rect: CarDisplayInputTargetRect?) = Unit
}

/** 現在の Compose ツリーから車載 display の入力対象を報告する CompositionLocal。 */
val LocalCarDisplayInputTargetReporter = staticCompositionLocalOf<CarDisplayInputTargetReporter> {
    NoOpCarDisplayInputTargetReporter
}
