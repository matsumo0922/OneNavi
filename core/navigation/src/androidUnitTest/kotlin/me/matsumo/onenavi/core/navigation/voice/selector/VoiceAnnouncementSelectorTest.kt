package me.matsumo.onenavi.core.navigation.voice.selector

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import me.matsumo.onenavi.core.navigation.voice.config.VoiceAnnouncementConfig
import me.matsumo.onenavi.core.navigation.voice.plan.AnnouncementDistanceWindow
import me.matsumo.onenavi.core.navigation.voice.plan.AnnouncementStage
import me.matsumo.onenavi.core.navigation.voice.plan.AnnouncementStageKind
import me.matsumo.onenavi.core.navigation.voice.plan.AnnouncementTarget
import me.matsumo.onenavi.core.navigation.voice.plan.VoiceAnnouncementId
import me.matsumo.onenavi.core.navigation.voice.plan.VoiceAnnouncementPlan
import me.matsumo.onenavi.core.navigation.voice.suppression.VoiceAnnouncementSpeechState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [VoiceAnnouncementSelector] の距離窓判定 / グループ消費 / 到達リードタイム判定 / 緊急度選択 / 抑止のテスト。
 */
class VoiceAnnouncementSelectorTest {

    @Test
    fun `中間段は距離窓に入った間だけ候補になり 既処理になると止まる`() {
        val selector = VoiceAnnouncementSelector(VoiceAnnouncementConfig())
        val plan = planOf(
            targetOf(
                index = 0,
                geometryMeters = 1_000.0,
                stages = listOf(middleStage("m800", enter = 800.0, exit = 1_000.0)),
            ),
        )

        // 窓 [800, 1000) の外なら出ない。窓に入れば未処理の間は選ばれる。
        val beforeWindow = selector.select(plan, tickOf(current = 750.0), emptyState())
        val inWindow = selector.select(plan, tickOf(current = 850.0), emptyState())
        // 既処理マークが付いたら止まる。
        val afterProcessed = selector.select(plan, tickOf(current = 900.0), emptyState().withStageFired(VoiceAnnouncementId("m800")))

        assertNull(beforeWindow)
        assertEquals(VoiceAnnouncementId("m800"), inWindow?.stage?.id)
        assertNull(afterProcessed)
    }

    @Test
    fun `距離違いの予告は1機会1つだけ選ばれ グループ消費で距離ラダーが連発しない`() {
        val selector = VoiceAnnouncementSelector(VoiceAnnouncementConfig())
        // GP geo 1000。500m / 300m / 100m 手前の予告をタイル状の窓で持ち、最も手前が FINAL。
        val plan = planOf(
            targetOf(
                index = 0,
                geometryMeters = 1_000.0,
                stages = listOf(
                    middleStage("m500", enter = 500.0, exit = 700.0),
                    middleStage("m300", enter = 700.0, exit = 900.0),
                    middleStage("m100", enter = 900.0, exit = 1_000.0),
                    finalStage("final"),
                ),
            ),
        )

        // 遠くから接近し、最初の発話機会 (500m 帯) で m500 を採る。
        val firstOpportunity = selector.select(plan, tickOf(current = 550.0), emptyState())
        // m500 が処理済みになると、以後どの距離帯に入っても MIDDLE はもう鳴らない (グループ消費)。
        val consumedState = emptyState().withStageFired(VoiceAnnouncementId("m500"))
        val atM300Band = selector.select(plan, tickOf(current = 750.0), consumedState)
        val atM100Band = selector.select(plan, tickOf(current = 940.0), consumedState)
        // FINAL はグループ消費に左右されず、到達リードタイムに達したら鳴る。
        val finalSelection = selector.select(plan, tickOf(current = 975.0), consumedState)

        assertEquals(VoiceAnnouncementId("m500"), firstOpportunity?.stage?.id)
        assertNull(atM300Band)
        assertNull(atM100Band)
        assertEquals(AnnouncementStageKind.FINAL, finalSelection?.stage?.kind)
    }

