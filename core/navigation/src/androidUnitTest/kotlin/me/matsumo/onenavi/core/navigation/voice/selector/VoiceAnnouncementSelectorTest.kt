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
 * [VoiceAnnouncementSelector] の到達判定 / 到達リードタイム判定 / 緊急度選択 / 抑止のテスト。
 */
class VoiceAnnouncementSelectorTest {

    @Test
    fun `中間段は到達後 未処理なら再評価され 既処理になると止まる`() {
        val selector = VoiceAnnouncementSelector(VoiceAnnouncementConfig())
        val plan = planOf(
            targetOf(
                index = 0,
                geometryMeters = 1_000.0,
                stages = listOf(middleStage("m800", triggerGeometryMeters = 800.0)),
            ),
        )

        // 到達していなければ出ない。到達後は未処理の間 (level 判定) 選ばれ続ける。
        val beforeReach = selector.select(plan, tickOf(current = 750.0), emptyState())
        val reached = selector.select(plan, tickOf(current = 850.0), emptyState())
        // 既処理マークが付いたら止まる。
        val afterProcessed = selector.select(plan, tickOf(current = 900.0), emptyState().withStageFired(VoiceAnnouncementId("m800")))

        assertNull(beforeReach)
        assertEquals(VoiceAnnouncementId("m800"), reached?.stage?.id)
        assertNull(afterProcessed)
    }

    @Test
    fun `同 tick で選ばれなかった遠い中間段が次 tick で取りこぼされない`() {
        val selector = VoiceAnnouncementSelector(VoiceAnnouncementConfig())
        // 近い A (geo 500, trigger 400) と遠い B (geo 1000, trigger 450)。
        val plan = planOf(
            targetOf(index = 0, geometryMeters = 500.0, stages = listOf(middleStage("near", triggerGeometryMeters = 400.0))),
            targetOf(index = 1, geometryMeters = 1_000.0, stages = listOf(middleStage("far", triggerGeometryMeters = 450.0))),
        )

        // 同 tick で両方のトリガ距離を跨ぐ。最緊急の near が返る。
        val firstTick = selector.select(plan, tickOf(current = 480.0), emptyState())
        // near を処理済みにした次 tick。edge 判定なら far は二度と候補化しないが、level なら拾える。
        val secondTick = selector.select(plan, tickOf(current = 510.0), emptyState().withStageFired(VoiceAnnouncementId("near")))

        assertEquals(VoiceAnnouncementId("near"), firstTick?.stage?.id)
        assertEquals(VoiceAnnouncementId("far"), secondTick?.stage?.id)
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

        val beforeBoundary = selector.select(plan, tickOf(current = 930.0, speed = 20.0), emptyState())
        val atBoundary = selector.select(plan, tickOf(current = 950.0, speed = 20.0), emptyState())

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

        val beforeBoundary = selector.select(plan, tickOf(current = 965.0, speed = null), emptyState())
        val atBoundary = selector.select(plan, tickOf(current = 975.0, speed = null), emptyState())

        assertNull(beforeBoundary)
        assertEquals(VoiceAnnouncementId("f"), atBoundary?.stage?.id)
    }

    @Test
    fun `同一 target で複数の中間段に同時到達したら最も手前の段を選ぶ`() {
        val selector = VoiceAnnouncementSelector(VoiceAnnouncementConfig())
        // stages は遠い順 (triggerSource 昇順) で並ぶ前提。2km と 1km 手前に同 tick で到達。
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

        val selection = selector.select(plan, tickOf(current = 4_200.0), emptyState())

        // 両方に到達しているが、より手前 (1km 手前 = triggerGeometry 大) の段を採る。
        assertEquals(VoiceAnnouncementId("m4000"), selection?.stage?.id)
    }

    @Test
    fun `近い中間段を採った後 追い越された遠い中間段は蒸し返さない`() {
        val selector = VoiceAnnouncementSelector(VoiceAnnouncementConfig())
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

        // 1km 手前 (m4000) を採る。
        val firstTick = selector.select(plan, tickOf(current = 4_200.0), emptyState())
        // m4000 だけ処理済みにした次 tick。追い越された 2km 手前 (m3000) は蒸し返さない。
        val afterNear = selector.select(plan, tickOf(current = 4_300.0), emptyState().withStageFired(VoiceAnnouncementId("m4000")))

        assertEquals(VoiceAnnouncementId("m4000"), firstTick?.stage?.id)
        assertNull(afterNear)
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

        val selection = selector.select(plan, tickOf(current = 450.0), emptyState())

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
            tick = tickOf(current = 850.0, isRouteUsable = false),
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

        val selection = selector.select(plan, tickOf(current = 850.0), passedState)

        assertNull(selection)
    }

    @Test
    fun `既処理の段は再び発話しない`() {
        val selector = VoiceAnnouncementSelector(VoiceAnnouncementConfig())
        val plan = planOf(
            targetOf(
                index = 0,
                geometryMeters = 1_000.0,
                stages = listOf(middleStage("m800", triggerGeometryMeters = 800.0)),
            ),
        )
        val firedState = emptyState().withStageFired(VoiceAnnouncementId("m800"))

        val selection = selector.select(plan, tickOf(current = 850.0), firedState)

        assertNull(selection)
    }

    @Test
    fun `到達済みの案内地点はその index を通過済みとして返す`() {
        val selector = VoiceAnnouncementSelector(VoiceAnnouncementConfig())
        val plan = planOf(
            targetOf(index = 0, geometryMeters = 500.0, stages = listOf(finalStage("f0"))),
            targetOf(index = 1, geometryMeters = 1_500.0, stages = listOf(finalStage("f1"))),
        )

        val passed = selector.passedTargetIndices(plan, tickOf(current = 520.0))
        val notYet = selector.passedTargetIndices(plan, tickOf(current = 480.0))

        assertEquals(listOf(0), passed)
        assertTrue(notYet.isEmpty())
    }

    @Test
    fun `route が発話不能な tick では通過済みを記録しない`() {
        val selector = VoiceAnnouncementSelector(VoiceAnnouncementConfig())
        val plan = planOf(
            targetOf(index = 0, geometryMeters = 500.0, stages = listOf(finalStage("f0"))),
        )

        // 投影距離は GP に到達しているが OFF_ROUTE 等で発話不能 → 通過済みにしない。
        val passed = selector.passedTargetIndices(
            plan = plan,
            tick = tickOf(current = 520.0, isRouteUsable = false),
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
        current: Double,
        speed: Double? = null,
        isRouteUsable: Boolean = true,
    ): VoiceTick = VoiceTick(
        currentCumulativeMeters = current,
        speedMetersPerSecond = speed,
        isRouteUsable = isRouteUsable,
    )

    private fun emptyState(): VoiceAnnouncementSpeechState = VoiceAnnouncementSpeechState()
}
