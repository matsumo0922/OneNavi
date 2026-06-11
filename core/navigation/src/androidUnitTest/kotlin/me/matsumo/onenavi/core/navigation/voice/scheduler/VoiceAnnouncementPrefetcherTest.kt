package me.matsumo.onenavi.core.navigation.voice.scheduler

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import me.matsumo.drive.supporter.api.guidance.domain.GuidanceCategory
import me.matsumo.drive.supporter.api.guidance.domain.GuideAnnouncementPiece
import me.matsumo.onenavi.core.navigation.voice.config.VoiceAnnouncementCategoryGate
import me.matsumo.onenavi.core.navigation.voice.dispatch.VoiceAnnouncementContent
import me.matsumo.onenavi.core.navigation.voice.dispatch.VoiceAnnouncementContentRenderer
import me.matsumo.onenavi.core.navigation.voice.dispatch.VoiceAnnouncementDispatcher
import me.matsumo.onenavi.core.navigation.voice.plan.AnnouncementDistanceWindow
import me.matsumo.onenavi.core.navigation.voice.plan.AnnouncementStage
import me.matsumo.onenavi.core.navigation.voice.plan.AnnouncementStageKind
import me.matsumo.onenavi.core.navigation.voice.plan.AnnouncementTarget
import me.matsumo.onenavi.core.navigation.voice.plan.VoiceAnnouncementId
import me.matsumo.onenavi.core.navigation.voice.plan.VoiceAnnouncementPlan
import me.matsumo.onenavi.core.navigation.voice.selector.VoiceTick
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [VoiceAnnouncementPrefetcher] の近傍抽出と category gate 適用のテスト。
 */
class VoiceAnnouncementPrefetcherTest {

    @Test
    fun `現在地より後ろの target は prefetch しない`() {
        val dispatcher = RecordingDispatcher()
        val prefetcher = prefetcherOf(dispatcher)
        prefetcher.attach(
            planOf(
                targetOf(index = 0, geometryMeters = 3_000.0, stage("far")),
            ),
        )

        prefetcher.onTick(tickOf(current = 3_100.0))

        assertEquals(emptyList(), dispatcher.prefetchedSsml)
    }

    @Test
    fun `現在距離なしの attach では prefetch しない`() {
        val dispatcher = RecordingDispatcher()
        val prefetcher = prefetcherOf(dispatcher)

        prefetcher.attach(
            planOf(
                targetOf(index = 0, geometryMeters = 500.0, stage("a")),
            ),
        )

        assertEquals(emptyList(), dispatcher.prefetchedSsml)
    }

    @Test
    fun `attach 時も現在地より後ろの target は prefetch しない`() {
        val dispatcher = RecordingDispatcher()
        val prefetcher = prefetcherOf(dispatcher)

        prefetcher.attach(
            plan = planOf(
                targetOf(index = 0, geometryMeters = 500.0, stage("behind")),
                targetOf(index = 1, geometryMeters = 1_000.0, stage("ahead")),
            ),
            currentCumulativeMeters = 750.0,
        )

        assertEquals(listOf("<speak>ahead</speak>"), dispatcher.prefetchedSsml)
    }

    @Test
    fun `最大3 target かつ 5km 以内の近傍だけ prefetch する`() {
        val dispatcher = RecordingDispatcher()
        val prefetcher = prefetcherOf(dispatcher)

        prefetcher.attach(
            plan = planOf(
                targetOf(index = 0, geometryMeters = 500.0, stage("a")),
                targetOf(index = 1, geometryMeters = 3_000.0, stage("b")),
                targetOf(index = 2, geometryMeters = 4_500.0, stage("c")),
                targetOf(index = 3, geometryMeters = 5_500.0, stage("d")),
            ),
            currentCumulativeMeters = 0.0,
        )

        assertEquals(
            listOf("<speak>a</speak>", "<speak>b</speak>", "<speak>c</speak>"),
            dispatcher.prefetchedSsml,
        )
    }