    @Test
    fun `route 途中から開始すると背後の遠い予告は鳴らず現在の距離帯の予告が選ばれる`() {
        val selector = VoiceAnnouncementSelector(VoiceAnnouncementConfig())
        val plan = planOf(
            targetOf(
                index = 0,
                geometryMeters = 1_000.0,
                stages = listOf(
                    middleStage("m500", enter = 500.0, exit = 700.0),
                    middleStage("m300", enter = 700.0, exit = 900.0),
                    middleStage("m100", enter = 900.0, exit = 1_000.0),
                ),
            ),
        )

        // 現在地 750m はすでに 500m 帯の窓 [500, 700) を通り過ぎている。300m 帯の予告だけが候補になる。
        val selection = selector.select(plan, tickOf(current = 750.0), emptyState())

        assertEquals(VoiceAnnouncementId("m300"), selection?.stage?.id)
    }

    @Test
    fun `直前段は速度から逆算した手前距離に達した tick で発話する`() {
        val selector = VoiceAnnouncementSelector(VoiceAnnouncementConfig())
        // leadTime 5s × 20m/s = 100m 手前。target 1000m なので fire 境界は 900m。
        val plan = planOf(
            targetOf(
                index = 0,
                geometryMeters = 1_000.0,
                stages = listOf(finalStage(id = "f", triggerGeometryMeters = 990.0)),
            ),
        )

        val beforeBoundary = selector.select(plan, tickOf(current = 890.0, speed = 20.0), emptyState())
        val atBoundary = selector.select(plan, tickOf(current = 910.0, speed = 20.0), emptyState())

        assertNull(beforeBoundary)
        assertEquals(VoiceAnnouncementId("f"), atBoundary?.stage?.id)
        assertEquals(AnnouncementStageKind.FINAL, atBoundary?.stage?.kind)
    }

    @Test
    fun `単一 block の FINAL は名目トリガ距離がリード境界より手前なら名目位置で発話する`() {
        val selector = VoiceAnnouncementSelector(VoiceAnnouncementConfig())
        val plan = planOf(
            targetOf(
                index = 0,
                geometryMeters = 1_000.0,
                stages = listOf(finalStage(id = "f500", triggerGeometryMeters = 500.0)),
            ),
        )

        val beforeNominalTrigger = selector.select(plan, tickOf(current = 490.0, speed = 20.0), emptyState())
        val atNominalTrigger = selector.select(plan, tickOf(current = 510.0, speed = 20.0), emptyState())

        assertNull(beforeNominalTrigger)
        assertEquals(VoiceAnnouncementId("f500"), atNominalTrigger?.stage?.id)
        assertEquals(AnnouncementStageKind.FINAL, atNominalTrigger?.stage?.kind)
    }

    @Test
    fun `速度が無い直前段は最小手前距離で発話する`() {
        val selector = VoiceAnnouncementSelector(VoiceAnnouncementConfig())
        // 速度なし → minLead 30m 手前。target 1000m なので fire 境界は 970m。
        val plan = planOf(
            targetOf(
                index = 0,
                geometryMeters = 1_000.0,
                stages = listOf(finalStage(id = "f", triggerGeometryMeters = 990.0)),
            ),
        )

        val beforeBoundary = selector.select(plan, tickOf(current = 965.0, speed = null), emptyState())
        val atBoundary = selector.select(plan, tickOf(current = 975.0, speed = null), emptyState())

        assertNull(beforeBoundary)
        assertEquals(VoiceAnnouncementId("f"), atBoundary?.stage?.id)
    }

    @Test
    fun `現在地が属する距離帯の中間段だけが候補になる`() {
        val selector = VoiceAnnouncementSelector(VoiceAnnouncementConfig())
        // 2km 帯 [3000, 4000) と 1km 帯 [4000, 4900) の予告。
        val plan = planOf(
            targetOf(
                index = 0,
                geometryMeters = 5_000.0,
                stages = listOf(
                    middleStage("m3000", enter = 3_000.0, exit = 4_000.0),
                    middleStage("m4000", enter = 4_000.0, exit = 4_900.0),
                ),
            ),
        )

        val inFarBand = selector.select(plan, tickOf(current = 3_500.0), emptyState())
        val inNearBand = selector.select(plan, tickOf(current = 4_200.0), emptyState())

        // 距離窓は非重複なので、現在地が入っている帯の段だけが選ばれる。
        assertEquals(VoiceAnnouncementId("m3000"), inFarBand?.stage?.id)
        assertEquals(VoiceAnnouncementId("m4000"), inNearBand?.stage?.id)
    }

