package me.matsumo.onenavi.core.navigation.server

import me.matsumo.drive.supporter.api.guidance.domain.GuidanceCategory
import me.matsumo.drive.supporter.api.route.domain.CarPriority
import me.matsumo.onenavi.core.model.CongestionSeverity
import me.matsumo.onenavi.core.model.RouteIncidentMarkerCategory
import me.matsumo.onenavi.core.model.RoutePriority
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [RouteGuidanceMapper] の server DTO 詰め替え契約テスト。
 */
class RouteGuidanceMapperTest {

    @Test
    fun `routePackageId を RouteDetail id と registry payload id にコピーする`() {
        val mappings = RouteGuidanceMapper().map(
            response = routePlanResponse(),
            origin = ORIGIN,
            destination = DESTINATION,
            intermediateWaypoints = listOf(VIA),
        )

        val mapping = mappings.single()
        val routeResult = mapping.routeResult
        val payload = mapping.payload

        assertEquals(ROUTE_PACKAGE_ID, routeResult.detail.id)
        assertEquals(ROUTE_PACKAGE_ID, payload.id)
        assertEquals(CarPriority.AvoidCongestion, payload.routeGuidance.priority)
        assertEquals(RoutePriority.AvoidCongestion, routeResult.detail.priority)
        assertEquals(RoutePriority.AvoidCongestion.label, routeResult.item.priorityLabel)
        assertEquals(listOf(VIA.toRoutePoint()), routeResult.detail.intermediateWaypoints)
        assertTrue(routeResult.detail.pointEvents.isEmpty())
        assertTrue(payload.sapaDetailsByName.isEmpty())
    }

    @Test
    fun `traffic と incident と toll details を RouteDetail へ詰め替える`() {
        val mapping = RouteGuidanceMapper().map(
            response = routePlanResponse(),
            origin = ORIGIN,
            destination = DESTINATION,
            intermediateWaypoints = emptyList(),
        ).single()

        val routeDetail = mapping.routeResult.detail
        val routeItem = mapping.routeResult.item
        val congestionSegment = routeDetail.congestionSegments.single()
        val incident = routeDetail.routeIncidents.single()
        val tollDetail = routeDetail.tollDetails.single()

        assertEquals(CongestionSeverity.SLOW, congestionSegment.severity)
        assertEquals(0, congestionSegment.startPolylinePointIndex)
        assertEquals(1, congestionSegment.endPolylinePointIndex)
        assertEquals(0.0, congestionSegment.startDistanceMeters)
        assertEquals(1_000.0, congestionSegment.endDistanceMeters)
        assertEquals("混雑", congestionSegment.headPointName)
        assertEquals(routeDetail.congestionSegments, routeItem.congestionSegments)
        assertEquals(RouteIncidentMarkerCategory.Regulation, incident.category)
        assertEquals("車線規制", incident.displayText)
        assertEquals("テスト区間", incident.placeName)
        assertEquals("テスト道路", incident.roadNumbering)
        assertEquals("首都高速道路", tollDetail.roadName)
        assertEquals(1_230, tollDetail.amount)
        assertEquals(1_230, routeDetail.tollFee)
        assertEquals(1_230, routeItem.tollFee)
        assertEquals(1_230, mapping.payload.routeGuidance.summary.tollDetails.single().amount)
    }

    @Test
    fun `guidancePoints と announcementBlocks を既存消費モデルへ詰め替える`() {
        val mappings = RouteGuidanceMapper().map(
            response = routePlanResponse(),
            origin = ORIGIN,
            destination = DESTINATION,
            intermediateWaypoints = emptyList(),
        )

        val guidancePoint = mappings
            .single()
            .payload
            .routeGuidance
            .guidancePoints
            .single()
        val announcementBlock = guidancePoint.announcementBlocks.single()
        val announcementPiece = announcementBlock.pieces.single()
        val phrase = guidancePoint.phrases.single()

        assertEquals(0, guidancePoint.gpType)
        assertEquals(7, guidancePoint.index)
        assertEquals(120, guidancePoint.distanceFromPrevMetres)
        assertEquals(480, guidancePoint.distanceFromStartMetres)
        assertTrue(guidancePoint.imageRefs.isEmpty())
        assertNull(guidancePoint.maneuver)
        assertEquals("block-1", announcementBlock.id)
        assertEquals(480.0, assertNotNull(announcementBlock.anchor.sourceDistanceFromStartMetres))
        assertNull(announcementBlock.anchor.sourceGuidancePointIndex)
        assertNull(announcementBlock.anchor.sourceBlockIndex)
        assertEquals(300, announcementBlock.triggerDistanceMetres)
        assertEquals(42, announcementBlock.groupId)
        assertEquals(250, assertNotNull(announcementBlock.window).nearMetres)
        assertEquals(400, assertNotNull(announcementBlock.window).farMetres)
        assertEquals(setOf(GuidanceCategory.IntersectionGuide), announcementBlock.categories)
        assertEquals("およそ300m先", announcementPiece.text)
        assertEquals("<phoneme>およそ300m先</phoneme>", announcementPiece.ssml)
        assertEquals(1000, announcementPiece.templateRef)
        assertEquals(GuidanceCategory.IntersectionGuide, announcementPiece.category)
        assertEquals("<speak>debug</speak>", phrase.ssml)
        assertEquals(300, phrase.distanceMetres)
        assertEquals(GuidanceCategory.IntersectionGuide, phrase.category)
    }

