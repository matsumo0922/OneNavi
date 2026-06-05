package me.matsumo.onenavi.feature.map.state

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 地図画面の UI 帯レイアウト判定を検証するテスト。 */
class MapPanelLayoutTest {

    @Test
    fun `840dp 未満では Compact になり UI 帯を持たない`() {
        val layout = resolveMapPanelLayout(maxWidth = 839.dp)

        assertEquals(MapWidthSizeClass.COMPACT, layout.widthSizeClass)
        assertEquals(0.dp, layout.panelWidth)
        assertFalse(layout.isSplit)
    }

    @Test
    fun `840dp 以上では Expanded になり 400dp の UI 帯を持つ`() {
        val layout = resolveMapPanelLayout(maxWidth = 840.dp)

        assertEquals(MapWidthSizeClass.EXPANDED, layout.widthSizeClass)
        assertEquals(400.dp, layout.panelWidth)
        assertEquals(MapPanelSide.RIGHT, layout.panelSide)
        assertTrue(layout.isSplit)
    }

    @Test
    fun `分割時の横 inset は UI 帯 + controls カラム幅になる`() {
        val splitLayout = resolveMapPanelLayout(maxWidth = 840.dp)
        val compactLayout = resolveMapPanelLayout(maxWidth = 839.dp)

        assertEquals(MAP_PANEL_WIDTH + MAP_CONTROLS_COLUMN_WIDTH, splitLayout.splitHorizontalInset)
        assertEquals(488.dp, splitLayout.splitHorizontalInset)
        assertEquals(0.dp, compactLayout.splitHorizontalInset)
    }

    @Test
    fun `Compact では左右に基本 padding だけを返す`() {
        val layout = resolveMapPanelLayout(maxWidth = 839.dp)

        val horizontalPadding = layout.resolveHorizontalCameraPaddingPx(
            basePaddingPx = 24,
            splitInsetPx = 488,
        )

        assertEquals(24 to 24, horizontalPadding)
    }

    @Test
    fun `右 UI 帯では左右 padding に inset を加算する`() {
        val layout = resolveMapPanelLayout(
            maxWidth = 840.dp,
            panelSide = MapPanelSide.RIGHT,
        )

        val horizontalPadding = layout.resolveHorizontalCameraPaddingPx(
            basePaddingPx = 24,
            splitInsetPx = 488,
        )

        assertEquals(512 to 512, horizontalPadding)
    }

    @Test
    fun `左 UI 帯では左右 padding に inset を加算する`() {
        val layout = resolveMapPanelLayout(
            maxWidth = 840.dp,
            panelSide = MapPanelSide.LEFT,
        )

        val horizontalPadding = layout.resolveHorizontalCameraPaddingPx(
            basePaddingPx = 24,
            splitInsetPx = 488,
        )

        assertEquals(512 to 512, horizontalPadding)
    }

    @Test
    fun `Compact では MapView を画面幅のまま配置する`() {
        val layout = resolveMapPanelLayout(maxWidth = 839.dp)

        val canvasLayout = layout.resolveCanvasLayout(viewportWidth = 839.dp)

        assertEquals(839.dp, canvasLayout.width)
        assertEquals(0.dp, canvasLayout.offsetX)
        assertEquals(0.dp, canvasLayout.horizontalInset)
    }

    @Test
    fun `右 UI 帯では MapView を左へ inset ぶんはみ出させて地図領域中心に寄せる`() {
        val layout = resolveMapPanelLayout(
            maxWidth = 840.dp,
            panelSide = MapPanelSide.RIGHT,
        )

        val canvasLayout = layout.resolveCanvasLayout(viewportWidth = 840.dp)

        assertEquals(1328.dp, canvasLayout.width)
        assertEquals((-488).dp, canvasLayout.offsetX)
        assertEquals(488.dp, canvasLayout.horizontalInset)
    }

    @Test
    fun `左 UI 帯では MapView を右へ inset ぶんはみ出させて地図領域中心に寄せる`() {
        val layout = resolveMapPanelLayout(
            maxWidth = 840.dp,
            panelSide = MapPanelSide.LEFT,
        )

        val canvasLayout = layout.resolveCanvasLayout(viewportWidth = 840.dp)

        assertEquals(1328.dp, canvasLayout.width)
        assertEquals(0.dp, canvasLayout.offsetX)
        assertEquals(488.dp, canvasLayout.horizontalInset)
    }
}
