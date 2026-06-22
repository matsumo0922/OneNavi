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
import me.matsumo.onenavi.core.navigation.tts.MilestoneAnnouncementProvider
import me.matsumo.onenavi.core.navigation.tts.OpeningAnnouncementProvider
import me.matsumo.onenavi.core.navigation.voice.config.VoiceAnnouncementCategoryGate
import me.matsumo.onenavi.core.navigation.voice.config.VoiceAnnouncementConfig
import me.matsumo.onenavi.core.navigation.voice.dispatch.VoiceAnnouncementContent
import me.matsumo.onenavi.core.navigation.voice.dispatch.VoiceAnnouncementContentRenderer
import me.matsumo.onenavi.core.navigation.voice.dispatch.VoiceAnnouncementDispatcher
import me.matsumo.onenavi.core.navigation.voice.plan.AnnouncementDistanceWindow
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

        assertEquals(listOf(spokenSsml("m800")), dispatcher.spoken)
    }

    @Test
    fun `debug snapshot request は tick 処理後の発話予定を返す`() = runTest {
        val dispatcher = GatingDispatcher()
        val runner = runnerOf(dispatcher, this)
        var debugSnapshot: me.matsumo.onenavi.core.navigation.voice.debug.VoiceAnnouncementDebugSnapshot? = null
        runner.attach(planOf(targetOf(index = 0, geometryMeters = 1_000.0, middleStage("m500", 500.0))))

        runner.submit(tickOf(current = 300.0))
        val isRequested = runner.requestDebugSnapshot(
            fetchStateProvider = {
                me.matsumo.onenavi.core.navigation.voice.debug.VoiceAnnouncementDebugFetchState.IN_FLIGHT
            },
            receiver = { snapshot -> debugSnapshot = snapshot },
        )
        advanceUntilIdle()
        runner.detach()

        kotlin.test.assertTrue(isRequested)
        val item = requireNotNull(debugSnapshot).upcomingAnnouncements.single()
        assertEquals("m500", item.stageId)
        assertEquals(200.0, item.remainingMeters)
        assertEquals(
            me.matsumo.onenavi.core.navigation.voice.debug.VoiceAnnouncementDebugFetchState.IN_FLIGHT,
            item.fetchState,
        )
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

        assertEquals(listOf(spokenSsml("near"), spokenSsml("far")), dispatcher.spoken)
    }

    @Test
    fun `barge-in は進行中の発話を中断して割り込む`() = runTest {
        val dispatcher = GatingDispatcher()
        val runner = runnerOf(dispatcher, this)
        runner.attach(
            planOf(
                targetOf(
                    index = 0,
                    geometryMeters = 1_000.0,
                    middleStage("farMiddle", triggerGeometryMeters = 100.0),
                    finalStage("nearFinal", triggerGeometryMeters = 900.0),
                ),
            ),
        )

        runner.submit(tickOf(current = 150.0)) // farMiddle を発話開始 (gate で保留)
        advanceUntilIdle()
        runner.submit(tickOf(current = 975.0)) // nearFinal が割り込み、farMiddle を中断
        advanceUntilIdle()
        runner.detach()

        assertEquals(listOf(spokenSsml("farMiddle"), spokenSsml("nearFinal")), dispatcher.spoken)
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

    @Test
    fun `発話が例外で失敗しても speaking が詰まらず次の発話を再生する`() = runTest {
        val dispatcher = FailFirstDispatcher()
        val runner = runnerOf(dispatcher, this)
        runner.attach(
            planOf(
                targetOf(index = 0, geometryMeters = 500.0, middleStage("a", 400.0)),
                targetOf(index = 1, geometryMeters = 1_500.0, middleStage("b", 1_000.0)),
            ),
        )

        runner.submit(tickOf(current = 450.0)) // a を発話開始 → speak が例外
        advanceUntilIdle()
        runner.submit(tickOf(current = 1_100.0)) // 詰まっていなければ b を発話できる
        advanceUntilIdle()
        runner.detach()

        // a は失敗 (記録されない) が state は解除され、b が再生される。
        assertEquals(listOf(spokenSsml("b")), dispatcher.spoken)
    }

    @Test
    fun `announceOpening 時は開始アナウンスを案内発話より先に再生し、その完了まで tick を保留する`() = runTest {
        val dispatcher = GatingDispatcher()
        val runner = runnerOf(dispatcher, this)
        runner.attach(
            planOf(targetOf(index = 0, geometryMeters = 1_000.0, middleStage("m800", 800.0))),
            announceOpening = true,
        )

        advanceUntilIdle()
        assertEquals(listOf(OPENING_SSML), dispatcher.spoken) // 開始アナウンスのみ再生中

        runner.submit(tickOf(current = 850.0)) // アナウンス中の tick は保留される
        advanceUntilIdle()
        assertEquals(listOf(OPENING_SSML), dispatcher.spoken)

        dispatcher.releaseNext() // 開始アナウンス完了 → 保留した最新 tick を再投入せず自動で再評価
        advanceUntilIdle()
        runner.detach()

        assertEquals(listOf(OPENING_SSML, spokenSsml("m800")), dispatcher.spoken)
    }

    @Test
    fun `設定の初回読了まで開始アナウンスと案内発話を保留する`() = runTest {
        val dispatcher = GatingDispatcher()
        val settingsGate = CompletableDeferred<Unit>()
        val runner = runnerOf(
            dispatcher = dispatcher,
            scope = this,
            awaitSettingsReady = { settingsGate.await() },
        )
        runner.attach(
            planOf(targetOf(index = 0, geometryMeters = 1_000.0, middleStage("m800", 800.0))),
            announceOpening = true,
        )

        runner.submit(tickOf(current = 850.0)) // 読了前の tick は channel に溜まる
        advanceUntilIdle()
        assertEquals(emptyList(), dispatcher.spoken) // 読了まで一切発話しない

        settingsGate.complete(Unit) // 初回読了 → 開始アナウンスから順に再生
        advanceUntilIdle()
        assertEquals(listOf(OPENING_SSML), dispatcher.spoken)

        dispatcher.releaseNext() // 開始アナウンス完了 → 保留していた tick を再評価
        advanceUntilIdle()
        runner.detach()

        assertEquals(listOf(OPENING_SSML, spokenSsml("m800")), dispatcher.spoken)
    }

    @Test
    fun `経由地通過アナウンスは進行中の案内発話へ割り込み、完了後に案内発話を再開する`() = runTest {
        val dispatcher = GatingDispatcher()
        val runner = runnerOf(dispatcher, this)
        runner.attach(
            planOf(
                targetOf(index = 0, geometryMeters = 1_000.0, middleStage("m800", 800.0)),
                targetOf(index = 1, geometryMeters = 2_000.0, middleStage("m1800", 1_800.0)),
            ),
        )

        runner.submit(tickOf(current = 850.0)) // m800 を発話開始 (gate で保留)
        advanceUntilIdle()
        assertEquals(listOf(spokenSsml("m800")), dispatcher.spoken)

        runner.announceWaypointApproach() // 割り込み → m800 を中断し経由地通過を発話
        advanceUntilIdle()
        assertEquals(listOf(spokenSsml("m800"), WAYPOINT_SSML), dispatcher.spoken)

        runner.submit(tickOf(current = 1_850.0)) // アナウンス中に進んだ tick は保留される
        advanceUntilIdle()
        assertEquals(listOf(spokenSsml("m800"), WAYPOINT_SSML), dispatcher.spoken)

        dispatcher.releaseNext() // 経由地通過アナウンス完了 → 保留した最新 tick を再投入せず自動で再評価
        advanceUntilIdle()
        runner.detach()

        assertEquals(listOf(spokenSsml("m800"), WAYPOINT_SSML, spokenSsml("m1800")), dispatcher.spoken)
    }

    @Test
    fun `目的地到達アナウンスは案内発話へ割り込み、detach されても鳴り切る`() = runTest {
        val dispatcher = GatingDispatcher()
        val runner = runnerOf(dispatcher, this)
        runner.attach(planOf(targetOf(index = 0, geometryMeters = 1_000.0, middleStage("m800", 800.0))))

        runner.submit(tickOf(current = 850.0)) // m800 を発話開始 (gate で保留)
        advanceUntilIdle()
        assertEquals(listOf(spokenSsml("m800")), dispatcher.spoken)

        runner.announceDestinationReached() // 進行中発話へ割り込み、session 非依存で再生
        runner.detach() // 案内 session 終了。目的地到達アナウンスは止めない
        advanceUntilIdle()
        assertEquals(listOf(spokenSsml("m800"), DESTINATION_SSML), dispatcher.spoken)

        dispatcher.releaseNext() // 後始末: 目的地到達アナウンス完了
        advanceUntilIdle()
    }

    private fun runnerOf(
        dispatcher: VoiceAnnouncementDispatcher,
        scope: CoroutineScope,
        awaitSettingsReady: suspend () -> Unit = {},
    ): VoiceAnnouncementSpeechRunner = VoiceAnnouncementSpeechRunner(
        scheduler = VoiceAnnouncementScheduler(
            selector = VoiceAnnouncementSelector(VoiceAnnouncementConfig()),
            policy = VoiceAnnouncementSelectionPolicy(),
            contentRenderer = VoiceAnnouncementContentRenderer(VoiceAnnouncementCategoryGate.AllOn),
            config = VoiceAnnouncementConfig(),
        ),
        dispatcher = dispatcher,
        openingAnnouncementProvider = OpeningAnnouncementProvider {
            VoiceAnnouncementContent(ssml = OPENING_SSML, cue = null)
        },
        milestoneAnnouncementProvider = FakeMilestoneAnnouncementProvider,
        awaitSettingsReady = awaitSettingsReady,
        scope = scope,
    )

    /** 経由地通過 / 目的地到達のマイルストーン発話を固定 SSML で返すテスト用 provider。 */
    private object FakeMilestoneAnnouncementProvider : MilestoneAnnouncementProvider {

        override suspend fun waypointApproach(): VoiceAnnouncementContent =
            VoiceAnnouncementContent(ssml = WAYPOINT_SSML, cue = null)

        override suspend fun destinationReached(): VoiceAnnouncementContent =
            VoiceAnnouncementContent(ssml = DESTINATION_SSML, cue = null)
    }

    /**
     * 発話を CompletableDeferred で保留できる dispatcher。順番に [releaseNext] で完了させる。
     */
    private class GatingDispatcher : VoiceAnnouncementDispatcher {

        val spoken = mutableListOf<String>()
        private val gates = ArrayDeque<CompletableDeferred<Unit>>()

        override suspend fun speak(content: VoiceAnnouncementContent) {
            spoken += requireNotNull(content.ssml)
            val gate = CompletableDeferred<Unit>()
            gates.addLast(gate)
            try {
                gate.await()
            } finally {
                gates.remove(gate)
            }
        }

        fun releaseNext() {
            gates.removeFirst().complete(Unit)
        }
    }

    /**
     * 最初の発話だけ例外を投げ、以降は記録する dispatcher。発話失敗後に state が解除されるかの検証用。
     */
    private class FailFirstDispatcher : VoiceAnnouncementDispatcher {

        val spoken = mutableListOf<String>()
        private var shouldFail = true

        override suspend fun speak(content: VoiceAnnouncementContent) {
            if (shouldFail) {
                shouldFail = false
                error("speak failed")
            }
            spoken += requireNotNull(content.ssml)
        }
    }

    // 各 stage は単一 piece (text=id, ssml なし) なので、render 後は <speak> で囲んだ SSML になる。
    private fun spokenSsml(id: String): String = "<speak>$id</speak>"

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

    private fun finalStage(id: String, triggerGeometryMeters: Double = 0.0): AnnouncementStage =
        stageOf(id, AnnouncementStageKind.FINAL, triggerGeometryMeters)

    private fun stageOf(
        id: String,
        kind: AnnouncementStageKind,
        triggerGeometryMeters: Double,
    ): AnnouncementStage = AnnouncementStage(
        id = VoiceAnnouncementId(id),
        groupKey = VoiceAnnouncementId("$id-grp"),
        kind = kind,
        triggerSourceMeters = triggerGeometryMeters,
        triggerGeometryMeters = triggerGeometryMeters,
        middleWindow = middleWindowFor(kind, triggerGeometryMeters),
        isGeneric = false,
        pieces = persistentListOf(
            GuideAnnouncementPiece(text = id, ssml = null, templateRef = null, category = GuidanceCategory.IntersectionGuide),
        ),
        categories = persistentSetOf(),
    )

    // coroutine 配線を検証するテストなので、窓上限は実質無制限にして「トリガ到達後は候補」とする。
    private fun middleWindowFor(kind: AnnouncementStageKind, triggerGeometryMeters: Double): AnnouncementDistanceWindow? =
        if (kind == AnnouncementStageKind.MIDDLE) {
            AnnouncementDistanceWindow(enterGeometryMeters = triggerGeometryMeters, exitGeometryMeters = Double.MAX_VALUE)
        } else {
            null
        }

    private fun tickOf(current: Double): VoiceTick = VoiceTick(
        currentCumulativeMeters = current,
        speedMetersPerSecond = null,
        canAnnounce = true,
        canCommitPassedTargets = true,
    )

    private companion object {

        /** テスト用の開始アナウンス SSML。 */
        const val OPENING_SSML = "<speak>opening</speak>"

        /** テスト用の経由地通過アナウンス SSML。 */
        const val WAYPOINT_SSML = "<speak>waypoint</speak>"

        /** テスト用の目的地到達アナウンス SSML。 */
        const val DESTINATION_SSML = "<speak>destination</speak>"
    }
}
