package me.matsumo.onenavi.core.navigation.voice.plan

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableSet
import me.matsumo.drive.supporter.api.guidance.domain.DsrRouteSummary
import me.matsumo.drive.supporter.api.guidance.domain.ExternalGuideAnchor
import me.matsumo.drive.supporter.api.guidance.domain.GuidanceCategory
import me.matsumo.drive.supporter.api.guidance.domain.GuidancePoint
import me.matsumo.drive.supporter.api.guidance.domain.GuideAnnouncementBlock
import me.matsumo.drive.supporter.api.guidance.domain.GuideAnnouncementPiece
import me.matsumo.drive.supporter.api.guidance.domain.RouteGuidance
import me.matsumo.onenavi.core.navigation.extnav.DistanceAnchor
import me.matsumo.onenavi.core.navigation.extnav.ExtNavRouteDistanceContext
import me.matsumo.onenavi.core.navigation.extnav.ExtNavRoutePayload
import me.matsumo.onenavi.core.navigation.extnav.RouteDistanceMapper
import me.matsumo.onenavi.core.navigation.voice.config.VoiceAnnouncementConfig
import me.matsumo.onenavi.core.navigation.voice.config.VoiceAnnouncementDistanceOverrides
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [VoiceAnnouncementPlanBuilder] の距離変換 / 段分類 / 距離 override の構築テスト。
 */
class VoiceAnnouncementPlanBuilderTest {

    @Test
    fun `source トリガ距離が geometry fire point に変換される`() {
        val builder = VoiceAnnouncementPlanBuilder()
        // source 1m -> geometry 2m の変換。総 geometry 距離は 20000m。
        val distanceContext = buildDistanceContext(
            sourceTotalMetres = 10_000.0,
            geometryTotalMetres = 20_000.0,
        )
        val guidancePoint = buildGuidancePoint(
            index = 0,
            distanceFromStartMetres = 5_000,
            blocks = listOf(
                buildBlock(
                    id = "b0",
                    anchorSourceMetres = 5_000.0,
                    triggerDistanceMetres = 200,
                    categories = listOf(GuidanceCategory.IntersectionGuide),
                ),
            ),
        )
        val payload = buildPayload(routeId = "R-1", guidancePoints = listOf(guidancePoint))

        val plan = builder.build(
            payload = payload,
            distanceContext = distanceContext,
            config = VoiceAnnouncementConfig(),
        )

        val stage = plan.targets.single().stages.single()
        // triggerSource = anchor(5000) - trigger(200) = 4800m。geometry はその 2 倍。
        assertEquals(4_800.0, stage.triggerSourceMeters, absoluteTolerance = 0.001)
        assertEquals(9_600.0, stage.triggerGeometryMeters, absoluteTolerance = 0.001)
        assertEquals(10_000.0, plan.targets.single().geometryMeters, absoluteTolerance = 0.001)
    }

    @Test
    fun `最も手前の段が FINAL それ以外が MIDDLE になり緊急度昇順で並ぶ`() {
        val builder = VoiceAnnouncementPlanBuilder()
        val distanceContext = buildIdentityDistanceContext(totalMetres = 10_000.0)
        val guidancePoint = buildGuidancePoint(
            index = 0,
            distanceFromStartMetres = 5_000,
            blocks = listOf(
                buildBlock("b2000", anchorSourceMetres = 5_000.0, triggerDistanceMetres = 2_000),
                buildBlock("b1000", anchorSourceMetres = 5_000.0, triggerDistanceMetres = 1_000),
                buildBlock("b300", anchorSourceMetres = 5_000.0, triggerDistanceMetres = 300),
                buildBlock("b100", anchorSourceMetres = 5_000.0, triggerDistanceMetres = 100),
            ),
        )
        val payload = buildPayload(routeId = "R-1", guidancePoints = listOf(guidancePoint))

        val plan = builder.build(
            payload = payload,
            distanceContext = distanceContext,
            config = VoiceAnnouncementConfig(),
        )

        val stages = plan.targets.single().stages
        assertEquals(4, stages.size)
        // 緊急度昇順 = triggerSourceMeters 昇順。最緊急 (= 最も手前) の段が末尾。
        assertEquals(listOf(3_000.0, 4_000.0, 4_700.0, 4_900.0), stages.map { stage -> stage.triggerSourceMeters })
        assertEquals(
            listOf(
                AnnouncementStageKind.MIDDLE,
                AnnouncementStageKind.MIDDLE,
                AnnouncementStageKind.MIDDLE,
                AnnouncementStageKind.FINAL,
            ),
            stages.map { stage -> stage.kind },
        )
    }