    @Test
    fun `中間段を1つ採ったら同一案内地点の他の中間段はグループ消費で蒸し返さない`() {
        val selector = VoiceAnnouncementSelector(VoiceAnnouncementConfig())
        val plan = planOf(
            targetOf(
                index = 0,
                geometryMeters = 5_000.0,
                stages = listOf(
                    middleStage("m3000", enter = 3_000.0, exit = 4_000.0),
                    middleStage("m4000", enter = 4_000.0, exit = 4_900.0),
                ),
            ),
        )

        // 2km 帯で m3000 を採る。
        val firstTick = selector.select(plan, tickOf(current = 3_500.0), emptyState())
        // m3000 を処理済みにすると、1km 帯に入っても m4000 は鳴らない (グループ消費)。
        val afterConsumed = selector.select(plan, tickOf(current = 4_200.0), emptyState().withStageFired(VoiceAnnouncementId("m3000")))

        assertEquals(VoiceAnnouncementId("m3000"), firstTick?.stage?.id)
        assertNull(afterConsumed)
    }

    @Test
    fun `同一グループで具体候補と汎用候補が同時に窓内なら具体候補を選ぶ`() {
        val selector = VoiceAnnouncementSelector(VoiceAnnouncementConfig())
        // 同一 group "grp131" に汎用「まもなく」(窓 [700,1000)) と具体「200m先」(窓 [800,1000))。
        val plan = planOf(
            targetOf(
                index = 0,
                geometryMeters = 1_000.0,
                stages = listOf(
                    middleStage("soon", enter = 700.0, exit = 1_000.0, groupKey = "grp131", isGeneric = true),
                    middleStage("approx", enter = 800.0, exit = 1_000.0, groupKey = "grp131", isGeneric = false),
                ),
            ),
        )

        // 両方の窓に入る 850m。参照実装の汎用回避で具体「200m先」(approx) を採る。
        val bothActive = selector.select(plan, tickOf(current = 850.0), emptyState())
        // 具体候補の窓外 (750m) では汎用「まもなく」しか無いのでそれを採る。
        val onlyGenericActive = selector.select(plan, tickOf(current = 750.0), emptyState())

        assertEquals(VoiceAnnouncementId("approx"), bothActive?.stage?.id)
        assertEquals(VoiceAnnouncementId("soon"), onlyGenericActive?.stage?.id)
    }

    @Test
    fun `同一グループの MIDDLE が発話済みなら FINAL も消費されて鳴らない`() {
        val selector = VoiceAnnouncementSelector(VoiceAnnouncementConfig())
        // 直前グループ "grp65" に、近接窓の MIDDLE と FINAL が同居するケース (group_id 共有)。
        val plan = planOf(
            targetOf(
                index = 0,
                geometryMeters = 1_000.0,
                stages = listOf(
                    middleStage("directMiddle", enter = 930.0, exit = 980.0, groupKey = "grp65"),
                    finalStage("directFinal", groupKey = "grp65"),
                ),
            ),
        )

        // MIDDLE 未処理なら FINAL は到達リードで鳴れる (緊急度で FINAL が勝つ)。
        val beforeConsume = selector.select(plan, tickOf(current = 975.0), emptyState())
        // 同一グループの MIDDLE が処理済みになると、同 groupKey の FINAL も消費されて鳴らない (1 グループ 1 発話)。
        val afterConsume = selector.select(plan, tickOf(current = 975.0), emptyState().withStageFired(VoiceAnnouncementId("directMiddle")))

        assertEquals(VoiceAnnouncementId("directFinal"), beforeConsume?.stage?.id)
        assertNull(afterConsume)
    }

