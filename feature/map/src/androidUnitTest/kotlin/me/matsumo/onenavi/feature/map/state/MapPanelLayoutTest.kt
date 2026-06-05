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
    fun `Compact では左右に基本 padding だけを返す`() {
        val layout = resolveMapPanelLayout(maxWidth = 839.dp)

        val horizontalPadding = layout.resolveHorizontalCameraPaddingPx(
            basePaddingPx = 24,
            panelWidthPx = 400,
        )

        assertEquals(24 to 24, horizontalPadding)
    }

    @Test
    fun `右 UI 帯では左右 padding に UI 帯幅を加算する`() {
        val layout = resolveMapPanelLayout(
            maxWidth = 840.dp,
            panelSide = MapPanelSide.RIGHT,
        )

        val horizontalPadding = layout.resolveHorizontalCameraPaddingPx(
            basePaddingPx = 24,
            panelWidthPx = 400,
        )

        assertEquals(424 to 424, horizontalPadding)
    }

    @Test
    fun `左 UI 帯では左右 padding に UI 帯幅を加算する`() {
        val layout = resolveMapPanelLayout(
            maxWidth = 840.dp,
            panelSide = MapPanelSide.LEFT,
        )

        val horizontalPadding = layout.resolveHorizontalCameraPaddingPx(
            basePaddingPx = 24,
            panelWidthPx = 400,
        )

        assertEquals(424 to 424, horizontalPadding)
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
    fun `右 UI 帯では MapView を左へはみ出させて地図領域中心に寄せる`() {
        val layout = resolveMapPanelLayout(
            maxWidth = 840.dp,
            panelSide = MapPanelSide.RIGHT,
        )

        val canvasLayout = layout.resolveCanvasLayout(viewportWidth = 840.dp)

        assertEquals(1240.dp, canvasLayout.width)
        assertEquals((-400).dp, canvasLayout.offsetX)
        assertEquals(400.dp, canvasLayout.horizontalInset)
    }

    @Test
    fun `左 UI 帯では MapView を右へはみ出させて地図領域中心に寄せる`() {
        val layout = resolveMapPanelLayout(
            maxWidth = 840.dp,
            panelSide = MapPanelSide.LEFT,
        )

        val canvasLayout = layout.resolveCanvasLayout(viewportWidth = 840.dp)

        assertEquals(1240.dp, canvasLayout.width)
        assertEquals(0.dp, canvasLayout.offsetX)
        assertEquals(400.dp, canvasLayout.horizontalInset)
    }
}