    @Test
    fun `MIDDLE 段には隣接段との間でタイル状の距離窓が割り当てられ FINAL は窓を持たない`() {
        val builder = VoiceAnnouncementPlanBuilder()
        val distanceContext = buildIdentityDistanceContext(totalMetres = 10_000.0)
        val guidancePoint = buildGuidancePoint(
            index = 0,
            distanceFromStartMetres = 5_000,
            blocks = listOf(
                buildBlock("b2000", anchorSourceMetres = 5_000.0, triggerDistanceMetres = 2_000),
                buildBlock("b1000", anchorSourceMetres = 5_000.0, triggerDistanceMetres = 1_000),
                buildBlock("b300", anchorSourceMetres = 5_000.0, triggerDistanceMetres = 300),
                buildBlock("b100", anchorSourceMetres = 5_000.0, triggerDistanceMetres = 100),
            ),
        )
        val payload = buildPayload(routeId = "R-1", guidancePoints = listOf(guidancePoint))

        val plan = builder.build(
            payload = payload,
            distanceContext = distanceContext,
            config = VoiceAnnouncementConfig(),
        )

        // triggerGeometry 昇順: 3000(MIDDLE) -> 4000(MIDDLE) -> 4700(MIDDLE) -> 4900(FINAL)。GP は 5000。
        val stages = plan.targets.single().stages
        val windows = stages.map { stage -> stage.middleWindow }
        // 各 MIDDLE の窓 = [自トリガ, 次段トリガ)。最も手前 MIDDLE の終端は次段 (FINAL) のトリガ 4900。
        assertEquals(AnnouncementDistanceWindow(3_000.0, 4_000.0), windows[0])
        assertEquals(AnnouncementDistanceWindow(4_000.0, 4_700.0), windows[1])
        assertEquals(AnnouncementDistanceWindow(4_700.0, 4_900.0), windows[2])
        // FINAL は到達リードタイム逆算で発話するため窓を持たない。
        assertEquals(null, windows[3])
    }

    @Test
    fun `後続段が無い MIDDLE のみの GP は窓の終端が案内地点になる`() {
        val builder = VoiceAnnouncementPlanBuilder()
        val distanceContext = buildIdentityDistanceContext(totalMetres = 10_000.0)
        // FINAL は最も手前の 1 段だけなので、MIDDLE が 1 段だけ残る GP を作る。
        val guidancePoint = buildGuidancePoint(
            index = 0,
            distanceFromStartMetres = 5_000,
            blocks = listOf(
                buildBlock("b500", anchorSourceMetres = 5_000.0, triggerDistanceMetres = 500),
                buildBlock("b100", anchorSourceMetres = 5_000.0, triggerDistanceMetres = 100),
            ),
        )
        val payload = buildPayload(routeId = "R-1", guidancePoints = listOf(guidancePoint))

        val plan = builder.build(
            payload = payload,
            distanceContext = distanceContext,
            config = VoiceAnnouncementConfig(),
        )

        // MIDDLE は b500 (trigger geo 4500) のみ。終端は次段 FINAL b100 (trigger geo 4900)。
        val middleStage = plan.targets.single().stages.first { stage -> stage.kind == AnnouncementStageKind.MIDDLE }
        assertEquals(AnnouncementDistanceWindow(4_500.0, 4_900.0), middleStage.middleWindow)
    }