    @Test
    fun `最寄り FINAL が同グループ消費で鳴らなくても後続 target は区切り済みとして解禁される`() {
        val selector = VoiceAnnouncementSelector(VoiceAnnouncementConfig())
        // target0 の直前グループ "grp65" に MIDDLE と最寄り FINAL が同居 (FINAL が nearest = triggerGeo 最大、本番同様)。
        // 後続 target1 の予告窓は target0 通過より手前で開く。
        val plan = planOf(
            targetOf(
                index = 0,
                geometryMeters = 1_000.0,
                stages = listOf(
                    middleStage("aDirectMiddle", enter = 930.0, exit = 980.0, groupKey = "grp65"),
                    finalStage("aFinal", groupKey = "grp65", triggerGeometryMeters = 970.0),
                ),
            ),
            targetOf(
                index = 1,
                geometryMeters = 2_000.0,
                stages = listOf(middleStage("bEarly", enter = 900.0, exit = 1_100.0, groupKey = "grp131")),
            ),
        )

        // target0 の MIDDLE が処理済み = 直前グループ消費。最寄り FINAL は消費で鳴らない (fired にならない) が、
        // target0 は区切り済み扱いになり、target0 通過前でも target1 の予告が解禁される。
        val consumed = emptyState().withStageFired(VoiceAnnouncementId("aDirectMiddle"))
        val selection = selector.select(plan, tickOf(current = 950.0), consumed)

        assertEquals(VoiceAnnouncementId("bEarly"), selection?.stage?.id)
    }

    @Test
    fun `debug preview は次の発話境界までの残距離と route 順ゲート状態を返す`() {
        val selector = VoiceAnnouncementSelector(VoiceAnnouncementConfig())
        val plan = planOf(
            targetOf(
                index = 0,
                geometryMeters = 1_000.0,
                stages = listOf(middleStage("first", enter = 500.0, exit = 700.0)),
            ),
            targetOf(
                index = 1,
                geometryMeters = 2_000.0,
                stages = listOf(middleStage("second", enter = 800.0, exit = 1_000.0)),
            ),
        )

        val previews = selector.previewUpcoming(
            plan = plan,
            tick = tickOf(current = 300.0),
            state = emptyState(),
            limit = 2,
        )

        assertEquals(2, previews.size)
        assertEquals(VoiceAnnouncementId("first"), previews[0].selection.stage.id)
        assertEquals(200.0, previews[0].remainingMeters)
        assertFalse(previews[0].isRouteOrderBlocked)
        assertEquals(VoiceAnnouncementId("second"), previews[1].selection.stage.id)
        assertEquals(500.0, previews[1].remainingMeters)
        assertTrue(previews[1].isRouteOrderBlocked)
    }

    @Test
    fun `debug preview は未来境界でも汎用候補より具体候補を優先する`() {
        val selector = VoiceAnnouncementSelector(VoiceAnnouncementConfig())
        val plan = planOf(
            targetOf(
                index = 0,
                geometryMeters = 1_000.0,
                stages = listOf(
                    middleStage("generic", enter = 500.0, exit = 700.0, groupKey = "grp", isGeneric = true),
                    middleStage("specific", enter = 500.0, exit = 700.0, groupKey = "grp", isGeneric = false),
                ),
            ),
        )

        val previews = selector.previewUpcoming(
            plan = plan,
            tick = tickOf(current = 300.0),
            state = emptyState(),
            limit = 1,
        )

        assertEquals(VoiceAnnouncementId("specific"), previews.single().selection.stage.id)
        assertEquals(200.0, previews.single().remainingMeters)
    }

    @Test
    fun `別グループの FINAL は MIDDLE のグループ消費に影響されない`() {
        val selector = VoiceAnnouncementSelector(VoiceAnnouncementConfig())
        // 予告グループ "grp131" の MIDDLE と、別グループ "grp65" の直前 FINAL。
        val plan = planOf(
            targetOf(
                index = 0,
                geometryMeters = 1_000.0,
                stages = listOf(
                    middleStage("predict", enter = 500.0, exit = 800.0, groupKey = "grp131"),
                    finalStage("final", groupKey = "grp65"),
                ),
            ),
        )

        // 予告 (grp131) を消費しても、別グループの FINAL (grp65) は到達リードで鳴る。
        val consumed = emptyState().withStageFired(VoiceAnnouncementId("predict"))
        val finalSelection = selector.select(plan, tickOf(current = 975.0), consumed)

        assertEquals(VoiceAnnouncementId("final"), finalSelection?.stage?.id)
    }

