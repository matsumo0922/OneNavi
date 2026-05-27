package me.matsumo.onenavi.core.navigation.newguidance.presentation

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableSet
import me.matsumo.onenavi.core.model.ManeuverModifier
import me.matsumo.onenavi.core.navigation.newguidance.semantic.GuidanceLane
import me.matsumo.onenavi.core.navigation.newguidance.semantic.LaneConfidence
import me.matsumo.onenavi.core.navigation.newguidance.semantic.LaneDirectionCell
import me.matsumo.onenavi.core.navigation.newguidance.semantic.LaneLayout
import me.matsumo.onenavi.core.navigation.newguidance.semantic.LaneMark
import me.matsumo.onenavi.core.navigation.newguidance.semantic.LaneSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [LanePresentationFactory] の semantic レーン → 視覚レーン整形テスト。
 */
class LanePresentationFactoryTest {

    private val factory = LanePresentationFactory()

    @Test
    fun `車線図はターゲットレーンを強調し各レーンの向きを保持する`() {
        // 左折+直進 / 直進 / 右折(target)
        val lane = directionLane(
            cell(directions = setOf(ManeuverModifier.LEFT, ManeuverModifier.STRAIGHT), isTarget = false),
            cell(directions = setOf(ManeuverModifier.STRAIGHT), isTarget = false),
            cell(directions = setOf(ManeuverModifier.RIGHT), isTarget = true),
        )

        val presentation = factory.create(lane = lane, recommendedDirection = ManeuverModifier.RIGHT)

        val visual = assertIs<LanePresentation.VisualLanes>(presentation)
        assertEquals(3, visual.lanes.size)
        // 各レーンの向きが保持され、左→右の正準順に並ぶ。
        assertEquals(listOf(ManeuverModifier.LEFT, ManeuverModifier.STRAIGHT), visual.lanes[0].allowedDirections)
        // ターゲットレーンだけ active で強調方向を持つ。
        assertTrue(visual.lanes[2].isActive)
        assertEquals(ManeuverModifier.RIGHT, visual.lanes[2].recommendedDirection)
        assertTrue(!visual.lanes[0].isActive)
        assertNull(visual.lanes[0].recommendedDirection)
    }

    @Test
    fun `複数方向のターゲットレーンは maneuver 方向で強調を曖昧解消する`() {
        // 左折+直進 が target。直進ルートなら直進を強調する。
        val lane = directionLane(
            cell(directions = setOf(ManeuverModifier.LEFT, ManeuverModifier.STRAIGHT), isTarget = true),
            cell(directions = setOf(ManeuverModifier.RIGHT), isTarget = false),
        )

        val presentation = factory.create(lane = lane, recommendedDirection = ManeuverModifier.STRAIGHT)

        val visual = assertIs<LanePresentation.VisualLanes>(presentation)
        assertEquals(ManeuverModifier.STRAIGHT, visual.lanes[0].recommendedDirection)
    }

    @Test
    fun `marker layout は従来通り推奨車線を強調する`() {
        val lane = GuidanceLane(
            layout = LaneLayout.MarkerLayout(
                lanes = listOf(LaneMark(rawA = 0, rawB = 0), LaneMark(rawA = 1, rawB = 0)).toImmutableList(),
                kind = 0,
            ),
            instruction = null,
            warning = null,
            sources = persistentSetOf(LaneSource.MARKER),
            confidence = LaneConfidence.MEDIUM,
            sourceRefs = persistentListOf(),
        )

        val presentation = factory.create(lane = lane, recommendedDirection = ManeuverModifier.RIGHT)

        val visual = assertIs<LanePresentation.VisualLanes>(presentation)
        assertTrue(visual.lanes[1].isActive)
        assertTrue(!visual.lanes[0].isActive)
    }

    private fun directionLane(vararg cells: LaneDirectionCell): GuidanceLane = GuidanceLane(
        layout = LaneLayout.DirectionLayout(lanes = cells.toList().toImmutableList()),
        instruction = null,
        warning = null,
        sources = persistentSetOf(LaneSource.LANE_DIAGRAM),
        confidence = LaneConfidence.HIGH,
        sourceRefs = persistentListOf(),
    )

    private fun cell(
        directions: Set<ManeuverModifier>,
        isTarget: Boolean,
    ): LaneDirectionCell = LaneDirectionCell(
        directions = directions.toImmutableSet(),
        isTarget = isTarget,
        isAppend = false,
    )
}
