package me.matsumo.onenavi.core.navigation.newguidance.presentation

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import me.matsumo.onenavi.core.model.ManeuverModifier
import me.matsumo.onenavi.core.model.ManeuverType
import me.matsumo.onenavi.core.model.RoadClass
import me.matsumo.onenavi.core.model.RouteDetail
import me.matsumo.onenavi.core.model.RoutePoint
import me.matsumo.onenavi.core.navigation.extnav.RouteGeometryMath
import me.matsumo.onenavi.core.navigation.newguidance.progress.GuidanceRouteSelector
import me.matsumo.onenavi.core.navigation.newguidance.progress.RouteProjectionContext
import me.matsumo.onenavi.core.navigation.newguidance.semantic.FacilityKind
import me.matsumo.onenavi.core.navigation.newguidance.semantic.FacilityServiceKind
import me.matsumo.onenavi.core.navigation.newguidance.semantic.GuidanceEvent
import me.matsumo.onenavi.core.navigation.newguidance.semantic.GuidanceEventDetails
import me.matsumo.onenavi.core.navigation.newguidance.semantic.GuidanceEventId
import me.matsumo.onenavi.core.navigation.newguidance.semantic.GuidanceLane
import me.matsumo.onenavi.core.navigation.newguidance.semantic.GuidanceManeuver
import me.matsumo.onenavi.core.navigation.newguidance.semantic.GuidanceRoute
import me.matsumo.onenavi.core.navigation.newguidance.semantic.GuideImageKey
import me.matsumo.onenavi.core.navigation.newguidance.semantic.LaneConfidence
import me.matsumo.onenavi.core.navigation.newguidance.semantic.LaneLayout
import me.matsumo.onenavi.core.navigation.newguidance.semantic.LaneMark
import me.matsumo.onenavi.core.navigation.newguidance.semantic.LaneSource
import me.matsumo.onenavi.core.navigation.newguidance.semantic.RouteAnchor
import me.matsumo.onenavi.core.navigation.newguidance.semantic.StepFacility
import me.matsumo.onenavi.core.navigation.newguidance.semantic.StepFacilityService
import me.matsumo.onenavi.core.navigation.newguidance.semantic.StepSignpost
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [GuidancePresentationProjector] の semantic → presentation 射影テスト。
 */
class GuidancePresentationProjectorTest {

    private val selector = GuidanceRouteSelector()
    private val projector = GuidancePresentationProjector()

    @Test
    fun `通過施設と主案内をリスト行へ射影し到着は除外する`() {
        val guidanceRoute = buildRoute()
        val context = buildContext()

        val presentation = project(guidanceRoute = guidanceRoute, context = context)

        assertEquals(ManeuverType.TURN, presentation.nextManeuver?.type)
        assertEquals(2, presentation.nextManeuver?.guidancePointIndex)
        assertEquals(ManeuverType.ARRIVE, presentation.followupManeuver?.type)

        // 到着は除外。残るのは主案内 (turn) と通過施設 (料金所) の 2 件で距離の降順。
        assertEquals(2, presentation.listItems.size)

        val maneuverItem = presentation.listItems[0]
        assertEquals("maneuver-2", maneuverItem.id)
        assertIs<GuidanceListIcon.Maneuver>(maneuverItem.icon)

        val tollItem = presentation.listItems[1]
        val tollBadge = assertIs<GuidanceListIcon.FacilityBadge>(tollItem.icon)
        assertEquals(FacilityKind.TOLL_GATE, tollBadge.kind)
        assertEquals(GuidanceListDetail.Toll(amountYen = 320), tollItem.detail)
    }

    @Test
    fun `主案内ラベルとフォローアップがバナーに同時に乗る`() {
        val guidanceRoute = buildRoute()
        val context = buildContext()

        val presentation = project(guidanceRoute = guidanceRoute, context = context)
        val banner = presentation.banner

        assertEquals(ManeuverType.TURN, banner?.primary?.type)
        // レーンが無いので下段はフォローアップ案内になる。
        val support = assertIs<BannerSupport.Followup>(banner?.support)
        assertEquals(ManeuverType.ARRIVE, support.maneuver.type)
        assertTrue(banner?.hasMoreEvents == true)
    }