    @Test
    fun `別グループの汎用候補は汎用回避で消されない`() {
        val selector = VoiceAnnouncementSelector(VoiceAnnouncementConfig())
        // 具体候補と汎用候補が別グループなら回避対象外 (回避は同一グループ内だけ)。
        val plan = planOf(
            targetOf(
                index = 0,
                geometryMeters = 1_000.0,
                stages = listOf(
                    middleStage("genericFar", enter = 500.0, exit = 700.0, groupKey = "grpDefault#far", isGeneric = true),
                ),
            ),
        )

        // 別グループの単独汎用候補は窓内なら鳴る。
        val selection = selector.select(plan, tickOf(current = 600.0), emptyState())

        assertEquals(VoiceAnnouncementId("genericFar"), selection?.stage?.id)
    }

    @Test
    fun `同時に複数 target が鳴りたい tick では最も近い案内地点が選ばれる`() {
        val selector = VoiceAnnouncementSelector(VoiceAnnouncementConfig())
        val plan = planOf(
            targetOf(
                index = 0,
                geometryMeters = 500.0,
                stages = listOf(middleStage("near", enter = 400.0, exit = 500.0)),
            ),
            targetOf(
                index = 1,
                geometryMeters = 1_000.0,
                stages = listOf(middleStage("far", enter = 400.0, exit = 1_000.0)),
            ),
        )

        val selection = selector.select(plan, tickOf(current = 450.0), emptyState())

        assertEquals(0, selection?.targetIndex)
        assertEquals(VoiceAnnouncementId("near"), selection?.stage?.id)
    }

    @Test
    fun `手前の案内地点が未発話なら後続地点の段は発話せず 区切り後に解禁される`() {
        val selector = VoiceAnnouncementSelector(VoiceAnnouncementConfig())
        // 手前 A (FINAL のみ) と、1km 級手前から窓が始まる後続 B の予告。
        val plan = planOf(
            targetOf(index = 0, geometryMeters = 500.0, stages = listOf(finalStage("aFinal"))),
            targetOf(index = 1, geometryMeters = 2_000.0, stages = listOf(middleStage("bEarly", enter = 100.0, exit = 2_000.0))),
        )

        // A はまだ直前距離に達せず未発話・未通過。B の予告は窓内だが、手前 A 未区切りなので持ち越し。
        val blockedByEarlier = selector.select(plan, tickOf(current = 150.0), emptyState())
        // A の FINAL が発話済みになれば B が解禁される。
        val afterFinal = selector.select(plan, tickOf(current = 150.0), emptyState().withStageFired(VoiceAnnouncementId("aFinal")))
        // A を通過済みにしても B が解禁される。
        val afterPassed = selector.select(plan, tickOf(current = 150.0), emptyState().withTargetPassed(0))

        assertNull(blockedByEarlier)
        assertEquals(VoiceAnnouncementId("bEarly"), afterFinal?.stage?.id)
        assertEquals(VoiceAnnouncementId("bEarly"), afterPassed?.stage?.id)
    }

    @Test
    fun `合成 fixture では遠距離予告窓が先行 target の最寄り窓より前なら先に発話される`() {
        val selector = VoiceAnnouncementSelector(VoiceAnnouncementConfig())
        val routeMeters = 60_000.0
        val mergeNoticeId = VoiceAnnouncementId("mergeNotice")
        val farJunctionAdvanceId = VoiceAnnouncementId("farJunctionAdvance")
        val plan = planOf(
            targetOf(
                index = 0,
                geometryMeters = geometryFromRemaining(routeMeters, remainingMeters = 55_321.0),
                stages = listOf(
                    middleStageFromRemaining(
                        id = mergeNoticeId.value,
                        routeMeters = routeMeters,
                        enterRemainingMeters = 55_721.0,
                        exitRemainingMeters = 55_521.0,
                        groupKey = "mergeGroup",
                    ),
                ),
            ),
            targetOf(
                index = 1,
                geometryMeters = geometryFromRemaining(routeMeters, remainingMeters = 55_042.0),
                stages = listOf(
                    middleStageFromRemaining(
                        id = farJunctionAdvanceId.value,
                        routeMeters = routeMeters,
                        enterRemainingMeters = 58_092.0,
                        exitRemainingMeters = 57_742.0,
                        groupKey = "junctionGroup",
                    ),
                ),
            ),
        )

        val selectedStageIds = simulateSelections(
            selector = selector,
            plan = plan,
            currentCumulativeMetersList = listOf(
                geometryFromRemaining(routeMeters, remainingMeters = 58_000.0),
                geometryFromRemaining(routeMeters, remainingMeters = 55_650.0),
            ),
        )

        assertEquals(listOf(farJunctionAdvanceId, mergeNoticeId), selectedStageIds)
    }

