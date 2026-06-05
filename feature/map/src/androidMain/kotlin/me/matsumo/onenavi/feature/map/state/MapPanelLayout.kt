package me.matsumo.onenavi.feature.map.state

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 地図画面の幅クラスと UI 帯の寸法・配置側。
 *
 * @param widthSizeClass 地図画面の幅クラス
 * @param panelWidth UI 帯の幅。Compact では 0dp
 * @param panelSide UI 帯を置く物理側
 */
@Immutable
internal data class MapPanelLayout(
    val widthSizeClass: MapWidthSizeClass,
    val panelWidth: Dp,
    val panelSide: MapPanelSide,
) {

    /** UI 帯を使う分割レイアウトが有効か。 */
    val isSplit: Boolean
        get() = widthSizeClass == MapWidthSizeClass.EXPANDED && panelWidth > 0.dp
}

/** 地図画面のレイアウト分岐に使う幅クラス。 */
internal enum class MapWidthSizeClass {
    COMPACT,
    EXPANDED,
}

/** UI 帯を置く物理側。 */
internal enum class MapPanelSide {
    LEFT,
    RIGHT,
}

/**
 * 地図画面の制約幅から UI 帯レイアウトを解決する。
 *
 * @param maxWidth 地図画面に与えられた最大幅
 * @param panelSide UI 帯を置く物理側
 * @return 幅クラスと UI 帯幅を含むレイアウト記述子
 */
internal fun resolveMapPanelLayout(
    maxWidth: Dp,
    panelSide: MapPanelSide = MapPanelSide.RIGHT,
): MapPanelLayout {
    val widthSizeClass = if (maxWidth >= MAP_PANEL_EXPANDED_MIN_WIDTH) {
        MapWidthSizeClass.EXPANDED
    } else {
        MapWidthSizeClass.COMPACT
    }

    return MapPanelLayout(
        widthSizeClass = widthSizeClass,
        panelWidth = if (widthSizeClass == MapWidthSizeClass.EXPANDED) {
            MAP_PANEL_WIDTH
        } else {
            0.dp
        },
        panelSide = panelSide,
    )
}

/** 分割レイアウトへ切り替える画面幅。 */
internal val MAP_PANEL_EXPANDED_MIN_WIDTH = 840.dp

/** 分割レイアウトで使う UI 帯の固定幅。 */
internal val MAP_PANEL_WIDTH = 400.dp
