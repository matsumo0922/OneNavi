package me.matsumo.onenavi.core.navigation.voice.selector

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import me.matsumo.onenavi.core.navigation.voice.config.VoiceAnnouncementConfig
import me.matsumo.onenavi.core.navigation.voice.plan.AnnouncementStage
import me.matsumo.onenavi.core.navigation.voice.plan.AnnouncementStageKind
import me.matsumo.onenavi.core.navigation.voice.plan.AnnouncementTarget
import me.matsumo.onenavi.core.navigation.voice.plan.VoiceAnnouncementId
import me.matsumo.onenavi.core.navigation.voice.plan.VoiceAnnouncementPlan
import me.matsumo.onenavi.core.navigation.voice.suppression.VoiceAnnouncementSpeechState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [VoiceAnnouncementSelector] の越え判定 / 到達リードタイム判定 / 緊急度選択 / 抑止のテスト。
 */
class VoiceAnnouncementSelectorTest {

    @Test
    fun `中間段は前 tick から現 tick の区間でトリガ距離を跨いだ tick だけ発話する`() {
        val selector = VoiceAnnouncementSelector(VoiceAnnouncementConfig())
        val plan = planOf(
            targetOf(
                index = 0,
                geometryMeters = 1_000.0,
                stages = listOf(middleStage("m800", triggerGeometryMeters = 800.0)),
            ),
        )

        val crossing = selector.select(plan, tickOf(previous = 700.0, current = 850.0), emptyState())
        val afterCrossing = selector.select(plan, tickOf(previous = 850.0, current = 900.0), emptyState())

        assertEquals(VoiceAnnouncementId("m800"), crossing?.stage?.id)
        assertNull(afterCrossing)
    }

    @Test
    fun `直前段は速度から逆算した手前距離に達した tick で発話する`() {
        val selector = VoiceAnnouncementSelector(VoiceAnnouncementConfig())
        // leadTime 3s × 20m/s = 60m 手前。target 1000m なので fire 境界は 940m。
        val plan = planOf(
            targetOf(
                index = 0,
                geometryMeters = 1_000.0,
                stages = listOf(finalStage("f")),
            ),
        )

        val beforeBoundary = selector.select(plan, tickOf(previous = 900.0, current = 930.0, speed = 20.0), emptyState())
        val atBoundary = selector.select(plan, tickOf(previous = 930.0, current = 950.0, speed = 20.0), emptyState())

        assertNull(beforeBoundary)
        assertEquals(VoiceAnnouncementId("f"), atBoundary?.stage?.id)
        assertEquals(AnnouncementStageKind.FINAL, atBoundary?.stage?.kind)
    }

    @Test
    fun `速度が無い直前段は最小手前距離で発話する`() {
        val selector = VoiceAnnouncementSelector(VoiceAnnouncementConfig())
        // 速度なし → minLead 30m 手前。target 1000m なので fire 境界は 970m。
        val plan = planOf(
            targetOf(
                index = 0,
                geometryMeters = 1_000.0,
                stages = listOf(finalStage("f")),
            ),
        )

        val beforeBoundary = selector.select(plan, tickOf(previous = 950.0, current = 965.0, speed = null), emptyState())
        val atBoundary = selector.select(plan, tickOf(previous = 965.0, current = 975.0, speed = null), emptyState())

        assertNull(beforeBoundary)
        assertEquals(VoiceAnnouncementId("f"), atBoundary?.stage?.id)
    }

    @Test
    fun `同一 target で複数の中間段を同時に跨いだら最も手前の段を選ぶ`() {
        val selector = VoiceAnnouncementSelector(VoiceAnnouncementConfig())
        // stages は遠い順 (triggerSource 昇順) で並ぶ前提。2km と 1km 手前を同 tick で跨ぐ。
        val plan = planOf(
            targetOf(
                index = 0,
                geometryMeters = 5_000.0,
                stages = listOf(
                    middleStage("m3000", triggerGeometryMeters = 3_000.0),
                    middleStage("m4000", triggerGeometryMeters = 4_000.0),
                ),
            ),
        )

        val selection = selector.select(plan, tickOf(previous = 2_500.0, current = 4_200.0), emptyState())

        // 両方を越えているが、より手前 (1km 手前 = triggerGeometry 大) の段を採る。
        assertEquals(VoiceAnnouncementId("m4000"), selection?.stage?.id)
    }

    @Test
    fun `同時に複数 target が鳴りたい tick では最も近い案内地点が選ばれる`() {
        val selector = VoiceAnnouncementSelector(VoiceAnnouncementConfig())
        val plan = planOf(
            targetOf(
                index = 0,
                geometryMeters = 500.0,
                stages = listOf(middleStage("near", triggerGeometryMeters = 400.0)),
            ),
            targetOf(
                index = 1,
                geometryMeters = 1_000.0,
                stages = listOf(middleStage("far", triggerGeometryMeters = 400.0)),
            ),
        )

        val selection = selector.select(plan, tickOf(previous = 350.0, current = 450.0), emptyState())

        assertEquals(0, selection?.targetIndex)
        assertEquals(VoiceAnnouncementId("near"), selection?.stage?.id)
    }

