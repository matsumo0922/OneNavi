package me.matsumo.onenavi.feature.map.state

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 地図画面の UI 帯レイアウト判定を検証するテスト。 */
class MapPanelLayoutTest {

    @Test
    fun `560dp 未満では Compact になり UI 帯を持たない`() {
        val layout = resolveMapPanelLayout(maxWidth = 559.dp)

        assertEquals(MapWidthSizeClass.COMPACT, layout.widthSizeClass)
        assertEquals(0.dp, layout.panelWidth)
        assertFalse(layout.isSplit)
    }

    @Test
    fun `560dp 以上では Expanded になり地図が残り比率ぶん残る UI 帯を持つ`() {
        val layout = resolveMapPanelLayout(maxWidth = 560.dp)

        assertEquals(MapWidthSizeClass.EXPANDED, layout.widthSizeClass)
        assertEquals(252.dp, layout.panelWidth)
        assertEquals(MapPanelSide.RIGHT, layout.panelSide)
        assertTrue(layout.isSplit)
    }

    @Test
    fun `UI 帯幅は画面が広くても上限 400dp で頭打ちになる`() {
        val layout = resolveMapPanelLayout(maxWidth = 1328.dp)

        assertEquals(MAP_PANEL_MAX_WIDTH, layout.panelWidth)
        assertEquals(400.dp, layout.panelWidth)
    }

    @Test
    fun `可視地図幅は常に画面幅の規定比率以上を保つ`() {
        val narrowLayout = resolveMapPanelLayout(maxWidth = 560.dp)
        val wideLayout = resolveMapPanelLayout(maxWidth = 1328.dp)

        assertEquals(252.dp, narrowLayout.visibleMapWidth(viewportWidth = 560.dp))
        assertEquals(872.dp, wideLayout.visibleMapWidth(viewportWidth = 1328.dp))
    }

    @Test
    fun `分割時の横 inset は UI 帯 + controls カラム幅になる`() {
        val splitLayout = resolveMapPanelLayout(maxWidth = 1328.dp)
        val compactLayout = resolveMapPanelLayout(maxWidth = 559.dp)

        assertEquals(MAP_PANEL_MAX_WIDTH + MAP_CONTROLS_COLUMN_WIDTH, splitLayout.splitHorizontalInset)
        assertEquals(456.dp, splitLayout.splitHorizontalInset)
        assertEquals(0.dp, compactLayout.splitHorizontalInset)
    }

    @Test
    fun `Compact では左右に基本 padding だけを返す`() {
        val layout = resolveMapPanelLayout(maxWidth = 559.dp)

        val horizontalPadding = layout.resolveHorizontalCameraPaddingPx(
            basePaddingPx = 24,
            splitInsetPx = 456,
        )

        assertEquals(24 to 24, horizontalPadding)
    }

    @Test
    fun `右 UI 帯では左右 padding に inset を加算する`() {
        val layout = resolveMapPanelLayout(
            maxWidth = 1328.dp,
            panelSide = MapPanelSide.RIGHT,
        )

        val horizontalPadding = layout.resolveHorizontalCameraPaddingPx(
            basePaddingPx = 24,
            splitInsetPx = 456,
        )

        assertEquals(480 to 480, horizontalPadding)
    }

    @Test
    fun `左 UI 帯では左右 padding に inset を加算する`() {
        val layout = resolveMapPanelLayout(
            maxWidth = 1328.dp,
            panelSide = MapPanelSide.LEFT,
        )

        val horizontalPadding = layout.resolveHorizontalCameraPaddingPx(
            basePaddingPx = 24,
            splitInsetPx = 456,
        )

        assertEquals(480 to 480, horizontalPadding)
    }

    @Test
    fun `Compact では MapView を画面幅のまま配置する`() {
        val layout = resolveMapPanelLayout(maxWidth = 559.dp)

        val canvasLayout = layout.resolveCanvasLayout(viewportWidth = 559.dp)

        assertEquals(559.dp, canvasLayout.width)
        assertEquals(0.dp, canvasLayout.offsetX)
        assertEquals(0.dp, canvasLayout.horizontalInset)
    }

    @Test
    fun `右 UI 帯では MapView を左へ inset ぶんはみ出させて地図領域中心に寄せる`() {
        val layout = resolveMapPanelLayout(
            maxWidth = 1328.dp,
            panelSide = MapPanelSide.RIGHT,
        )

        val canvasLayout = layout.resolveCanvasLayout(viewportWidth = 1328.dp)

        assertEquals(1784.dp, canvasLayout.width)
        assertEquals((-456).dp, canvasLayout.offsetX)
        assertEquals(456.dp, canvasLayout.horizontalInset)
    }

    @Test
    fun `左 UI 帯では MapView を右へ inset ぶんはみ出させて地図領域中心に寄せる`() {
        val layout = resolveMapPanelLayout(
            maxWidth = 1328.dp,
            panelSide = MapPanelSide.LEFT,
        )

        val canvasLayout = layout.resolveCanvasLayout(viewportWidth = 1328.dp)

        assertEquals(1784.dp, canvasLayout.width)
        assertEquals(0.dp, canvasLayout.offsetX)
        assertEquals(456.dp, canvasLayout.horizontalInset)
    }
}