    @Test
    fun `主案内を持たないレーンイベントはバナー下段に乗らない`() {
        val guidanceRoute = GuidanceRoute(
            totalDistanceMeters = 400.0,
            totalDurationSeconds = 300,
            tollTotalYen = 320,
            events = listOf(
                laneFacilityEvent(id = "event-toll", geometryMeters = 100.0),
                maneuverEvent(id = "event-3", guidancePointIndex = 3, geometryMeters = 300.0, type = ManeuverType.TURN),
                maneuverEvent(id = "event-5", guidancePointIndex = 5, geometryMeters = 400.0, type = ManeuverType.ARRIVE),
            ).toImmutableList(),
        )
        val context = buildContext()

        val presentation = project(guidanceRoute = guidanceRoute, context = context)

        // バナー下段は上段の主案内 event-3 に紐付く補助だけを見るため、手前のレーンは表示しない。
        val support = assertIs<BannerSupport.Followup>(presentation.banner?.support)
        assertEquals(ManeuverType.ARRIVE, support.maneuver.type)
    }

    @Test
    fun `次の主案内に紐付くレーンは主案内の向きでバナー下段に乗る`() {
        val guidanceRoute = GuidanceRoute(
            totalDistanceMeters = 400.0,
            totalDurationSeconds = 300,
            tollTotalYen = null,
            events = listOf(
                maneuverEvent(
                    id = "event-3",
                    guidancePointIndex = 3,
                    geometryMeters = 300.0,
                    type = ManeuverType.TURN,
                    modifier = ManeuverModifier.RIGHT,
                    lane = markerLane(),
                ),
                maneuverEvent(id = "event-5", guidancePointIndex = 5, geometryMeters = 400.0, type = ManeuverType.ARRIVE),
            ).toImmutableList(),
        )
        val context = buildContext()

        val presentation = project(guidanceRoute = guidanceRoute, context = context)

        val support = assertIs<BannerSupport.Lanes>(presentation.banner?.support)
        val visualLanes = assertIs<LanePresentation.VisualLanes>(support.lane)
        val recommendedLane = visualLanes.lanes.first { lane -> lane.isActive }
        assertEquals(ManeuverModifier.RIGHT, recommendedLane.recommendedDirection)
        assertEquals(ManeuverType.ARRIVE, presentation.banner?.followup?.type)
    }

    @Test
    fun `3km 以内の主案内に紐付くレーンはバナー下段に乗る`() {
        val guidanceRoute = GuidanceRoute(
            totalDistanceMeters = 1_400.0,
            totalDurationSeconds = 600,
            tollTotalYen = null,
            events = listOf(
                maneuverEvent(
                    id = "event-3",
                    guidancePointIndex = 3,
                    geometryMeters = 1_200.0,
                    type = ManeuverType.TURN,
                    modifier = ManeuverModifier.RIGHT,
                    lane = markerLane(),
                ),
                maneuverEvent(
                    id = "event-5",
                    guidancePointIndex = 5,
                    geometryMeters = 1_400.0,
                    type = ManeuverType.ARRIVE,
                ),
            ).toImmutableList(),
        )
        val context = buildContext()

        val presentation = project(guidanceRoute = guidanceRoute, context = context)

        assertIs<BannerSupport.Lanes>(presentation.banner?.support)
    }

    @Test
    fun `3km より遠い主案内に紐付くレーンはバナー下段に乗らない`() {
        val guidanceRoute = GuidanceRoute(
            totalDistanceMeters = 3_600.0,
            totalDurationSeconds = 600,
            tollTotalYen = null,
            events = listOf(
                maneuverEvent(
                    id = "event-3",
                    guidancePointIndex = 3,
                    geometryMeters = 3_200.0,
                    type = ManeuverType.TURN,
                    modifier = ManeuverModifier.RIGHT,
                    lane = markerLane(),
                ),
                maneuverEvent(
                    id = "event-5",
                    guidancePointIndex = 5,
                    geometryMeters = 3_600.0,
                    type = ManeuverType.ARRIVE,
                ),
            ).toImmutableList(),
        )
        val context = buildContext()

        val presentation = project(guidanceRoute = guidanceRoute, context = context)

        val support = assertIs<BannerSupport.Followup>(presentation.banner?.support)
        assertEquals(ManeuverType.ARRIVE, support.maneuver.type)
    }