    @Test
    fun `block が 1 つだけの GP はその段が FINAL になる`() {
        val builder = VoiceAnnouncementPlanBuilder()
        val distanceContext = buildIdentityDistanceContext(totalMetres = 10_000.0)
        val guidancePoint = buildGuidancePoint(
            index = 7,
            distanceFromStartMetres = 3_000,
            blocks = listOf(
                buildBlock("only", anchorSourceMetres = 3_000.0, triggerDistanceMetres = 80),
            ),
        )
        val payload = buildPayload(routeId = "R-1", guidancePoints = listOf(guidancePoint))

        val plan = builder.build(
            payload = payload,
            distanceContext = distanceContext,
            config = VoiceAnnouncementConfig(),
        )

        val stage = plan.targets.single().stages.single()
        assertEquals(AnnouncementStageKind.FINAL, stage.kind)
        assertEquals(VoiceAnnouncementId("R-1#gp7#only"), stage.id)
    }

    @Test
    fun `source 距離を持たない block は除外される`() {
        val builder = VoiceAnnouncementPlanBuilder()
        val distanceContext = buildIdentityDistanceContext(totalMetres = 10_000.0)
        val guidancePoint = buildGuidancePoint(
            index = 0,
            distanceFromStartMetres = 5_000,
            blocks = listOf(
                buildBlock("mapped", anchorSourceMetres = 5_000.0, triggerDistanceMetres = 100),
                buildBlock("unmapped", anchorSourceMetres = null, triggerDistanceMetres = 2_000),
            ),
        )
        val payload = buildPayload(routeId = "R-1", guidancePoints = listOf(guidancePoint))

        val plan = builder.build(
            payload = payload,
            distanceContext = distanceContext,
            config = VoiceAnnouncementConfig(),
        )

        val stages = plan.targets.single().stages
        assertEquals(1, stages.size)
        assertEquals(VoiceAnnouncementId("R-1#gp0#mapped"), stages.single().id)
    }

    @Test
    fun `全 block が source 距離を持たない GP は target にならない`() {
        val builder = VoiceAnnouncementPlanBuilder()
        val distanceContext = buildIdentityDistanceContext(totalMetres = 10_000.0)
        val guidancePoint = buildGuidancePoint(
            index = 0,
            distanceFromStartMetres = 5_000,
            blocks = listOf(
                buildBlock("unmapped", anchorSourceMetres = null, triggerDistanceMetres = 100),
            ),
        )
        val payload = buildPayload(routeId = "R-1", guidancePoints = listOf(guidancePoint))

        val plan = builder.build(
            payload = payload,
            distanceContext = distanceContext,
            config = VoiceAnnouncementConfig(),
        )

        assertTrue(plan.targets.isEmpty())
    }

    @Test
    fun `距離 override は MIDDLE block を複数段に複製し FINAL は据え置く`() {
        val builder = VoiceAnnouncementPlanBuilder()
        val distanceContext = buildIdentityDistanceContext(totalMetres = 10_000.0)
        val config = VoiceAnnouncementConfig(
            distanceOverrides = VoiceAnnouncementDistanceOverrides.ByCategory(
                overrides = persistentMapOf(
                    GuidanceCategory.IntersectionGuide to persistentListOf(2_000.0, 1_000.0, 300.0),
                ),
            ),
        )
        val guidancePoint = buildGuidancePoint(
            index = 3,
            distanceFromStartMetres = 5_000,
            blocks = listOf(
                buildBlock(
                    id = "middle",
                    anchorSourceMetres = 5_000.0,
                    triggerDistanceMetres = 1_500,
                    categories = listOf(GuidanceCategory.IntersectionGuide),
                ),
                buildBlock(
                    id = "final",
                    anchorSourceMetres = 5_000.0,
                    triggerDistanceMetres = 100,
                    categories = listOf(GuidanceCategory.IntersectionGuide),
                ),
            ),
        )
        val payload = buildPayload(routeId = "R-1", guidancePoints = listOf(guidancePoint))

        val plan = builder.build(
            payload = payload,
            distanceContext = distanceContext,
            config = config,
        )

        val stages = plan.targets.single().stages
        val middleStages = stages.filter { stage -> stage.kind == AnnouncementStageKind.MIDDLE }
        val finalStages = stages.filter { stage -> stage.kind == AnnouncementStageKind.FINAL }
        assertEquals(3, middleStages.size)
        assertEquals(1, finalStages.size)
        // triggerSource 昇順 = 手前距離 2000 -> 1000 -> 300 の順。
        val middleIdsByTrigger = middleStages
            .sortedBy { stage -> stage.triggerSourceMeters }
            .map { stage -> stage.id }
        assertEquals(
            listOf(
                VoiceAnnouncementId("R-1#gp3#middle#2000"),
                VoiceAnnouncementId("R-1#gp3#middle#1000"),
                VoiceAnnouncementId("R-1#gp3#middle#300"),
            ),
            middleIdsByTrigger,
        )
    }