    @Test
    fun `近接チェーンの重複窓は先行 target が未発話なら後続 target を通さない`() {
        val selector = VoiceAnnouncementSelector(VoiceAnnouncementConfig())
        val firstNearId = VoiceAnnouncementId("firstNear")
        val secondNearId = VoiceAnnouncementId("secondNear")
        val plan = planOf(
            targetOf(
                index = 0,
                geometryMeters = 1_000.0,
                stages = listOf(
                    middleStage(
                        id = firstNearId.value,
                        enter = 900.0,
                        exit = 980.0,
                        groupKey = "firstGroup",
                    ),
                ),
            ),
            targetOf(
                index = 1,
                geometryMeters = 1_100.0,
                stages = listOf(
                    middleStage(
                        id = secondNearId.value,
                        enter = 850.0,
                        exit = 950.0,
                        groupKey = "secondGroup",
                    ),
                ),
            ),
        )

        val blockedBeforeFirstWindow = selector.select(plan, tickOf(current = 875.0), emptyState())
        val selectedStageIds = simulateSelections(
            selector = selector,
            plan = plan,
            currentCumulativeMetersList = listOf(875.0, 920.0, 930.0),
        )

        assertNull(blockedBeforeFirstWindow)
        assertEquals(listOf(firstNearId, secondNearId), selectedStageIds)
    }

    @Test
    fun `FINAL 段は窓を持たないため互いに素バイパスを使わない`() {
        val selector = VoiceAnnouncementSelector(VoiceAnnouncementConfig())
        val plan = planOf(
            targetOf(
                index = 0,
                geometryMeters = 1_000.0,
                stages = listOf(
                    middleStage(
                        id = "firstNear",
                        enter = 990.0,
                        exit = 1_000.0,
                        groupKey = "firstGroup",
                    ),
                ),
            ),
            targetOf(
                index = 1,
                geometryMeters = 1_010.0,
                stages = listOf(finalStage("secondFinal")),
            ),
        )

        val selection = selector.select(plan, tickOf(current = 985.0), emptyState())

        assertNull(selection)
    }

    @Test
    fun `名目 trigger を持つ窓なし FINAL でも 遠距離予告窓が余白ぶん手前で完結すれば発話される`() {
        val selector = VoiceAnnouncementSelector(VoiceAnnouncementConfig())
        // 手前 A は名目 trigger を持つ単一 block FINAL (合流 350m / カーブ 500m 相当・窓なし)。
        // GP 2000・trigger geo 1650 → 発火帯 enter = min(1650, 2000-30) = 1650。バイパス閾値 = 1650 - 100(margin) = 1550。
        // 後続 B の遠距離予告窓 [1000,1500] は閾値より手前で完結する (disjoint + 余白)。
        val plan = planOf(
            targetOf(
                index = 0,
                geometryMeters = 2_000.0,
                stages = listOf(finalStage("mergeFinal", triggerGeometryMeters = 1_650.0)),
            ),
            targetOf(
                index = 1,
                geometryMeters = 2_300.0,
                stages = listOf(middleStage("farAdvance", enter = 1_000.0, exit = 1_500.0, groupKey = "farGroup")),
            ),
        )

        // A 未発話・未通過でも、窓が余白込みで disjoint なので B の遠距離予告が解禁される (issue #90 の核心)。
        val selection = selector.select(plan, tickOf(current = 1_200.0), emptyState())

        assertEquals(VoiceAnnouncementId("farAdvance"), selection?.stage?.id)
    }

