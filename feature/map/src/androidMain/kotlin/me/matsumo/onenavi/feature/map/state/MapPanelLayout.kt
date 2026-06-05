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

    /**
     * GoogleMap に渡す左右 padding を返す。
     *
     * 分割レイアウトでは MapView の実 viewport を UI 帯幅ぶん横へ広げるため、
     * 左右に同じ inset を足して padded center と 3D 投影中心を一致させる。
     *
     * @param basePaddingPx 左右に常に確保する基本 padding（px）
     * @param panelWidthPx UI 帯の幅（px）
     * @return start padding と end padding
     */
    fun resolveHorizontalCameraPaddingPx(
        basePaddingPx: Int,
        panelWidthPx: Int,
    ): Pair<Int, Int> {
        if (!isSplit) {
            return basePaddingPx to basePaddingPx
        }

        val splitPaddingPx = basePaddingPx + panelWidthPx
        return splitPaddingPx to splitPaddingPx
    }

    /**
     * MapView の実 viewport 配置を返す。
     *
     * 分割レイアウトでは実 MapView を画面より UI 帯幅ぶん広くし、UI 帯と反対側へはみ出させる。
     * これにより画面全域を MapView で覆ったまま、実 viewport の中心を地図領域の中心へ置く。
     *
     * @param viewportWidth 画面に表示される地図コンテナ幅
     * @return MapView の実幅・オフセット・padding 用 inset
     */
    fun resolveCanvasLayout(viewportWidth: Dp): MapCanvasLayout {
        if (!isSplit) {
            return MapCanvasLayout(
                width = viewportWidth,
                offsetX = 0.dp,
                horizontalInset = 0.dp,
            )
        }

        return MapCanvasLayout(
            width = viewportWidth + panelWidth,
            offsetX = when (panelSide) {
                MapPanelSide.LEFT -> 0.dp
                MapPanelSide.RIGHT -> -panelWidth
            },
            horizontalInset = panelWidth,
        )
    }
}

/**
 * MapView の実 viewport 配置。
 *
 * @param width MapView に与える実幅
 * @param offsetX 画面上に配置する X オフセット
 * @param horizontalInset 画面外にはみ出した領域と UI 帯を避けるため左右 padding に足す幅
 */
@Immutable
internal data class MapCanvasLayout(
    val width: Dp,
    val offsetX: Dp,
    val horizontalInset: Dp,
)

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
