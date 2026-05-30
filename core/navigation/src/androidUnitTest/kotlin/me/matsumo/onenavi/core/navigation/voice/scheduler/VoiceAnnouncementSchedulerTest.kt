package me.matsumo.onenavi.core.navigation.voice.scheduler

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import me.matsumo.drive.supporter.api.guidance.domain.GuidanceCategory
import me.matsumo.drive.supporter.api.guidance.domain.GuideAnnouncementPiece
import me.matsumo.onenavi.core.navigation.voice.config.VoiceAnnouncementCategoryGate
import me.matsumo.onenavi.core.navigation.voice.config.VoiceAnnouncementConfig
import me.matsumo.onenavi.core.navigation.voice.dispatch.VoiceAnnouncementContentRenderer
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
        // 同一案内地点の遠い中間段 (発話中) に、直前段が割り込むケース。
        scheduler.attach(
            planOf(
                targetOf(
                    index = 0,
                    geometryMeters = 1_000.0,
                    middleStage("farMiddle", triggerGeometryMeters = 100.0),
                    finalStage("nearFinal", triggerGeometryMeters = 900.0),
                ),
            ),
        )

        // 遠い中間段を先に発話開始 (FINAL はまだ手前距離に達していない)。
        val first = scheduler.onTick(tickOf(current = 150.0))
        // FINAL が手前距離に達し、発話中の中間段より緊急 → 中断+割り込み。
        val bargeIn = scheduler.onTick(tickOf(current = 975.0))

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
    fun `距離違いの予告は連発せず 予告1回と直前1回に収まる`() {
        val scheduler = schedulerOf()
        // GP geo 1000。500m / 300m / 100m 帯の予告 (タイル窓) と、直前の FINAL。
        scheduler.attach(
            planOf(
                targetOf(
                    index = 0,
                    geometryMeters = 1_000.0,
                    middleStageWindowed("m500", enter = 500.0, exit = 700.0),
                    middleStageWindowed("m300", enter = 700.0, exit = 900.0),
                    middleStageWindowed("m100", enter = 900.0, exit = 1_000.0),
                    finalStage("final"),
                ),
            ),
        )

        // 500m 帯で予告を 1 回開始し、完了させる。
        val prediction = scheduler.onTick(tickOf(current = 550.0))
        scheduler.onSpeechFinished(VoiceAnnouncementId("m500"))
        // 300m 帯・100m 帯に入っても、グループ消費で予告は二度と鳴らない。
        val atM300Band = scheduler.onTick(tickOf(current = 750.0))
        val atM100Band = scheduler.onTick(tickOf(current = 950.0))
        // 直前で FINAL だけが鳴る。
        val finalCommand = scheduler.onTick(tickOf(current = 975.0))

        assertEquals(
            VoiceAnnouncementId("m500"),
            assertIs<VoiceAnnouncementCommand.StartSpeaking>(prediction).request.stageId,
        )
        assertNull(atM300Band)
        assertNull(atM100Band)
        assertEquals(
            VoiceAnnouncementId("final"),
            assertIs<VoiceAnnouncementCommand.StartSpeaking>(finalCommand).request.stageId,
        )
    }

    @Test
    fun `同一案内地点で発話済みと同じテキストの段は二重発話せず畳む`() {
        val scheduler = schedulerOf()
        // 同一 target に、別グループ・別 stage だが render 後テキストが同じ 2 段
        // (category gate 適用後に同一文言へ畳まれるケースの再現)。
        scheduler.attach(
            planOf(
                targetOf(
                    index = 0,
                    geometryMeters = 1_000.0,
                    middleStageWindowed("first", enter = 500.0, exit = 700.0, groupKey = "grpA", text = "右方向です"),
                    middleStageWindowed("second", enter = 700.0, exit = 900.0, groupKey = "grpB", text = "右方向です"),
                ),
            ),
        )

        // first を発話開始し完了させる (テキスト "右方向です" を発話確定済みに記録)。
        val firstCommand = scheduler.onTick(tickOf(current = 550.0))
        scheduler.onSpeechFinished(VoiceAnnouncementId("first"))
        // second は別グループで窓に入るが、同一案内地点で同じテキストが発話済みなので抑止 (指示なし)。
        val secondCommand = scheduler.onTick(tickOf(current = 750.0))

        assertEquals(
            VoiceAnnouncementId("first"),
            assertIs<VoiceAnnouncementCommand.StartSpeaking>(firstCommand).request.stageId,
        )
        assertNull(secondCommand)
    }

    @Test
    fun `別の案内地点なら同じテキストでも発話する`() {
        val scheduler = schedulerOf()
        // 連続する別 target が同じ「右方向です」を持つ正当なケース (案内地点が違えば抑止しない)。
        scheduler.attach(
            planOf(
                targetOf(index = 0, geometryMeters = 500.0, middleStageWindowed("t0", enter = 400.0, exit = 500.0, text = "右方向です")),
                targetOf(index = 1, geometryMeters = 1_500.0, middleStageWindowed("t1", enter = 1_400.0, exit = 1_500.0, text = "右方向です")),
            ),
        )

        // target0 で発話・完了。
        val command0 = scheduler.onTick(tickOf(current = 450.0))
        scheduler.onSpeechFinished(VoiceAnnouncementId("t0"))
        // target0 を通過した後 target1 で同じテキストでも別案内なので発話する。
        val command1 = scheduler.onTick(tickOf(current = 1_450.0))

        assertEquals(VoiceAnnouncementId("t0"), assertIs<VoiceAnnouncementCommand.StartSpeaking>(command0).request.stageId)
        assertEquals(VoiceAnnouncementId("t1"), assertIs<VoiceAnnouncementCommand.StartSpeaking>(command1).request.stageId)
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

    // dispatch の状態遷移を検証するテストなので、既定では窓上限を実質無制限にして「トリガ到達後は候補」とする。
    private fun middleStage(
        id: String,
        triggerGeometryMeters: Double,
        category: GuidanceCategory = GuidanceCategory.IntersectionGuide,
        groupKey: String = "grp",
    ): AnnouncementStage = stageOf(
        id = id,
        kind = AnnouncementStageKind.MIDDLE,
        triggerGeometryMeters = triggerGeometryMeters,
        category = category,
        groupKey = groupKey,
        window = AnnouncementDistanceWindow(enterGeometryMeters = triggerGeometryMeters, exitGeometryMeters = Double.MAX_VALUE),
    )

    // 距離違いの代替候補をタイル状の窓で表す MIDDLE 段。同一 groupKey で束ね、グループ消費の統合テストで使う。
    private fun middleStageWindowed(
        id: String,
        enter: Double,
        exit: Double,
        category: GuidanceCategory = GuidanceCategory.IntersectionGuide,
        groupKey: String = "grp",
        text: String = id,
    ): AnnouncementStage = stageOf(
        id = id,
        kind = AnnouncementStageKind.MIDDLE,
        triggerGeometryMeters = enter,
        category = category,
        groupKey = groupKey,
        window = AnnouncementDistanceWindow(enterGeometryMeters = enter, exitGeometryMeters = exit),
        text = text,
    )

    private fun finalStage(
        id: String,
        triggerGeometryMeters: Double = 0.0,
        category: GuidanceCategory = GuidanceCategory.IntersectionGuide,
        groupKey: String = "final-grp",
    ): AnnouncementStage = stageOf(
        id = id,
        kind = AnnouncementStageKind.FINAL,
        triggerGeometryMeters = triggerGeometryMeters,
        category = category,
        groupKey = groupKey,
        window = null,
    )

    private fun stageOf(
        id: String,
        kind: AnnouncementStageKind,
        triggerGeometryMeters: Double,
        category: GuidanceCategory,
        groupKey: String,
        window: AnnouncementDistanceWindow?,
        text: String = id,
    ): AnnouncementStage = AnnouncementStage(
        id = VoiceAnnouncementId(id),
        groupKey = VoiceAnnouncementId(groupKey),
        kind = kind,
        triggerSourceMeters = triggerGeometryMeters,
        triggerGeometryMeters = triggerGeometryMeters,
        middleWindow = window,
        isGeneric = false,
        pieces = persistentListOf(
            GuideAnnouncementPiece(text = text, ssml = null, templateRef = null, category = category),
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