    @Test
    fun `遠距離予告窓が先行 FINAL 発火帯の手前余白に食い込む場合はゲートされる`() {
        val selector = VoiceAnnouncementSelector(VoiceAnnouncementConfig())
        // A: GP 2000・名目 trigger geo 1650 → 発火帯 enter = 1650。バイパス閾値 = 1650 - 100(margin) = 1550。
        // B の予告窓終端 1600 は発火帯 (1650) より手前だが余白 (1550) には食い込む → barge-in 回避のためゲート。
        // finalFireBoundaryMeters が名目 trigger(1650) でなくリード境界(1970)を返すと誤って解禁されるため、結合も固定する。
        val plan = planOf(
            targetOf(
                index = 0,
                geometryMeters = 2_000.0,
                stages = listOf(finalStage("mergeFinal", triggerGeometryMeters = 1_650.0)),
            ),
            targetOf(
                index = 1,
                geometryMeters = 2_300.0,
                stages = listOf(middleStage("farAdvance", enter = 1_000.0, exit = 1_600.0, groupKey = "farGroup")),
            ),
        )

        val blocked = selector.select(plan, tickOf(current = 1_300.0), emptyState())
        val afterPassed = selector.select(plan, tickOf(current = 1_300.0), emptyState().withTargetPassed(0))

        assertNull(blocked)
        assertEquals(VoiceAnnouncementId("farAdvance"), afterPassed?.stage?.id)
    }

    @Test
    fun `先行 FINAL の発火帯は速度リードで広がり 同じ予告窓でもゲート結果が変わる`() {
        val selector = VoiceAnnouncementSelector(VoiceAnnouncementConfig())
        // A(GP2000) は名目 trigger 無し → 発火帯 enter は速度リードで決まる。バイパス閾値 = enter - 100(margin)。
        //   速度なし   : enter = 2000 - 30(min lead)       = 1970 → 閾値 1870 → 予告窓終端 1860 <= 1870 で発話可
        //   速度 30m/s : enter = 2000 - max(30×5, 30)=150 = 1850 → 閾値 1750 → 1860 <= 1750 が偽でゲート
        val plan = planOf(
            targetOf(index = 0, geometryMeters = 2_000.0, stages = listOf(finalStage("mergeFinal"))),
            targetOf(
                index = 1,
                geometryMeters = 2_300.0,
                stages = listOf(middleStage("farAdvance", enter = 1_500.0, exit = 1_860.0, groupKey = "farGroup")),
            ),
        )

        val lowSpeed = selector.select(plan, tickOf(current = 1_700.0), emptyState())
        val highSpeed = selector.select(plan, tickOf(current = 1_700.0, speed = 30.0), emptyState())

        assertEquals(VoiceAnnouncementId("farAdvance"), lowSpeed?.stage?.id)
        assertNull(highSpeed)
    }

    @Test
    fun `先行 target が近接 MIDDLE 窓を持つ場合は FINAL 境界でなく MIDDLE 窓開始でゲートする`() {
        val selector = VoiceAnnouncementSelector(VoiceAnnouncementConfig())
        // 先行 A は近接 MIDDLE [850,900] と FINAL (trigger geo 970) を併せ持つ通常地点。
        // gate enter は最寄り MIDDLE 窓開始 850 (FINAL 境界 970 ではない)。後続 B の窓終端 860 は 850 に重なるため、
        // A が区切られるまでゲートする。FINAL 境界 970 を使うと閾値 870 となり 860<=870 で誤って解禁される。
        val plan = planOf(
            targetOf(
                index = 0,
                geometryMeters = 1_000.0,
                stages = listOf(
                    middleStage("aNear", enter = 850.0, exit = 900.0, groupKey = "aGrp"),
                    finalStage("aFinal", triggerGeometryMeters = 970.0),
                ),
            ),
            targetOf(
                index = 1,
                geometryMeters = 1_100.0,
                stages = listOf(middleStage("bAdvance", enter = 800.0, exit = 860.0, groupKey = "bGrp")),
            ),
        )

        val blocked = selector.select(plan, tickOf(current = 830.0), emptyState())
        val afterAnnounced = selector.select(
            plan = plan,
            tick = tickOf(current = 830.0),
            state = emptyState().withStageFired(VoiceAnnouncementId("aFinal")),
        )

        assertNull(blocked)
        assertEquals(VoiceAnnouncementId("bAdvance"), afterAnnounced?.stage?.id)
    }

