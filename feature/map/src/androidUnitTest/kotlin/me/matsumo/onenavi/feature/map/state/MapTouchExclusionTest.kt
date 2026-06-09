package me.matsumo.onenavi.feature.map.state

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 地図より手前の UI 領域を touch から守る判定を検証するテスト。 */
class MapTouchExclusionTest {

    @Test
    fun `全画面 overlay 表示中は画面内の点を除外する`() {
        val exclusion = compactExclusion(isFullScreenExcluded = true)

        assertTrue(
            exclusion.contains(
                pointX = 500f,
                pointY = 300f,
                viewportWidth = VIEWPORT_WIDTH,
                viewportHeight = VIEWPORT_HEIGHT,
            ),
        )
    }

    @Test
    fun `画面外の点は除外しない`() {
        val exclusion = compactExclusion(compactTopInsetPx = 80)

        assertFalse(
            exclusion.contains(
                pointX = -1f,
                pointY = 40f,
                viewportWidth = VIEWPORT_WIDTH,
                viewportHeight = VIEWPORT_HEIGHT,
            ),
        )
        assertFalse(
            exclusion.contains(
                pointX = 500f,
                pointY = VIEWPORT_HEIGHT + 1f,
                viewportWidth = VIEWPORT_WIDTH,
                viewportHeight = VIEWPORT_HEIGHT,
            ),
        )
    }

    @Test
    fun `右分割では右側 UI 帯を除外する`() {
        val exclusion = splitExclusion(panelSide = MapPanelSide.RIGHT)

        assertFalse(
            exclusion.contains(
                pointX = 699f,
                pointY = 300f,
                viewportWidth = VIEWPORT_WIDTH,
                viewportHeight = VIEWPORT_HEIGHT,
            ),
        )
        assertTrue(
            exclusion.contains(
                pointX = 700f,
                pointY = 300f,
                viewportWidth = VIEWPORT_WIDTH,
                viewportHeight = VIEWPORT_HEIGHT,
            ),
        )
    }

    @Test
    fun `左分割では左側 UI 帯を除外する`() {
        val exclusion = splitExclusion(panelSide = MapPanelSide.LEFT)

        assertTrue(
            exclusion.contains(
                pointX = 300f,
                pointY = 300f,
                viewportWidth = VIEWPORT_WIDTH,
                viewportHeight = VIEWPORT_HEIGHT,
            ),
        )
        assertFalse(
            exclusion.contains(
                pointX = 301f,
                pointY = 300f,
                viewportWidth = VIEWPORT_WIDTH,
                viewportHeight = VIEWPORT_HEIGHT,
            ),
        )
    }

    @Test
    fun `Compact では上部 UI と下部 UI と controls カラムを除外する`() {
        val exclusion = compactExclusion(
            compactTopInsetPx = 80,
            compactBottomInsetPx = 120,
            compactControlsInsetPx = 96,
        )

        assertTrue(
            exclusion.contains(
                pointX = 500f,
                pointY = 80f,
                viewportWidth = VIEWPORT_WIDTH,
                viewportHeight = VIEWPORT_HEIGHT,
            ),
        )
        assertTrue(
            exclusion.contains(
                pointX = 500f,
                pointY = 480f,
                viewportWidth = VIEWPORT_WIDTH,
                viewportHeight = VIEWPORT_HEIGHT,
            ),
        )
        assertTrue(
            exclusion.contains(
                pointX = 904f,
                pointY = 300f,
                viewportWidth = VIEWPORT_WIDTH,
                viewportHeight = VIEWPORT_HEIGHT,
            ),
        )
        assertFalse(
            exclusion.contains(
                pointX = 500f,
                pointY = 300f,
                viewportWidth = VIEWPORT_WIDTH,
                viewportHeight = VIEWPORT_HEIGHT,
            ),
        )
    }

    private fun compactExclusion(
        isFullScreenExcluded: Boolean = false,
        compactTopInsetPx: Int = 0,
        compactBottomInsetPx: Int = 0,
        compactControlsInsetPx: Int = 0,
    ): MapTouchExclusion {
        return MapTouchExclusion(
            isFullScreenExcluded = isFullScreenExcluded,
            panelLayout = MapPanelLayout(
                widthSizeClass = MapWidthSizeClass.COMPACT,
                panelWidth = 0.dp,
                panelSide = MapPanelSide.RIGHT,
            ),
            splitInsetPx = 0,
            compactTopInsetPx = compactTopInsetPx,
            compactBottomInsetPx = compactBottomInsetPx,
            compactControlsInsetPx = compactControlsInsetPx,
        )
    }

    private fun splitExclusion(panelSide: MapPanelSide): MapTouchExclusion {
        return MapTouchExclusion(
            isFullScreenExcluded = false,
            panelLayout = MapPanelLayout(
                widthSizeClass = MapWidthSizeClass.EXPANDED,
                panelWidth = 240.dp,
                panelSide = panelSide,
            ),
            splitInsetPx = 300,
            compactTopInsetPx = 0,
            compactBottomInsetPx = 0,
            compactControlsInsetPx = 0,
        )
    }

    /** touch 除外テストで使う固定 viewport 値。 */
    private companion object {

        /** viewport 幅。 */
        private const val VIEWPORT_WIDTH = 1_000

        /** viewport 高さ。 */
        private const val VIEWPORT_HEIGHT = 600
    }
}
