package me.matsumo.onenavi.feature.map.state

import androidx.compose.runtime.Immutable

/**
 * MapView へ touch を渡さない画面上の UI 領域。
 *
 * @param isFullScreenExcluded 全画面 overlay 表示中なら true
 * @param panelLayout 地図画面の UI 帯配置
 * @param splitInsetPx split 時に UI 帯と controls が占める幅
 * @param compactTopInsetPx compact 時に上側 UI が占める高さ
 * @param compactBottomInsetPx compact 時に下側 UI が占める高さ
 * @param compactControlsInsetPx compact 時に controls が占める幅
 */
@Immutable
internal data class MapTouchExclusion(
    val isFullScreenExcluded: Boolean,
    val panelLayout: MapPanelLayout,
    val splitInsetPx: Int,
    val compactTopInsetPx: Int,
    val compactBottomInsetPx: Int,
    val compactControlsInsetPx: Int,
) {

    /**
     * 指定された点が地図より手前の UI 領域に含まれるかを返す。
     *
     * @param pointX 画面内 X 座標
     * @param pointY 画面内 Y 座標
     * @param viewportWidth 画面幅
     * @param viewportHeight 画面高さ
     * @return MapView に渡さない場合 true
     */
    fun contains(
        pointX: Float,
        pointY: Float,
        viewportWidth: Int,
        viewportHeight: Int,
    ): Boolean {
        if (isFullScreenExcluded) {
            return true
        }

        val isInsideHorizontalViewport = pointX >= 0f && pointX <= viewportWidth.toFloat()
        val isInsideVerticalViewport = pointY >= 0f && pointY <= viewportHeight.toFloat()
        val isInsideViewport = isInsideHorizontalViewport && isInsideVerticalViewport

        if (!isInsideViewport) {
            return false
        }

        return if (panelLayout.isSplit) {
            containsSplitUiBand(
                pointX = pointX,
                viewportWidth = viewportWidth,
            )
        } else {
            containsCompactUiBand(
                pointX = pointX,
                pointY = pointY,
                viewportWidth = viewportWidth,
                viewportHeight = viewportHeight,
            )
        }
    }

    private fun containsSplitUiBand(
        pointX: Float,
        viewportWidth: Int,
    ): Boolean {
        if (splitInsetPx <= 0) {
            return false
        }

        return when (panelLayout.panelSide) {
            MapPanelSide.LEFT -> pointX <= splitInsetPx.toFloat()
            MapPanelSide.RIGHT -> pointX >= (viewportWidth - splitInsetPx).toFloat()
        }
    }

    private fun containsCompactUiBand(
        pointX: Float,
        pointY: Float,
        viewportWidth: Int,
        viewportHeight: Int,
    ): Boolean {
        val isInsideTopUi = compactTopInsetPx > 0 && pointY <= compactTopInsetPx.toFloat()
        val compactBottomTop = (viewportHeight - compactBottomInsetPx).toFloat()
        val compactControlsLeft = (viewportWidth - compactControlsInsetPx).toFloat()
        val isInsideBottomUi = compactBottomInsetPx > 0 && pointY >= compactBottomTop
        val isInsideControlsUi = compactControlsInsetPx > 0 && pointX >= compactControlsLeft

        return isInsideTopUi || isInsideBottomUi || isInsideControlsUi
    }
}