    @Test
    fun `category gate 適用後に空の content は prefetch しない`() {
        val dispatcher = RecordingDispatcher()
        val gate = VoiceAnnouncementCategoryGate.of(GuidanceCategory.Curve to false)
        val prefetcher = prefetcherOf(
            dispatcher = dispatcher,
            gate = gate,
        )

        prefetcher.attach(
            plan = planOf(
                targetOf(
                    index = 0,
                    geometryMeters = 500.0,
                    stage = stage("curve", GuidanceCategory.Curve),
                ),
            ),
            currentCumulativeMeters = 0.0,
        )

        assertEquals(emptyList(), dispatcher.prefetchedSsml)
    }

    @Test
    fun `ローカル cue だけの content は prefetch しない`() {
        val dispatcher = RecordingDispatcher()
        val prefetcher = prefetcherOf(dispatcher)

        prefetcher.attach(
            plan = planOf(
                targetOf(index = 0, geometryMeters = 500.0, stage("ポーン")),
            ),
            currentCumulativeMeters = 0.0,
        )

        assertEquals(emptyList(), dispatcher.prefetchedSsml)
    }

    @Test
    fun `detach で pending prefetch を clear し 以後の tick では prefetch しない`() {
        val dispatcher = RecordingDispatcher()
        val prefetcher = prefetcherOf(dispatcher)
        prefetcher.attach(
            plan = planOf(
                targetOf(index = 0, geometryMeters = 500.0, stage("a")),
            ),
            currentCumulativeMeters = 0.0,
        )

        prefetcher.detach()
        prefetcher.onTick(tickOf(current = 100.0))

        assertEquals(listOf("<speak>a</speak>"), dispatcher.prefetchedSsml)
        assertEquals(2, dispatcher.clearCount)
    }

    private fun prefetcherOf(
        dispatcher: RecordingDispatcher,
        gate: VoiceAnnouncementCategoryGate = VoiceAnnouncementCategoryGate.AllOn,
    ): VoiceAnnouncementPrefetcher = VoiceAnnouncementPrefetcher(
        contentRenderer = VoiceAnnouncementContentRenderer(gate),
        dispatcher = dispatcher,
    )

    private fun planOf(vararg targets: AnnouncementTarget): VoiceAnnouncementPlan = VoiceAnnouncementPlan(
        routeId = "R",
        targets = targets.toList().toImmutableList(),
    )

    private fun targetOf(
        index: Int,
        geometryMeters: Double,
        stage: AnnouncementStage,
    ): AnnouncementTarget = AnnouncementTarget(
        guidancePointIndex = index,
        geometryMeters = geometryMeters,
        stages = persistentListOf(stage),
    )

    private fun stage(
        id: String,
        category: GuidanceCategory = GuidanceCategory.IntersectionGuide,
    ): AnnouncementStage = AnnouncementStage(
        id = VoiceAnnouncementId(id),
        groupKey = VoiceAnnouncementId("$id-group"),
        kind = AnnouncementStageKind.MIDDLE,
        triggerSourceMeters = 0.0,
        triggerGeometryMeters = 0.0,
        middleWindow = AnnouncementDistanceWindow(
            enterGeometryMeters = 0.0,
            exitGeometryMeters = Double.MAX_VALUE,
        ),
        isGeneric = false,
        pieces = persistentListOf(
            GuideAnnouncementPiece(
                text = id,
                ssml = null,
                templateRef = null,
                category = category,
            ),
        ),
        categories = persistentSetOf(category),
    )

    private fun tickOf(current: Double): VoiceTick = VoiceTick(
        currentCumulativeMeters = current,
        speedMetersPerSecond = null,
        isRouteUsable = true,
    )

    /**
     * 先読み request を記録する dispatcher。
     */
    private class RecordingDispatcher : VoiceAnnouncementDispatcher {

        val prefetchedSsml = mutableListOf<String>()
        var clearCount = 0

        override suspend fun speak(content: VoiceAnnouncementContent) = Unit

        override fun prefetch(content: VoiceAnnouncementContent) {
            content.ssml?.let { ssml -> prefetchedSsml += ssml }
        }

        override fun clearPrefetch() {
            clearCount += 1
        }
    }
}
