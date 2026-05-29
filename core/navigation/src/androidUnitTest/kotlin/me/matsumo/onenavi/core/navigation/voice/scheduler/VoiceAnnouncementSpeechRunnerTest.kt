package me.matsumo.onenavi.core.navigation.voice.scheduler

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import me.matsumo.drive.supporter.api.guidance.domain.GuidanceCategory
import me.matsumo.drive.supporter.api.guidance.domain.GuideAnnouncementPiece
import me.matsumo.onenavi.core.navigation.voice.config.VoiceAnnouncementCategoryGate
import me.matsumo.onenavi.core.navigation.voice.config.VoiceAnnouncementConfig
import me.matsumo.onenavi.core.navigation.voice.dispatch.VoiceAnnouncementContent
import me.matsumo.onenavi.core.navigation.voice.dispatch.VoiceAnnouncementContentRenderer
import me.matsumo.onenavi.core.navigation.voice.dispatch.VoiceAnnouncementDispatcher
import me.matsumo.onenavi.core.navigation.voice.plan.AnnouncementStage
import me.matsumo.onenavi.core.navigation.voice.plan.AnnouncementStageKind
import me.matsumo.onenavi.core.navigation.voice.plan.AnnouncementTarget
import me.matsumo.onenavi.core.navigation.voice.plan.VoiceAnnouncementId
import me.matsumo.onenavi.core.navigation.voice.plan.VoiceAnnouncementPlan
import me.matsumo.onenavi.core.navigation.voice.selector.VoiceAnnouncementSelector
import me.matsumo.onenavi.core.navigation.voice.selector.VoiceTick
import me.matsumo.onenavi.core.navigation.voice.suppression.VoiceAnnouncementSelectionPolicy
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [VoiceAnnouncementSpeechRunner] の coroutine 配線 (発話再生 / キュー消化 / barge-in 中断 / detach) のテスト。
 */
class VoiceAnnouncementSpeechRunnerTest {

    @Test
    fun `tick で選ばれた発話を dispatcher で再生する`() = runTest {
        val dispatcher = GatingDispatcher()
        val runner = runnerOf(dispatcher, this)
        runner.attach(planOf(targetOf(index = 0, geometryMeters = 1_000.0, middleStage("m800", 800.0))))

        runner.submit(tickOf(current = 850.0))
        advanceUntilIdle()
        runner.detach()

        assertEquals(listOf("m800"), dispatcher.spoken)
    }

    @Test
    fun `発話中に積まれた候補を発話完了後に再生する`() = runTest {
        val dispatcher = GatingDispatcher()
        val runner = runnerOf(dispatcher, this)
        runner.attach(
            planOf(
                targetOf(index = 0, geometryMeters = 500.0, middleStage("near", 400.0)),
                targetOf(index = 1, geometryMeters = 1_500.0, middleStage("far", 400.0)),
            ),
        )

        runner.submit(tickOf(current = 450.0)) // near を発話開始 (gate で保留)
        advanceUntilIdle()
        runner.submit(tickOf(current = 460.0)) // far はキューへ
        advanceUntilIdle()
        dispatcher.releaseNext() // near の発話完了 → far を消化
        advanceUntilIdle()
        runner.detach()

        assertEquals(listOf("near", "far"), dispatcher.spoken)
    }

    @Test
    fun `barge-in は進行中の発話を中断して割り込む`() = runTest {
        val dispatcher = GatingDispatcher()
        val runner = runnerOf(dispatcher, this)
        runner.attach(
            planOf(
                targetOf(index = 0, geometryMeters = 760.0, finalStage("nearFinal")),
                targetOf(index = 1, geometryMeters = 2_000.0, middleStage("farMiddle", 700.0)),
            ),
        )

        runner.submit(tickOf(current = 700.0)) // farMiddle を発話開始 (gate で保留)
        advanceUntilIdle()
        runner.submit(tickOf(current = 735.0)) // nearFinal が割り込み、farMiddle を中断
        advanceUntilIdle()
        runner.detach()

        assertEquals(listOf("farMiddle", "nearFinal"), dispatcher.spoken)
    }

    @Test
    fun `detach 後の tick は再生しない`() = runTest {
        val dispatcher = GatingDispatcher()
        val runner = runnerOf(dispatcher, this)
        runner.attach(planOf(targetOf(index = 0, geometryMeters = 1_000.0, middleStage("m800", 800.0))))

        runner.detach()
        runner.submit(tickOf(current = 850.0))
        advanceUntilIdle()

        assertEquals(emptyList(), dispatcher.spoken)
    }

    private fun runnerOf(
        dispatcher: VoiceAnnouncementDispatcher,
        scope: CoroutineScope,
    ): VoiceAnnouncementSpeechRunner = VoiceAnnouncementSpeechRunner(
        scheduler = VoiceAnnouncementScheduler(
            selector = VoiceAnnouncementSelector(VoiceAnnouncementConfig()),
            policy = VoiceAnnouncementSelectionPolicy(),
            contentRenderer = VoiceAnnouncementContentRenderer(VoiceAnnouncementCategoryGate.AllOn),
        ),
        dispatcher = dispatcher,
        scope = scope,
    )

    /**
     * 発話を CompletableDeferred で保留できる dispatcher。順番に [releaseNext] で完了させる。
     */
    private class GatingDispatcher : VoiceAnnouncementDispatcher {

        val spoken = mutableListOf<String>()
        private val gates = ArrayDeque<CompletableDeferred<Unit>>()

        override suspend fun speak(content: VoiceAnnouncementContent) {
            spoken += content.text
            val gate = CompletableDeferred<Unit>()
            gates.addLast(gate)
            gate.await()
        }

        fun releaseNext() {
            gates.removeFirst().complete(Unit)
        }
    }

    private fun planOf(vararg targets: AnnouncementTarget): VoiceAnnouncementPlan = VoiceAnnouncementPlan(
        routeId = "R",
        targets = targets.toList().toImmutableList(),
    )

    private fun targetOf(
        index: Int,
        geometryMeters: Double,
        vararg stages: AnnouncementStage,
    ): AnnouncementTarget = AnnouncementTarget(
        guidancePointIndex = index,
        geometryMeters = geometryMeters,
        stages = stages.toList().toImmutableList(),
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
        pieces = persistentListOf(
            GuideAnnouncementPiece(text = id, ssml = null, templateRef = null, category = GuidanceCategory.IntersectionGuide),
        ),
        categories = persistentSetOf(),
    )

    private fun tickOf(current: Double): VoiceTick = VoiceTick(
        currentCumulativeMeters = current,
        speedMetersPerSecond = null,
        isRouteUsable = true,
    )
}