    @Test
    fun `target は geometryMeters 昇順で並ぶ`() {
        val builder = VoiceAnnouncementPlanBuilder()
        val distanceContext = buildIdentityDistanceContext(totalMetres = 10_000.0)
        val farPoint = buildGuidancePoint(
            index = 0,
            distanceFromStartMetres = 5_000,
            blocks = listOf(buildBlock("far", anchorSourceMetres = 5_000.0, triggerDistanceMetres = 100)),
        )
        val nearPoint = buildGuidancePoint(
            index = 1,
            distanceFromStartMetres = 2_000,
            blocks = listOf(buildBlock("near", anchorSourceMetres = 2_000.0, triggerDistanceMetres = 100)),
        )
        val payload = buildPayload(routeId = "R-1", guidancePoints = listOf(farPoint, nearPoint))

        val plan = builder.build(
            payload = payload,
            distanceContext = distanceContext,
            config = VoiceAnnouncementConfig(),
        )

        assertEquals("R-1", plan.routeId)
        assertEquals(listOf(2_000.0, 5_000.0), plan.targets.map { target -> target.geometryMeters })
    }

    @Test
    fun `発話内容が同一の重複 block は案内点に近い1段に畳まれる`() {
        val builder = VoiceAnnouncementPlanBuilder()
        val distanceContext = buildIdentityDistanceContext(totalMetres = 10_000.0)
        // 「この信号を左」を 3 距離で重複させ、別文言の「500m先左」を 1 つ持つ GP。
        val guidancePoint = buildGuidancePoint(
            index = 0,
            distanceFromStartMetres = 5_000,
            blocks = listOf(
                buildBlock("dupFar", anchorSourceMetres = 5_000.0, triggerDistanceMetres = 300, text = "この信号を左"),
                buildBlock("dupMid", anchorSourceMetres = 5_000.0, triggerDistanceMetres = 200, text = "この信号を左"),
                buildBlock("dupNear", anchorSourceMetres = 5_000.0, triggerDistanceMetres = 100, text = "この信号を左"),
                buildBlock("ahead", anchorSourceMetres = 5_000.0, triggerDistanceMetres = 500, text = "500m先左"),
            ),
        )
        val payload = buildPayload(routeId = "R-1", guidancePoints = listOf(guidancePoint))

        val plan = builder.build(
            payload = payload,
            distanceContext = distanceContext,
            config = VoiceAnnouncementConfig(),
        )

        val stages = plan.targets.single().stages
        // 重複 3 つは最寄り (triggerDistance 100) の dupNear 1 段に畳まれ、ahead と合わせて 2 段。
        assertEquals(2, stages.size)
        assertEquals(
            listOf(VoiceAnnouncementId("R-1#gp0#ahead"), VoiceAnnouncementId("R-1#gp0#dupNear")),
            stages.map { stage -> stage.id },
        )
        // 残った最寄りの重複段が FINAL になる。
        val dupStage = stages.single { stage -> stage.id == VoiceAnnouncementId("R-1#gp0#dupNear") }
        assertEquals(AnnouncementStageKind.FINAL, dupStage.kind)
    }

