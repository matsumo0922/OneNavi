package me.matsumo.onenavi.core.navigation.voice.scheduler

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import me.matsumo.drive.supporter.api.guidance.domain.GuidanceCategory
import me.matsumo.drive.supporter.api.guidance.domain.GuideAnnouncementPiece
import me.matsumo.onenavi.core.navigation.voice.config.VoiceAnnouncementCategoryGate
import me.matsumo.onenavi.core.navigation.voice.config.VoiceAnnouncementConfig
import me.matsumo.onenavi.core.navigation.voice.dispatch.VoiceAnnouncementContentRenderer
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
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * [VoiceAnnouncementScheduler] の状態遷移と発話実行指示 (PLAY / BARGE_IN / ENQUEUE / キュー消化) のテスト。
 */
class VoiceAnnouncementSchedulerTest {

    @Test
    fun `発話中が無ければ最緊急候補で発話を開始する`() {
        val scheduler = schedulerOf()
        scheduler.attach(planOf(targetOf(index = 0, geometryMeters = 1_000.0, middleStage("m800", 800.0))))

        val command = scheduler.onTick(tickOf(current = 850.0))

        val start = assertIs<VoiceAnnouncementCommand.StartSpeaking>(command)
        assertEquals(VoiceAnnouncementId("m800"), start.request.stageId)
    }

    @Test
    fun `発話中に積んだ非緊急候補は発話完了後にキューから消化される`() {
        val scheduler = schedulerOf()
        scheduler.attach(
            planOf(
                targetOf(index = 0, geometryMeters = 500.0, middleStage("near", 400.0)),
                targetOf(index = 1, geometryMeters = 1_500.0, middleStage("far", 400.0)),
            ),
        )

        // near の方が緊急なので先に発話開始。
        val first = scheduler.onTick(tickOf(current = 450.0))
        // 発話中に far が trigger するが near より非緊急 → キューへ積むだけ (指示なし)。
        val whileSpeaking = scheduler.onTick(tickOf(current = 460.0))
        // near の発話完了でキューの far を消化する。
        val drained = scheduler.onSpeechFinished(VoiceAnnouncementId("near"))

        assertEquals(VoiceAnnouncementId("near"), assertIs<VoiceAnnouncementCommand.StartSpeaking>(first).request.stageId)
        assertNull(whileSpeaking)
        assertEquals(VoiceAnnouncementId("far"), assertIs<VoiceAnnouncementCommand.StartSpeaking>(drained).request.stageId)
    }

    @Test
    fun `発話中より緊急な直前段は中断して割り込む`() {
        val scheduler = schedulerOf()
        scheduler.attach(
            planOf(
                targetOf(index = 0, geometryMeters = 760.0, finalStage("nearFinal")),
                targetOf(index = 1, geometryMeters = 2_000.0, middleStage("farMiddle", 700.0)),
            ),
        )

        // far の中間段を先に発話開始 (near の FINAL はまだ手前距離に達していない)。
        val first = scheduler.onTick(tickOf(current = 700.0))
        // near の FINAL が手前距離に達し、発話中の far より緊急 → 中断+割り込み。
        val bargeIn = scheduler.onTick(tickOf(current = 735.0))

        assertEquals(
            VoiceAnnouncementId("farMiddle"),
            assertIs<VoiceAnnouncementCommand.StartSpeaking>(first).request.stageId,
        )
        assertEquals(
            VoiceAnnouncementId("nearFinal"),
            assertIs<VoiceAnnouncementCommand.InterruptAndSpeak>(bargeIn).request.stageId,
        )
    }

