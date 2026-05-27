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
            FlagsGroupEntry(a = 0, b = 0, compactDirections = persistentListOf(2, 4)),
            FlagsGroupEntry(a = 0, b = 0, compactDirections = persistentListOf(4)),
            FlagsGroupEntry(a = 1, b = 1, compactDirections = persistentListOf(6)),
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
            FlagsGroupEntry(a = 0, b = 0, compactDirections = persistentListOf(2, 4)),
            FlagsGroupEntry(a = 0, b = 1, compactDirections = persistentListOf(4)),
            FlagsGroupEntry(a = 1, b = 0, compactDirections = persistentListOf(6)),
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
        val entries = listOf(FlagsGroupEntry(a = 0, b = 0, compactDirections = persistentListOf(4)))

        assertNull(parser.parse(entries = entries, sourceRefs = persistentListOf()))
    }

    @Test
    fun `compact code 3-5-7 を手前左 斜め右 手前右 に対応させる`() {
        // 手前左+直進 / 斜め右(target) / 手前右
        val entries = listOf(
            FlagsGroupEntry(a = 0, b = 0, compactDirections = persistentListOf(3, 4)),
            FlagsGroupEntry(a = 0, b = 1, compactDirections = persistentListOf(5)),
            FlagsGroupEntry(a = 0, b = 0, compactDirections = persistentListOf(7)),
        )

        val lane = parser.parse(entries = entries, sourceRefs = persistentListOf())
        val layout = assertIs<LaneLayout.DirectionLayout>(lane?.layout)

        assertEquals(setOf(ManeuverModifier.SHARP_LEFT, ManeuverModifier.STRAIGHT), layout.lanes[0].directions)
        assertEquals(setOf(ManeuverModifier.SLIGHT_RIGHT), layout.lanes[1].directions)
        assertEquals(setOf(ManeuverModifier.SHARP_RIGHT), layout.lanes[2].directions)
    }

    @Test
    fun `既知方向が1つ未満なら車線図とみなさない`() {
        // 範囲外の compact code (0 / 9) のみの entry は車線図ではない。
        val entries = listOf(
            FlagsGroupEntry(a = 0, b = 0, compactDirections = persistentListOf(0)),
            FlagsGroupEntry(a = 0, b = 0, compactDirections = persistentListOf(9)),
        )

        assertNull(parser.parse(entries = entries, sourceRefs = persistentListOf()))
    }

    @Test
    fun `範囲外の compact code は捨てて既知方向だけ残す`() {
        val entries = listOf(
            FlagsGroupEntry(a = 0, b = 0, compactDirections = persistentListOf(4, 9)),
            FlagsGroupEntry(a = 0, b = 1, compactDirections = persistentListOf(6)),
        )

        val lane = parser.parse(entries = entries, sourceRefs = persistentListOf())
        val layout = assertIs<LaneLayout.DirectionLayout>(lane?.layout)

        assertEquals(setOf(ManeuverModifier.STRAIGHT), layout.lanes[0].directions)
        assertEquals(setOf(ManeuverModifier.RIGHT), layout.lanes[1].directions)
    }
}
