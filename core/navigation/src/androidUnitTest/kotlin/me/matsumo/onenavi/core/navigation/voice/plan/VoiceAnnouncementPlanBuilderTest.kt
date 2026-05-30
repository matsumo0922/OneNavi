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
import me.matsumo.drive.supporter.api.guidance.domain.GuideAnnouncementWindow
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
    fun `MIDDLE 段の距離窓は外部データの発話有効範囲から作られ FINAL は窓を持たない`() {
        val builder = VoiceAnnouncementPlanBuilder()
        val distanceContext = buildIdentityDistanceContext(totalMetres = 10_000.0)
        // 予告候補 (手前 60〜144m) と直前候補 (手前 0〜30m)。GP は source 5000m。
        val guidancePoint = buildGuidancePoint(
            index = 0,
            distanceFromStartMetres = 5_000,
            blocks = listOf(
                buildBlock(
                    id = "yokoku",
                    anchorSourceMetres = 5_000.0,
                    triggerDistanceMetres = 144,
                    groupId = 100,
                    window = GuideAnnouncementWindow(nearMetres = 60, farMetres = 144),
                ),
                buildBlock(
                    id = "chokuzen",
                    anchorSourceMetres = 5_000.0,
                    triggerDistanceMetres = 30,
                    groupId = 200,
                    window = GuideAnnouncementWindow(nearMetres = 0, farMetres = 30),
                ),
            ),
        )
        val payload = buildPayload(routeId = "R-1", guidancePoints = listOf(guidancePoint))

        val plan = builder.build(
            payload = payload,
            distanceContext = distanceContext,
            config = VoiceAnnouncementConfig(),
        )

        val stages = plan.targets.single().stages
        // chokuzen (最小 triggerDistance) が FINAL、yokoku が MIDDLE。
        val yokoku = stages.single { stage -> stage.id == VoiceAnnouncementId("R-1#gp0#yokoku") }
        val chokuzen = stages.single { stage -> stage.id == VoiceAnnouncementId("R-1#gp0#chokuzen") }
        assertEquals(AnnouncementStageKind.MIDDLE, yokoku.kind)
        assertEquals(AnnouncementStageKind.FINAL, chokuzen.kind)
        // MIDDLE の窓は [anchor - far, anchor - near] = [5000-144, 5000-60] = [4856, 4940] (identity 変換)。
        assertEquals(AnnouncementDistanceWindow(4_856.0, 4_940.0), yokoku.middleWindow)
        // FINAL は窓を持たない。
        assertEquals(null, chokuzen.middleWindow)
        // group_id が異なる候補は別 groupKey になる。
        assertEquals(VoiceAnnouncementId("R-1#gp0#grp100"), yokoku.groupKey)
        assertEquals(VoiceAnnouncementId("R-1#gp0#grp200"), chokuzen.groupKey)
    }

    @Test
    fun `距離窓が無い MIDDLE は delta 周りの狭い窓になり案内地点まで広げない`() {
        val builder = VoiceAnnouncementPlanBuilder()
        val distanceContext = buildIdentityDistanceContext(totalMetres = 10_000.0)
        // window 無し (旧データ互換 / 抽出漏れ)。FINAL を別に置いて b500 を MIDDLE にする。
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

        // window が無いので delta(500) 周りの狭い窓 = 残距離 [500-60, 500] → source [4500, 4560] (identity)。
        // 案内地点 geo 5000 までは広げない (広い窓は遠方トリガ block の誤発話を生むため)。
        val middleStage = plan.targets.single().stages.first { stage -> stage.kind == AnnouncementStageKind.MIDDLE }
        assertEquals(AnnouncementDistanceWindow(4_500.0, 4_560.0), middleStage.middleWindow)
    }

    @Test
    fun `同一 group_id の距離違い候補は同じ groupKey に束ねられる`() {
        val builder = VoiceAnnouncementPlanBuilder()
        val distanceContext = buildIdentityDistanceContext(totalMetres = 10_000.0)
        // 同一 group_id 131075 の予告 2 候補 (別文言) と、別 group_id の直前候補。
        val guidancePoint = buildGuidancePoint(
            index = 0,
            distanceFromStartMetres = 5_000,
            blocks = listOf(
                buildBlock("soon", anchorSourceMetres = 5_000.0, triggerDistanceMetres = 250, groupId = 131_075, text = "まもなく右方向です"),
                buildBlock("approx", anchorSourceMetres = 5_000.0, triggerDistanceMetres = 100, groupId = 131_075, text = "およそ100m先右方向です"),
                buildBlock("bare", anchorSourceMetres = 5_000.0, triggerDistanceMetres = 30, groupId = 65_539, text = "右方向です"),
            ),
        )
        val payload = buildPayload(routeId = "R-1", guidancePoints = listOf(guidancePoint))

        val plan = builder.build(
            payload = payload,
            distanceContext = distanceContext,
            config = VoiceAnnouncementConfig(),
        )

        val stages = plan.targets.single().stages
        val soon = stages.single { stage -> stage.id == VoiceAnnouncementId("R-1#gp0#soon") }
        val approx = stages.single { stage -> stage.id == VoiceAnnouncementId("R-1#gp0#approx") }
        val bare = stages.single { stage -> stage.id == VoiceAnnouncementId("R-1#gp0#bare") }
        // 予告 2 候補は同じ groupKey、直前は別 groupKey。
        assertEquals(soon.groupKey, approx.groupKey)
        assertEquals(VoiceAnnouncementId("R-1#gp0#grp131075"), soon.groupKey)
        assertEquals(VoiceAnnouncementId("R-1#gp0#grp65539"), bare.groupKey)
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
        // 複製段の窓は元 block の triggerDistance ではなく各 override 距離周りに作られる (identity 変換、幅 60)。
        // 残距離 [override-60, override] → source [anchor-override, anchor-(override-60)]。
        val windowsByTrigger = middleStages
            .sortedBy { stage -> stage.triggerSourceMeters }
            .map { stage -> stage.middleWindow }
        assertWindowApprox(windowsByTrigger[0], expectedEnter = 3_000.0, expectedExit = 3_060.0)
        assertWindowApprox(windowsByTrigger[1], expectedEnter = 4_000.0, expectedExit = 4_060.0)
        assertWindowApprox(windowsByTrigger[2], expectedEnter = 4_700.0, expectedExit = 4_760.0)
        // 各 override 段は別 groupKey になる (グループ消費で 1 回に潰れず、指定距離ごとに鳴る)。
        val middleGroupKeys = middleStages.map { stage -> stage.groupKey }.toSet()
        assertEquals(3, middleGroupKeys.size)
    }

    private fun assertWindowApprox(window: AnnouncementDistanceWindow?, expectedEnter: Double, expectedExit: Double) {
        val actual = requireNotNull(window) { "MIDDLE 段は距離窓を持つ" }
        assertEquals(expectedEnter, actual.enterGeometryMeters, absoluteTolerance = 0.01)
        assertEquals(expectedExit, actual.exitGeometryMeters, absoluteTolerance = 0.01)
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

    @Test
    fun `group_id 0 の候補は束ねず block 単位の grpDefault key になる`() {
        val builder = VoiceAnnouncementPlanBuilder()
        val distanceContext = buildIdentityDistanceContext(totalMetres = 10_000.0)
        // group_id=0 (グループ無し) の遠方予告 2 つ。参照実装と同じく互いに束ねない。
        val guidancePoint = buildGuidancePoint(
            index = 0,
            distanceFromStartMetres = 5_000,
            blocks = listOf(
                buildBlock("far2km", anchorSourceMetres = 5_000.0, triggerDistanceMetres = 2_000, text = "2km先右方向", groupId = 0),
                buildBlock("far1km", anchorSourceMetres = 5_000.0, triggerDistanceMetres = 1_000, text = "1km先右方向", groupId = 0),
                buildBlock("direct", anchorSourceMetres = 5_000.0, triggerDistanceMetres = 59, text = "右方向です", groupId = 65),
            ),
        )
        val payload = buildPayload(routeId = "R-1", guidancePoints = listOf(guidancePoint))

        val plan = builder.build(
            payload = payload,
            distanceContext = distanceContext,
            config = VoiceAnnouncementConfig(),
        )

        val stages = plan.targets.single().stages
        val far2km = stages.single { stage -> stage.id == VoiceAnnouncementId("R-1#gp0#far2km") }
        val far1km = stages.single { stage -> stage.id == VoiceAnnouncementId("R-1#gp0#far1km") }
        // group_id=0 同士は別 key (block 単位で一意)。束ねるとどちらか 1 つしか鳴らなくなるため。
        assertTrue(far2km.groupKey != far1km.groupKey)
        assertEquals(VoiceAnnouncementId("R-1#gp0#grpDefault#far2km"), far2km.groupKey)
        assertEquals(VoiceAnnouncementId("R-1#gp0#grpDefault#far1km"), far1km.groupKey)
    }

    @Test
    fun `汎用フラグはテンプレート ID から決まり全候補が段として残る`() {
        val builder = VoiceAnnouncementPlanBuilder()
        val distanceContext = buildIdentityDistanceContext(totalMetres = 10_000.0)
        // 同一予告 group 131 に「まもなく」(汎用 template 100) と「200m先」(具体 template 104)。
        // 絞り込みは発話時の選抜 (Selector) が担うため、プラン構築では全候補を段として残す。
        val guidancePoint = buildGuidancePoint(
            index = 0,
            distanceFromStartMetres = 5_000,
            blocks = listOf(
                buildBlock("soon", anchorSourceMetres = 5_000.0, triggerDistanceMetres = 250, text = "まもなく右方向です", groupId = 131, templateRef = 100),
                buildBlock("approx", anchorSourceMetres = 5_000.0, triggerDistanceMetres = 200, text = "およそ200m先右方向です", groupId = 131, templateRef = 104),
                buildBlock("direct", anchorSourceMetres = 5_000.0, triggerDistanceMetres = 59, text = "右方向です", groupId = 65, templateRef = 100),
            ),
        )
        val payload = buildPayload(routeId = "R-1", guidancePoints = listOf(guidancePoint))

        val plan = builder.build(
            payload = payload,
            distanceContext = distanceContext,
            config = VoiceAnnouncementConfig(),
        )

        val stages = plan.targets.single().stages
        val soon = stages.single { stage -> stage.id == VoiceAnnouncementId("R-1#gp0#soon") }
        val approx = stages.single { stage -> stage.id == VoiceAnnouncementId("R-1#gp0#approx") }
        // 全候補が段として残る (脱落させない)。
        assertEquals(3, stages.size)
        // 全 piece が template 100 の soon は汎用、template 104 を含む approx は非汎用。
        assertTrue(soon.isGeneric)
        assertTrue(!approx.isGeneric)
        // 同一 group の予告は同じ groupKey。
        assertEquals(soon.groupKey, approx.groupKey)
    }

    @Test
    fun `汎用フラグは先頭 piece のテンプレートで決まる`() {
        val builder = VoiceAnnouncementPlanBuilder()
        val distanceContext = buildIdentityDistanceContext(totalMetres = 10_000.0)
        // 先頭 piece が汎用(100)で後続に別テンプレ(416)が混ざる予告と、先頭が具体(104)の予告。
        val guidancePoint = buildGuidancePoint(
            index = 0,
            distanceFromStartMetres = 5_000,
            blocks = listOf(
                buildBlock("soonMixed", anchorSourceMetres = 5_000.0, triggerDistanceMetres = 250, text = "まもなく右方向です", groupId = 131, templateRef = 100, tailTemplateRef = 416),
                buildBlock("approx", anchorSourceMetres = 5_000.0, triggerDistanceMetres = 200, text = "およそ200m先右方向です", groupId = 131, templateRef = 104, tailTemplateRef = 416),
                buildBlock("direct", anchorSourceMetres = 5_000.0, triggerDistanceMetres = 59, text = "右方向です", groupId = 65, templateRef = 100),
            ),
        )
        val payload = buildPayload(routeId = "R-1", guidancePoints = listOf(guidancePoint))

        val plan = builder.build(
            payload = payload,
            distanceContext = distanceContext,
            config = VoiceAnnouncementConfig(),
        )

        val stages = plan.targets.single().stages
        val soonMixed = stages.single { stage -> stage.id == VoiceAnnouncementId("R-1#gp0#soonMixed") }
        val approx = stages.single { stage -> stage.id == VoiceAnnouncementId("R-1#gp0#approx") }
        // 後続 piece に別テンプレが混ざっても、先頭 piece が 100 の soonMixed は汎用。
        assertTrue(soonMixed.isGeneric)
        // 先頭が 104 の approx は後続が混ざっても非汎用。
        assertTrue(!approx.isGeneric)
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
        text: String = "${id}先",
        groupId: Int = 0,
        window: GuideAnnouncementWindow? = null,
        templateRef: Int? = null,
        tailTemplateRef: Int? = null,
    ): GuideAnnouncementBlock {
        // 既定ではブロックごとに別文言 ("${id}先") とし、dedup で畳まれないようにする。
        // tailTemplateRef を渡すと先頭 piece と別テンプレートの 2 つ目 piece を足す (先頭 piece 判定の検証用)。
        val headPiece = GuideAnnouncementPiece(
            text = text,
            ssml = null,
            templateRef = templateRef,
            category = categories.firstOrNull(),
        )
        val pieces = if (tailTemplateRef == null) {
            persistentListOf(headPiece)
        } else {
            persistentListOf(
                headPiece,
                GuideAnnouncementPiece(text = "$text-tail", ssml = null, templateRef = tailTemplateRef, category = null),
            )
        }
        return GuideAnnouncementBlock(
            id = id,
            anchor = ExternalGuideAnchor(
                sourceDistanceFromStartMetres = anchorSourceMetres,
                sourceGuidancePointIndex = null,
                sourceBlockIndex = null,
            ),
            triggerDistanceMetres = triggerDistanceMetres,
            groupId = groupId,
            window = window,
            pieces = pieces,
            categories = categories.toImmutableSet(),
        )
    }
}
