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
}