    @Test
    fun `route が発話不能なら何も選ばない`() {
        val selector = VoiceAnnouncementSelector(VoiceAnnouncementConfig())
        val plan = planOf(
            targetOf(
                index = 0,
                geometryMeters = 1_000.0,
                stages = listOf(middleStage("m800", triggerGeometryMeters = 800.0)),
            ),
        )

        val selection = selector.select(
            plan = plan,
            tick = tickOf(previous = 700.0, current = 850.0, isRouteUsable = false),
            state = emptyState(),
        )

        assertNull(selection)
    }

    @Test
    fun `通過済みとして記録された案内地点は発話しない`() {
        val selector = VoiceAnnouncementSelector(VoiceAnnouncementConfig())
        val plan = planOf(
            targetOf(
                index = 0,
                geometryMeters = 1_000.0,
                stages = listOf(middleStage("m800", triggerGeometryMeters = 800.0)),
            ),
        )
        val passedState = emptyState().withTargetPassed(0)

        val selection = selector.select(plan, tickOf(previous = 700.0, current = 850.0), passedState)

        assertNull(selection)
    }

    @Test
    fun `既発話の段は再び発話しない`() {
        val selector = VoiceAnnouncementSelector(VoiceAnnouncementConfig())
        val plan = planOf(
            targetOf(
                index = 0,
                geometryMeters = 1_000.0,
                stages = listOf(middleStage("m800", triggerGeometryMeters = 800.0)),
            ),
        )
        val firedState = emptyState().withStageFired(VoiceAnnouncementId("m800"))

        val selection = selector.select(plan, tickOf(previous = 700.0, current = 850.0), firedState)

        assertNull(selection)
    }

    @Test
    fun `案内地点を跨いだ tick はその index を通過済みとして返す`() {
        val selector = VoiceAnnouncementSelector(VoiceAnnouncementConfig())
        val plan = planOf(
            targetOf(index = 0, geometryMeters = 500.0, stages = listOf(finalStage("f0"))),
            targetOf(index = 1, geometryMeters = 1_500.0, stages = listOf(finalStage("f1"))),
        )

        val justPassed = selector.passedTargetIndices(plan, tickOf(previous = 480.0, current = 520.0))
        val notYet = selector.passedTargetIndices(plan, tickOf(previous = 520.0, current = 560.0))

        assertEquals(listOf(0), justPassed)
        assertTrue(notYet.isEmpty())
    }

    @Test
    fun `route が発話不能な tick では通過済みを記録しない`() {
        val selector = VoiceAnnouncementSelector(VoiceAnnouncementConfig())
        val plan = planOf(
            targetOf(index = 0, geometryMeters = 500.0, stages = listOf(finalStage("f0"))),
        )

        // 投影距離は GP を跨いでいるが OFF_ROUTE 等で発話不能 → 通過済みにしない。
        val passed = selector.passedTargetIndices(
            plan = plan,
            tick = tickOf(previous = 480.0, current = 520.0, isRouteUsable = false),
        )

        assertTrue(passed.isEmpty())
    }

    private fun planOf(vararg targets: AnnouncementTarget): VoiceAnnouncementPlan =
        VoiceAnnouncementPlan(
            routeId = "R",
            targets = targets.toList().toImmutableList(),
        )

    private fun targetOf(
        index: Int,
        geometryMeters: Double,
        stages: List<AnnouncementStage>,
    ): AnnouncementTarget = AnnouncementTarget(
        guidancePointIndex = index,
        geometryMeters = geometryMeters,
        stages = stages.toImmutableList(),
    )

    private fun middleStage(id: String, triggerGeometryMeters: Double): AnnouncementStage =
        stageOf(id, AnnouncementStageKind.MIDDLE, triggerGeometryMeters)

    private fun finalStage(id: String): AnnouncementStage =
        stageOf(id, AnnouncementStageKind.FINAL, triggerGeometryMeters = 0.0)

    private fun stageOf(
        id: String,
        kind: AnnouncementStageKind,
        triggerGeometryMeters: Double,
    ): AnnouncementStage = AnnouncementStage(
        id = VoiceAnnouncementId(id),
        kind = kind,
        triggerSourceMeters = triggerGeometryMeters,
        triggerGeometryMeters = triggerGeometryMeters,
        pieces = persistentListOf(),
        categories = persistentSetOf(),
    )

    private fun tickOf(
        previous: Double,
        current: Double,
        speed: Double? = null,
        isRouteUsable: Boolean = true,
    ): VoiceTick = VoiceTick(
        previousCumulativeMeters = previous,
        currentCumulativeMeters = current,
        speedMetersPerSecond = speed,
        isRouteUsable = isRouteUsable,
    )

    private fun emptyState(): VoiceAnnouncementSpeechState = VoiceAnnouncementSpeechState()
}