    @Test
    fun `主案内の方面看板画像 key はバナーに乗る`() {
        val imageKey = GuideImageKey(major = 101, minor = 123_456)
        val guidanceRoute = GuidanceRoute(
            totalDistanceMeters = 400.0,
            totalDurationSeconds = 300,
            tollTotalYen = null,
            events = listOf(
                maneuverEvent(
                    id = "event-3",
                    guidancePointIndex = 3,
                    geometryMeters = 300.0,
                    type = ManeuverType.TURN,
                    signpostImageKey = imageKey,
                ),
            ).toImmutableList(),
        )
        val context = buildContext()

        val presentation = project(guidanceRoute = guidanceRoute, context = context)

        assertEquals(imageKey, presentation.banner?.signpostImageKey)
    }

    @Test
    fun `主案内が無ければバナーは null`() {
        val guidanceRoute = GuidanceRoute(
            totalDistanceMeters = 400.0,
            totalDurationSeconds = 300,
            tollTotalYen = 320,
            events = listOf(facilityEvent(id = "event-toll", geometryMeters = 100.0, kind = FacilityKind.TOLL_GATE)).toImmutableList(),
        )
        val context = buildContext()

        val presentation = project(guidanceRoute = guidanceRoute, context = context)

        assertNull(presentation.banner)
        assertNull(presentation.nextManeuver)
        assertEquals(1, presentation.listItems.size)
    }

    @Test
    fun `SA PA 設備サービスはリスト行の detail に乗る`() {
        val services = persistentListOf(
            StepFacilityService(
                kind = FacilityServiceKind.TOILET,
                label = "トイレ",
            ),
            StepFacilityService(
                kind = FacilityServiceKind.ATM,
                label = "ATM",
            ),
        )
        val guidanceRoute = GuidanceRoute(
            totalDistanceMeters = 400.0,
            totalDurationSeconds = 300,
            tollTotalYen = null,
            events = listOf(
                facilityEvent(
                    id = "event-pa",
                    geometryMeters = 100.0,
                    kind = FacilityKind.PA,
                    services = services,
                ),
            ).toImmutableList(),
        )
        val context = buildContext()

        val presentation = project(guidanceRoute = guidanceRoute, context = context)

        assertEquals(GuidanceListDetail.FacilityServices(services = services), presentation.listItems.single().detail)
    }

    private fun project(
        guidanceRoute: GuidanceRoute,
        context: RouteProjectionContext,
    ): GuidancePresentation {
        val selection = selector.select(route = guidanceRoute, currentCumulativeMeters = 0.0)
        return projector.project(
            guidanceRoute = guidanceRoute,
            selection = selection,
            context = context,
            currentCumulativeMeters = 0.0,
            currentRoadClass = RoadClass.ORDINARY,
            currentRoadName = null,
            timestampMillis = 1_000L,
        )
    }

    private fun buildRoute(): GuidanceRoute = GuidanceRoute(
        totalDistanceMeters = 400.0,
        totalDurationSeconds = 300,
        tollTotalYen = 320,
        events = listOf(
            facilityEvent(id = "event-toll", geometryMeters = 100.0, kind = FacilityKind.TOLL_GATE),
            maneuverEvent(id = "event-2", guidancePointIndex = 2, geometryMeters = 200.0, type = ManeuverType.TURN),
            maneuverEvent(id = "event-5", guidancePointIndex = 5, geometryMeters = 400.0, type = ManeuverType.ARRIVE),
        ).toImmutableList(),
    )