    @Test
    fun `route が発話不能なら何も選ばない`() {
        val selector = VoiceAnnouncementSelector(VoiceAnnouncementConfig())
        val plan = planOf(
            targetOf(
                index = 0,
                geometryMeters = 1_000.0,
                stages = listOf(middleStage("m800", enter = 800.0, exit = 1_000.0)),
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
                stages = listOf(middleStage("m800", enter = 800.0, exit = 1_000.0)),
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
                stages = listOf(middleStage("m800", enter = 800.0, exit = 1_000.0)),
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

    private fun simulateSelections(
        selector: VoiceAnnouncementSelector,
        plan: VoiceAnnouncementPlan,
        currentCumulativeMetersList: List<Double>,
    ): List<VoiceAnnouncementId> {
        var state = emptyState()
        val selectedStageIds = mutableListOf<VoiceAnnouncementId>()

        for (currentCumulativeMeters in currentCumulativeMetersList) {
            val tick = tickOf(current = currentCumulativeMeters)
            val passedTargetIndices = selector.passedTargetIndices(plan, tick)

            for (passedTargetIndex in passedTargetIndices) {
                state = state.withTargetPassed(passedTargetIndex)
            }

            val selection = selector.select(plan, tick, state) ?: continue
            selectedStageIds += selection.stage.id
            state = state.withStageFired(selection.stage.id)
        }

        return selectedStageIds
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
    ): AnnouncementTarget =
        AnnouncementTarget(
            guidancePointIndex = index,
            geometryMeters = geometryMeters,
            stages = stages.toImmutableList(),
        )

    private fun middleStageFromRemaining(
        id: String,
        routeMeters: Double,
        enterRemainingMeters: Double,
        exitRemainingMeters: Double,
        groupKey: String,
    ): AnnouncementStage {
        val enterGeometryMeters = geometryFromRemaining(routeMeters, enterRemainingMeters)
        val exitGeometryMeters = geometryFromRemaining(routeMeters, exitRemainingMeters)

        return middleStage(
            id = id,
            enter = enterGeometryMeters,
            exit = exitGeometryMeters,
            groupKey = groupKey,
        )
    }

    private fun geometryFromRemaining(routeMeters: Double, remainingMeters: Double): Double = routeMeters - remainingMeters

    // 同一案内地点の距離違い候補は既定で同一 groupKey に束ね、グループ消費 (1 グループ 1 発話) を再現する。
    private fun middleStage(
        id: String,
        enter: Double,
        exit: Double,
        groupKey: String = "grp",
        isGeneric: Boolean = false,
    ): AnnouncementStage =
        stageOf(
            id = id,
            kind = AnnouncementStageKind.MIDDLE,
            triggerGeometryMeters = enter,
            groupKey = groupKey,
            window = AnnouncementDistanceWindow(enterGeometryMeters = enter, exitGeometryMeters = exit),
            isGeneric = isGeneric,
        )

    private fun finalStage(
        id: String,
        groupKey: String = "final-grp",
        triggerGeometryMeters: Double = Double.POSITIVE_INFINITY,
    ): AnnouncementStage =
        stageOf(id, AnnouncementStageKind.FINAL, triggerGeometryMeters = triggerGeometryMeters, groupKey = groupKey, window = null)

    private fun stageOf(
        id: String,
        kind: AnnouncementStageKind,
        triggerGeometryMeters: Double,
        groupKey: String,
        window: AnnouncementDistanceWindow?,
        isGeneric: Boolean = false,
    ): AnnouncementStage = AnnouncementStage(
        id = VoiceAnnouncementId(id),
        groupKey = VoiceAnnouncementId(groupKey),
        kind = kind,
        triggerSourceMeters = triggerGeometryMeters,
        triggerGeometryMeters = triggerGeometryMeters,
        middleWindow = window,
        isGeneric = isGeneric,
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