    @Test
    fun `読み上げテキストが同一なら piece の category が違っても1段に畳む`() {
        val builder = VoiceAnnouncementPlanBuilder()
        val distanceContext = buildIdentityDistanceContext(totalMetres = 10_000.0)
        // 同一テキストだがトリガ理由 (category) が異なる重複。外部データに実在するパターン。
        val guidancePoint = buildGuidancePoint(
            index = 0,
            distanceFromStartMetres = 5_000,
            blocks = listOf(
                buildBlock(
                    "speedReason",
                    anchorSourceMetres = 5_000.0,
                    triggerDistanceMetres = 80,
                    categories = listOf(GuidanceCategory.SpeedAdjustment),
                    text = "この信号を左方向です",
                ),
                buildBlock(
                    "signalReason",
                    anchorSourceMetres = 5_000.0,
                    triggerDistanceMetres = 59,
                    categories = listOf(GuidanceCategory.TrafficLight),
                    text = "この信号を左方向です",
                ),
            ),
        )
        val payload = buildPayload(routeId = "R-1", guidancePoints = listOf(guidancePoint))

        val plan = builder.build(
            payload = payload,
            distanceContext = distanceContext,
            config = VoiceAnnouncementConfig(),
        )

        // category が違っても読み上げが同じなら畳まれ、最寄り (triggerDistance 59) の1段だけ残る。
        val stage = plan.targets.single().stages.single()
        assertEquals(VoiceAnnouncementId("R-1#gp0#signalReason"), stage.id)
    }

    private fun buildIdentityDistanceContext(totalMetres: Double): ExtNavRouteDistanceContext =
        buildDistanceContext(sourceTotalMetres = totalMetres, geometryTotalMetres = totalMetres)

    private fun buildDistanceContext(
        sourceTotalMetres: Double,
        geometryTotalMetres: Double,
    ): ExtNavRouteDistanceContext {
        val mapper = RouteDistanceMapper(
            anchors = listOf(
                DistanceAnchor(sourceMetres = 0.0, geometryMetres = 0.0),
                DistanceAnchor(sourceMetres = sourceTotalMetres, geometryMetres = geometryTotalMetres),
            ),
        )
        return ExtNavRouteDistanceContext(
            distanceMapper = mapper,
            totalGeometryMetres = geometryTotalMetres,
        )
    }

    private fun buildPayload(
        routeId: String,
        guidancePoints: List<GuidancePoint>,
    ): ExtNavRoutePayload = ExtNavRoutePayload(
        id = routeId,
        routeGuidance = RouteGuidance(
            index = 0,
            priority = null,
            summary = DsrRouteSummary(
                depth = 0,
                distanceMetres = 0,
                timeSeconds = 0,
                fuelLitres = 0f,
                tollYen = 0,
                tollDetails = persistentListOf(),
                streets = persistentListOf(),
                priority = 0,
                trafficCongestionAvoidanceRate = 0f,
            ),
            guidancePoints = guidancePoints.toImmutableList(),
            intersections = persistentListOf(),
            imageIds = persistentListOf(),
            polyline = persistentListOf(),
        ),
    )

    private fun buildGuidancePoint(
        index: Int,
        distanceFromStartMetres: Int,
        blocks: List<GuideAnnouncementBlock>,
    ): GuidancePoint = GuidancePoint(
        index = index,
        gpType = 0,
        distanceFromPrevMetres = 0,
        distanceFromStartMetres = distanceFromStartMetres,
        phrases = persistentListOf(),
        announcementBlocks = blocks.toImmutableList(),
        imageRefs = persistentListOf(),
        maneuver = null,
    )

    private fun buildBlock(
        id: String,
        anchorSourceMetres: Double?,
        triggerDistanceMetres: Int,
        categories: List<GuidanceCategory> = listOf(GuidanceCategory.IntersectionGuide),
        text: String = id,
    ): GuideAnnouncementBlock {
        // 既定ではブロックごとに別文言 (text = id) とし、dedup で畳まれないようにする。
        // 重複を再現するテストだけ同一 text を渡す。
        val pieces = persistentListOf(
            GuideAnnouncementPiece(
                text = text,
                ssml = null,
                templateRef = null,
                category = categories.firstOrNull(),
            ),
        )
        return GuideAnnouncementBlock(
            id = id,
            anchor = ExternalGuideAnchor(
                sourceDistanceFromStartMetres = anchorSourceMetres,
                sourceGuidancePointIndex = null,
                sourceBlockIndex = null,
            ),
            triggerDistanceMetres = triggerDistanceMetres,
            pieces = pieces,
            categories = categories.toImmutableSet(),
        )
    }
}