    @Test
    fun `キュー消化時に通過済みになった案内地点の発話は抑止する`() {
        val scheduler = schedulerOf()
        scheduler.attach(
            planOf(
                targetOf(index = 0, geometryMeters = 500.0, middleStage("near", 400.0)),
                targetOf(index = 1, geometryMeters = 1_000.0, middleStage("far", 400.0)),
            ),
        )

        scheduler.onTick(tickOf(current = 450.0)) // near を発話開始
        scheduler.onTick(tickOf(current = 460.0)) // far をキューへ
        // 両 target を通過してから near の発話完了。far は通過済みなのでキュー消化で抑止。
        scheduler.onTick(tickOf(current = 1_100.0))
        val drained = scheduler.onSpeechFinished(VoiceAnnouncementId("near"))

        assertNull(drained)
    }

    @Test
    fun `発話内容が空の段は発話を起こさず畳み 同 tick の別候補を次 tick で拾う`() {
        val gate = VoiceAnnouncementCategoryGate.of(GuidanceCategory.Curve to false)
        val scheduler = schedulerOf(gate)
        scheduler.attach(
            planOf(
                // 最緊急だが OFF category のみ → 発話内容が空。
                targetOf(index = 0, geometryMeters = 500.0, middleStage("curve", 400.0, GuidanceCategory.Curve)),
                targetOf(index = 1, geometryMeters = 600.0, middleStage("guide", 400.0, GuidanceCategory.IntersectionGuide)),
            ),
        )

        // curve が最緊急で選ばれるが内容が空 → 指示なしで畳む。guide はこの tick では選ばれない。
        val emptyTick = scheduler.onTick(tickOf(current = 450.0))
        // 次 tick では畳まれた curve が外れ、未処理の guide が拾われる。
        val nextTick = scheduler.onTick(tickOf(current = 460.0))

        assertNull(emptyTick)
        assertEquals(VoiceAnnouncementId("guide"), assertIs<VoiceAnnouncementCommand.StartSpeaking>(nextTick).request.stageId)
    }

    @Test
    fun `detach 後の tick は何も発話しない`() {
        val scheduler = schedulerOf()
        scheduler.attach(planOf(targetOf(index = 0, geometryMeters = 1_000.0, middleStage("m800", 800.0))))
        scheduler.detach()

        val command = scheduler.onTick(tickOf(current = 850.0))

        assertNull(command)
    }

    @Test
    fun `route が発話不能な tick は何も発話しない`() {
        val scheduler = schedulerOf()
        scheduler.attach(planOf(targetOf(index = 0, geometryMeters = 1_000.0, middleStage("m800", 800.0))))

        val command = scheduler.onTick(tickOf(current = 850.0, isRouteUsable = false))

        assertNull(command)
    }

    private fun schedulerOf(
        gate: VoiceAnnouncementCategoryGate = VoiceAnnouncementCategoryGate.AllOn,
    ): VoiceAnnouncementScheduler = VoiceAnnouncementScheduler(
        selector = VoiceAnnouncementSelector(VoiceAnnouncementConfig()),
        policy = VoiceAnnouncementSelectionPolicy(),
        contentRenderer = VoiceAnnouncementContentRenderer(gate),
    )

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

    private fun middleStage(
        id: String,
        triggerGeometryMeters: Double,
        category: GuidanceCategory = GuidanceCategory.IntersectionGuide,
    ): AnnouncementStage = stageOf(id, AnnouncementStageKind.MIDDLE, triggerGeometryMeters, category)

    private fun finalStage(
        id: String,
        category: GuidanceCategory = GuidanceCategory.IntersectionGuide,
    ): AnnouncementStage = stageOf(id, AnnouncementStageKind.FINAL, triggerGeometryMeters = 0.0, category)

    private fun stageOf(
        id: String,
        kind: AnnouncementStageKind,
        triggerGeometryMeters: Double,
        category: GuidanceCategory,
    ): AnnouncementStage = AnnouncementStage(
        id = VoiceAnnouncementId(id),
        kind = kind,
        triggerSourceMeters = triggerGeometryMeters,
        triggerGeometryMeters = triggerGeometryMeters,
        pieces = persistentListOf(
            GuideAnnouncementPiece(text = id, ssml = null, templateRef = null, category = category),
        ),
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
}
