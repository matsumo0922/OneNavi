package me.matsumo.onenavi.core.navigation.extnav

import kotlinx.collections.immutable.persistentListOf
import me.matsumo.drive.supporter.api.guidance.domain.FlagsGroupEntry
import me.matsumo.onenavi.core.model.ManeuverModifier
import me.matsumo.onenavi.core.navigation.newguidance.semantic.LaneConfidence
import me.matsumo.onenavi.core.navigation.newguidance.semantic.LaneLayout
import me.matsumo.onenavi.core.navigation.newguidance.semantic.LaneSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [LaneDiagramParser] の flags_group → 車線図射影テスト。仕様の検証ケース (学園の森) に基づく。
 */
class LaneDiagramParserTest {

    private val parser = LaneDiagramParser()

    @Test
    fun `学園の森の車線図を方向とターゲットに復元する`() {
        // 左折+直進 / 直進 / 右折(target, append)
        val entries = listOf(
            FlagsGroupEntry(a = 0, b = 0, directions = persistentListOf(2, 4)),
            FlagsGroupEntry(a = 0, b = 0, directions = persistentListOf(4)),
            FlagsGroupEntry(a = 1, b = 1, directions = persistentListOf(6)),
        )

        val lane = parser.parse(entries = entries, sourceRefs = persistentListOf())

        val layout = assertIs<LaneLayout.DirectionLayout>(lane?.layout)
        assertEquals(3, layout.lanes.size)
        assertEquals(setOf(ManeuverModifier.LEFT, ManeuverModifier.STRAIGHT), layout.lanes[0].directions)
        assertEquals(setOf(ManeuverModifier.STRAIGHT), layout.lanes[1].directions)
        assertEquals(setOf(ManeuverModifier.RIGHT), layout.lanes[2].directions)
        assertTrue(layout.lanes[2].isTarget)
        assertTrue(layout.lanes[2].isAppend)
        assertTrue(!layout.lanes[0].isTarget)
        assertEquals(LaneConfidence.HIGH, lane?.confidence)
        assertTrue(lane?.sources?.contains(LaneSource.LANE_DIAGRAM) == true)
    }

    @Test
    fun `field_1 と field_2 が独立に解釈される`() {
        // 学園の森中央: 左折+直進 / 直進(target) / 右折(append)
        val entries = listOf(
            FlagsGroupEntry(a = 0, b = 0, directions = persistentListOf(2, 4)),
            FlagsGroupEntry(a = 0, b = 1, directions = persistentListOf(4)),
            FlagsGroupEntry(a = 1, b = 0, directions = persistentListOf(6)),
        )

        val lane = parser.parse(entries = entries, sourceRefs = persistentListOf())
        val layout = assertIs<LaneLayout.DirectionLayout>(lane?.layout)

        assertTrue(layout.lanes[1].isTarget)
        assertTrue(!layout.lanes[1].isAppend)
        assertTrue(!layout.lanes[2].isTarget)
        assertTrue(layout.lanes[2].isAppend)
    }

    @Test
    fun `レーンが1つだけなら車線図とみなさない`() {
        val entries = listOf(FlagsGroupEntry(a = 0, b = 0, directions = persistentListOf(4)))

        assertNull(parser.parse(entries = entries, sourceRefs = persistentListOf()))
    }

    @Test
    fun `既知方向が1つ未満なら車線図とみなさない`() {
        // 方向コードが未確定 (5/7) のみの entry は車線図ではない。
        val entries = listOf(
            FlagsGroupEntry(a = 0, b = 0, directions = persistentListOf(5)),
            FlagsGroupEntry(a = 0, b = 0, directions = persistentListOf(7)),
        )

        assertNull(parser.parse(entries = entries, sourceRefs = persistentListOf()))
    }

    @Test
    fun `未確定の方向コードは捨てて既知方向だけ残す`() {
        val entries = listOf(
            FlagsGroupEntry(a = 0, b = 0, directions = persistentListOf(4, 5)),
            FlagsGroupEntry(a = 0, b = 1, directions = persistentListOf(6)),
        )

        val lane = parser.parse(entries = entries, sourceRefs = persistentListOf())
        val layout = assertIs<LaneLayout.DirectionLayout>(lane?.layout)

        assertEquals(setOf(ManeuverModifier.STRAIGHT), layout.lanes[0].directions)
        assertEquals(setOf(ManeuverModifier.RIGHT), layout.lanes[1].directions)
    }
}