    private fun buildContext(): RouteProjectionContext {
        val geometry = listOf(
            RoutePoint(latitude = 0.0, longitude = 0.0),
            RoutePoint(latitude = 0.0, longitude = 0.004),
        )
        val cumulativeMetres = RouteGeometryMath.cumulativeMetres(geometry)
        return RouteProjectionContext(
            route = RouteDetail(
                id = "projector-test",
                origin = geometry.first(),
                destination = geometry.last(),
                intermediateWaypoints = persistentListOf(),
                geometry = geometry.toImmutableList(),
                distanceMeters = 400.0,
                durationSeconds = 300.0,
                steps = persistentListOf(),
            ),
            cumulativeMetres = cumulativeMetres,
            totalGeometryMetres = 400.0,
        )
    }

    private fun maneuverEvent(
        id: String,
        guidancePointIndex: Int,
        geometryMeters: Double,
        type: ManeuverType,
        modifier: ManeuverModifier = ManeuverModifier.STRAIGHT,
        lane: GuidanceLane? = null,
        signpostImageKey: GuideImageKey? = null,
    ): GuidanceEvent = GuidanceEvent(
        id = GuidanceEventId(id),
        anchor = anchorAt(geometryMeters = geometryMeters, guidancePointIndex = guidancePointIndex),
        primary = GuidanceManeuver(
            type = type,
            modifier = modifier,
            intersectionName = null,
            exitNumber = null,
        ),
        details = emptyDetails(facility = null).copy(
            lane = lane,
            signpost = signpostImageKey?.toSignpost(),
        ),
        sourceRefs = persistentListOf(),
    )

    private fun GuideImageKey.toSignpost(): StepSignpost = StepSignpost(
        primary = "東京方面",
        secondary = null,
        imageRef = this,
    )

    private fun facilityEvent(
        id: String,
        geometryMeters: Double,
        kind: FacilityKind,
        services: ImmutableList<StepFacilityService> = persistentListOf(),
    ): GuidanceEvent = GuidanceEvent(
        id = GuidanceEventId(id),
        anchor = anchorAt(geometryMeters = geometryMeters, guidancePointIndex = null),
        primary = null,
        details = emptyDetails(
            facility = StepFacility(kind = kind, name = "料金所", services = services),
        ),
        sourceRefs = persistentListOf(),
    )

    private fun laneFacilityEvent(
        id: String,
        geometryMeters: Double,
    ): GuidanceEvent = GuidanceEvent(
        id = GuidanceEventId(id),
        anchor = anchorAt(geometryMeters = geometryMeters, guidancePointIndex = null),
        primary = null,
        details = GuidanceEventDetails(
            facility = StepFacility(kind = FacilityKind.TOLL_GATE, name = "料金所", services = persistentListOf()),
            lane = markerLane(),
            toll = null,
            signpost = null,
            boundary = null,
            roadName = null,
            notices = persistentListOf(),
        ),
        sourceRefs = persistentListOf(),
    )

    private fun markerLane(): GuidanceLane = GuidanceLane(
        layout = LaneLayout.MarkerLayout(
            lanes = listOf(LaneMark(rawA = 1, rawB = 0), LaneMark(rawA = 0, rawB = 0)).toImmutableList(),
            kind = 0,
        ),
        instruction = null,
        warning = null,
        sources = persistentSetOf(LaneSource.MARKER),
        confidence = LaneConfidence.MEDIUM,
        sourceRefs = persistentListOf(),
    )

    private fun anchorAt(
        geometryMeters: Double,
        guidancePointIndex: Int?,
    ): RouteAnchor = RouteAnchor(
        sourceDistanceFromStartMeters = geometryMeters,
        geometryDistanceFromStartMeters = geometryMeters,
        location = RoutePoint(latitude = 0.0, longitude = 0.0),
        sourceGuidancePointIndex = guidancePointIndex,
        sourceBlockIndex = null,
        matchErrorMeters = null,
    )

    private fun emptyDetails(facility: StepFacility?): GuidanceEventDetails = GuidanceEventDetails(
        facility = facility,
        lane = null,
        toll = null,
        signpost = null,
        boundary = null,
        roadName = null,
        notices = persistentListOf(),
    )
}