    @Test
    fun `pointEvents と imageRefs と sapaDetails は S1 では空として扱う`() {
        val mapping = RouteGuidanceMapper().map(
            response = routePlanResponse(),
            origin = ORIGIN,
            destination = DESTINATION,
            intermediateWaypoints = emptyList(),
        ).single()

        assertTrue(mapping.routeResult.detail.pointEvents.isEmpty())
        assertTrue(mapping.payload.routeGuidance.pointEvents.isEmpty())
        assertTrue(mapping.payload.routeGuidance.imageIds.isEmpty())
        assertTrue(mapping.payload.sapaDetailsByName.isEmpty())
    }

    private fun routePlanResponse(): RoutePlanResponseDto =
        RoutePlanResponseDto(
            candidates = listOf(routeCandidate()),
        )

    private fun routeCandidate(): RouteCandidateDto =
        RouteCandidateDto(
            routePackageId = ROUTE_PACKAGE_ID,
            priority = RoutePriorityDto.AVOID_CONGESTION,
            mergedFromPriorities = listOf(RoutePriorityDto.AVOID_CONGESTION),
            geometry = listOf(ORIGIN, DESTINATION),
            polyline = "encoded-polyline",
            summary = RouteSummaryDto(
                durationSeconds = 900,
                baseDurationSeconds = 1_000,
                typicalDurationSeconds = 950,
                lengthMetres = 12_345,
            ),
            tollFee = RouteTollFeeDto(
                currency = "JPY",
                amount = 1_230.0,
            ),
            tollDetails = listOf(
                RouteTollDetailDto(
                    sectionIndex = 0,
                    tollIndex = 0,
                    fareIndex = 0,
                    tollSystem = "首都高速道路",
                    convertedPrice = RouteTollPriceDto(
                        currency = "JPY",
                        amount = 1_230.0,
                    ),
                ),
            ),
            congestionSegments = listOf(
                RouteCongestionSegmentDto(
                    id = "congestion-1",
                    startMeasureMetres = 0.0,
                    endMeasureMetres = 1_000.0,
                    level = RouteCongestionLevelDto.CROWDED,
                    source = "HERE",
                    displayText = "混雑",
                ),
            ),
            routeIncidents = listOf(
                RouteIncidentDto(
                    id = "incident-1",
                    startMeasureMetres = 400.0,
                    endMeasureMetres = 600.0,
                    category = RouteIncidentCategoryDto.REGULATION,
                    kind = RouteIncidentKindDto.LANE_RESTRICTION,
                    displayText = "車線規制",
                    roadNames = listOf("テスト道路"),
                    sectionName = "テスト区間",
                    source = "JARTIC",
                ),
            ),
            guidancePoints = listOf(guidancePoint()),
            maneuvers = listOf(
                RouteManeuverDto(
                    id = "maneuver-1",
                    type = RouteManeuverTypeDto.TURN,
                    rawAction = "turn",
                    routeMeasureMetres = 480.0,
                ),
            ),
        )

    private fun guidancePoint(): GuidancePointDto =
        GuidancePointDto(
            index = 7,
            distanceFromStartMetres = 480,
            distanceFromPrevMetres = 120,
            maneuverRefId = "maneuver-1",
            announcementBlocks = listOf(announcementBlock()),
            phrases = listOf(
                SsmlPhraseDto(
                    ssml = "<speak>debug</speak>",
                    distanceMetres = 300,
                    category = GuidanceCategory.IntersectionGuide.id,
                ),
            ),
        )

    private fun announcementBlock(): AnnouncementBlockDto =
        AnnouncementBlockDto(
            id = "block-1",
            anchorMetres = 480.0,
            triggerDistanceMetres = 300,
            groupId = 42,
            window = AnnouncementWindowDto(
                nearMetres = 250,
                farMetres = 400,
            ),
            pieces = listOf(
                AnnouncementPieceDto(
                    text = "およそ300m先",
                    ssml = "<phoneme>およそ300m先</phoneme>",
                    templateRef = 1000,
                    category = GuidanceCategory.IntersectionGuide.id,
                ),
            ),
            hasBlankAnnouncementSlot = false,
            categories = listOf(GuidanceCategory.IntersectionGuide.id),
        )

    private fun RouteCoordinateDto.toRoutePoint() =
        me.matsumo.onenavi.core.model.RoutePoint(
            latitude = latitude,
            longitude = longitude,
        )

    private companion object {
        /** server が候補へ付与した route package ID。 */
        const val ROUTE_PACKAGE_ID: String = "server-route-package-1"

        /** テスト origin 座標。 */
        val ORIGIN = RouteCoordinateDto(
            latitude = 35.0,
            longitude = 139.0,
        )

        /** テスト destination 座標。 */
        val DESTINATION = RouteCoordinateDto(
            latitude = 35.01,
            longitude = 139.01,
        )

        /** テスト経由地座標。 */
        val VIA = RouteCoordinateDto(
            latitude = 35.005,
            longitude = 139.005,
        )
    }
}
