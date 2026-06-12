package me.matsumo.onenavi.feature.map.state

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 地図 host が保証する表示可能領域から算出した inset。 */
@Immutable
data class MapHostInsets(
    val start: Dp = 0.dp,
    val top: Dp = 0.dp,
    val end: Dp = 0.dp,
    val bottom: Dp = 0.dp,
) {

    /** 各辺ごとに大きい inset を採用した値を返す。 */
    fun maxWith(other: MapHostInsets): MapHostInsets {
        return MapHostInsets(
            start = maxOf(start, other.start),
            top = maxOf(top, other.top),
            end = maxOf(end, other.end),
            bottom = maxOf(bottom, other.bottom),
        )
    }

    /** 左右に追加の inset を足した値を返す。 */
    fun withAddedHorizontal(inset: Dp): MapHostInsets {
        return copy(
            start = start + inset,
            end = end + inset,
        )
    }

    /** 地図 host inset の既定値。 */
    companion object {

        /** host による追加 inset が無い状態。 */
        val Zero = MapHostInsets()
    }
}

/** 地図 host から通知された visible/stable 領域を Compose 側へ渡す state。 */
@Immutable
data class MapHostViewport(
    val visibleInsets: MapHostInsets = MapHostInsets.Zero,
    val stableInsets: MapHostInsets = MapHostInsets.Zero,
) {

    /** 地図 host viewport の既定値。 */
    companion object {

        /** host による追加 viewport 制約が無い状態。 */
        val Zero = MapHostViewport()
    }
}

/** 現在の地図 host viewport を提供する CompositionLocal。 */
val LocalMapHostViewport = staticCompositionLocalOf { MapHostViewport.Zero }

/** 地図 overlay UI が避けるべき inset を system bars と host stable insets から解決する。 */
internal fun resolveMapContentInsets(systemBarInsets: MapHostInsets, hostStableInsets: MapHostInsets): MapHostInsets {
    return systemBarInsets.maxWith(hostStableInsets)
}
