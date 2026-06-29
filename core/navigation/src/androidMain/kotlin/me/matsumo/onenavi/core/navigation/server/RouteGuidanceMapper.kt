package me.matsumo.onenavi.core.navigation.server

import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableSet
import me.matsumo.drive.supporter.api.core.model.Coord
import me.matsumo.drive.supporter.api.guidance.domain.DsrRouteSummary
import me.matsumo.drive.supporter.api.guidance.domain.ExternalGuideAnchor
import me.matsumo.drive.supporter.api.guidance.domain.GuidanceCategory
import me.matsumo.drive.supporter.api.guidance.domain.GuidancePoint
import me.matsumo.drive.supporter.api.guidance.domain.GuideAnnouncementBlock
import me.matsumo.drive.supporter.api.guidance.domain.GuideAnnouncementPiece
import me.matsumo.drive.supporter.api.guidance.domain.GuideAnnouncementWindow
import me.matsumo.drive.supporter.api.guidance.domain.RouteGuidance
import me.matsumo.drive.supporter.api.guidance.domain.SsmlPhrase
import me.matsumo.drive.supporter.api.guidance.domain.StreetSegment
import me.matsumo.drive.supporter.api.guidance.domain.TollDetail
import me.matsumo.onenavi.core.model.CongestionSegment
import me.matsumo.onenavi.core.model.CongestionSegmentSource
import me.matsumo.onenavi.core.model.CongestionSeverity
import me.matsumo.onenavi.core.model.CongestionTrend
import me.matsumo.onenavi.core.model.RouteDetail
import me.matsumo.onenavi.core.model.RouteIncidentMarker
import me.matsumo.onenavi.core.model.RouteIncidentMarkerCategory
import me.matsumo.onenavi.core.model.RouteItem
import me.matsumo.onenavi.core.model.RoutePoint
import me.matsumo.onenavi.core.model.RoutePriority
import me.matsumo.onenavi.core.model.RouteResult
import me.matsumo.onenavi.core.model.RouteStepInfo
import me.matsumo.onenavi.core.model.TollSegmentFee
import me.matsumo.onenavi.core.navigation.extnav.ExtNavRoutePayload
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import me.matsumo.drive.supporter.api.route.domain.CarPriority as ExtNavCarPriority

/**
 * server route response を既存の案内消費モデルへ詰め替える mapper。
 */
internal class RouteGuidanceMapper {

    /**
     * response 内の全候補を [RouteResult] と registry payload に変換する。
     */
    fun map(
        response: RoutePlanResponseDto,
        origin: RouteCoordinateDto,
        destination: RouteCoordinateDto,
        intermediateWaypoints: List<RouteCoordinateDto>,
    ): List<ServerRouteMapping> {
        return response.candidates.mapIndexed { candidateIndex, candidate ->
            candidate.toMapping(
                candidateIndex = candidateIndex,
                origin = origin,
                destination = destination,
                intermediateWaypoints = intermediateWaypoints,
            )
        }
    }

    private fun RouteCandidateDto.toMapping(
        candidateIndex: Int,
        origin: RouteCoordinateDto,
        destination: RouteCoordinateDto,
        intermediateWaypoints: List<RouteCoordinateDto>,
    ): ServerRouteMapping {
        require(routePackageId.isNotBlank()) { "routePackageId must not be blank" }

        val originPoint = origin.toRoutePoint()
        val destinationPoint = destination.toRoutePoint()
        val routeGuidance = toRouteGuidance(candidateIndex)
        val routeGeometry = routeGuidance.buildGeometry(originPoint, destinationPoint)
        val routePriority = priority.toRoutePriority()
        val tollYen = tollFee.toYenOrNull()
        val cumulativeMetres = routeGeometry.cumulativeMetres()
        val congestionSegments = congestionSegments
            .map { segment -> segment.toModel(cumulativeMetres) }
            .toImmutableList()
        val routeIncidents = routeIncidents
            .map { incident -> incident.toModel(routeGeometry, cumulativeMetres) }
            .toImmutableList()
        val tollSegmentFees = tollDetails.toTollSegmentFees()
        val routeDetail = RouteDetail(
            id = routePackageId,
            origin = originPoint,
            destination = destinationPoint,
            intermediateWaypoints = intermediateWaypoints
                .map { waypoint -> waypoint.toRoutePoint() }
                .toImmutableList(),
            geometry = routeGeometry,
            distanceMeters = summary.lengthMetres.toDouble(),
            durationSeconds = summary.durationSeconds.toDouble(),
            steps = emptyList<RouteStepInfo>().toImmutableList(),
            congestionSegments = congestionSegments,
            priority = routePriority,
            tollFee = tollYen,
            tollDetails = tollSegmentFees,
            routeIncidents = routeIncidents,
        )
        val routeItem = RouteItem(
            durationSeconds = routeDetail.durationSeconds,
            distanceMeters = routeDetail.distanceMeters,
            geometry = routeGeometry,
            viaRoadNames = emptyList<String>().toImmutableList(),
            hasTolls = tollYen != null,
            tollFee = tollYen,
            congestionSegments = congestionSegments,
            priorityLabel = routePriority.label,
        )
        val payload = ExtNavRoutePayload(
            id = routePackageId,
            routeGuidance = routeGuidance,
        )

        return ServerRouteMapping(
            routeResult = RouteResult(
                item = routeItem,
                detail = routeDetail,
            ),
            payload = payload,
        )
    }

    private fun RouteCandidateDto.toRouteGuidance(candidateIndex: Int): RouteGuidance {
        val polyline = geometry
            .map { coordinate -> coordinate.toCoord() }
            .toImmutableList()

        return RouteGuidance(
            index = candidateIndex + 1,
            priority = priority.toCarPriority(),
            summary = DsrRouteSummary(
                depth = 0,
                distanceMetres = summary.lengthMetres,
                timeSeconds = summary.durationSeconds,
                fuelLitres = 0f,
                tollYen = tollFee.toYenOrNull() ?: 0,
                tollDetails = tollDetails.toExtNavTollDetails(),
                streets = emptyList<StreetSegment>().toImmutableList(),
                priority = priority.ordinal,
                trafficCongestionAvoidanceRate = 0f,
            ),
            guidancePoints = guidancePoints
                .map { guidancePoint -> guidancePoint.toGuidancePoint() }
                .toImmutableList(),
            intersections = emptyList<me.matsumo.drive.supporter.api.guidance.domain.Intersection>().toImmutableList(),
            imageIds = emptyList<me.matsumo.drive.supporter.api.guidance.domain.GuideImageRef>().toImmutableList(),
            polyline = polyline,
        )
    }

    private fun RouteCongestionSegmentDto.toModel(cumulativeMetres: DoubleArray): CongestionSegment {
        val startIndex = startMeasureMetres.nearestGeometryIndex(cumulativeMetres)
        val endIndex = endMeasureMetres
            .nearestGeometryIndex(cumulativeMetres)
            .coerceAtLeast(startIndex)
        val startDistanceMetres = startMeasureMetres.coerceAtLeast(0.0)
        val endDistanceMetres = endMeasureMetres.coerceAtLeast(startDistanceMetres)

        return CongestionSegment(
            startPolylinePointIndex = startIndex,
            endPolylinePointIndex = endIndex,
            severity = level.toSeverity(),
            startDistanceMeters = startDistanceMetres,
            endDistanceMeters = endDistanceMetres,
            congestionDistanceMeters = endDistanceMetres - startDistanceMetres,
            transitMinutes = null,
            trend = CongestionTrend.UNKNOWN,
            isIntermittent = false,
            headPointName = displayText?.ifBlank { null },
            source = source.toCongestionSource(),
        )
    }

    private fun RouteIncidentDto.toModel(
        geometry: List<RoutePoint>,
        cumulativeMetres: DoubleArray,
    ): RouteIncidentMarker {
        val incidentMeasureMetres = (startMeasureMetres + endMeasureMetres) / 2.0
        val incidentIndex = incidentMeasureMetres.nearestGeometryIndex(cumulativeMetres)
        val incidentPoint = geometry.getOrNull(incidentIndex) ?: RoutePoint(0.0, 0.0)
        val incidentText = displayText?.ifBlank { null } ?: kind.displayText()

        return RouteIncidentMarker(
            category = category.toModel(),
            coord = incidentPoint,
            displayText = incidentText,
            distanceFromStartMeters = incidentMeasureMetres.coerceAtLeast(0.0),
            polylinePointIndex = incidentIndex,
            placeName = sectionName?.ifBlank { null },
            roadNumbering = roadNames.firstOrNull { roadName -> roadName.isNotBlank() },
        )
    }

    private fun GuidancePointDto.toGuidancePoint(): GuidancePoint {
        return GuidancePoint(
            index = index,
            gpType = DEFAULT_GUIDANCE_POINT_TYPE,
            distanceFromPrevMetres = distanceFromPrevMetres,
            distanceFromStartMetres = distanceFromStartMetres,
            phrases = toPhrases(),
            announcementBlocks = announcementBlocks
                .map { block -> block.toAnnouncementBlock() }
                .toImmutableList(),
            imageRefs = emptyList<me.matsumo.drive.supporter.api.guidance.domain.GuideImageRef>().toImmutableList(),
            maneuver = null,
        )
    }

    private fun GuidancePointDto.toPhrases() =
        if (phrases.isNotEmpty()) {
            phrases.map { phrase -> phrase.toSsmlPhrase() }.toImmutableList()
        } else {
            announcementBlocks
                .mapNotNull { block -> block.toSsmlPhraseOrNull() }
                .toImmutableList()
        }

    private fun AnnouncementBlockDto.toAnnouncementBlock(): GuideAnnouncementBlock {
        val blockPieces = pieces.map { piece -> piece.toAnnouncementPiece() }.toImmutableList()
        val blockCategories = categories
            .ifEmpty { pieces.mapNotNull { piece -> piece.category } }
            .map { categoryId -> GuidanceCategory.fromId(categoryId) }
            .toImmutableSet()

        return GuideAnnouncementBlock(
            id = id,
            anchor = ExternalGuideAnchor(
                sourceDistanceFromStartMetres = anchorMetres,
                sourceGuidancePointIndex = null,
                sourceBlockIndex = null,
                sourceRouteInfoPointIndex = null,
            ),
            triggerDistanceMetres = triggerDistanceMetres,
            groupId = groupId,
            window = window?.toAnnouncementWindow(),
            pieces = blockPieces,
            hasBlankAnnouncementSlot = hasBlankAnnouncementSlot,
            categories = blockCategories,
        )
    }

    private fun AnnouncementPieceDto.toAnnouncementPiece(): GuideAnnouncementPiece =
        GuideAnnouncementPiece(
            text = text,
            ssml = ssml,
            templateRef = templateRef,
            category = category?.let { categoryId -> GuidanceCategory.fromId(categoryId) },
        )

    private fun AnnouncementWindowDto.toAnnouncementWindow(): GuideAnnouncementWindow =
        GuideAnnouncementWindow(
            nearMetres = nearMetres,
            farMetres = farMetres,
        )

    private fun SsmlPhraseDto.toSsmlPhrase(): SsmlPhrase =
        SsmlPhrase(
            ssml = ssml,
            distanceMetres = distanceMetres,
            category = GuidanceCategory.fromId(category),
        )

    private fun AnnouncementBlockDto.toSsmlPhraseOrNull(): SsmlPhrase? {
        if (pieces.isEmpty()) return null

        val phraseSsml = pieces.joinToString(separator = "") { piece -> piece.ssml ?: piece.text }
        val phraseCategory = pieces.firstNotNullOfOrNull { piece -> piece.category }
            ?: categories.firstOrNull()
            ?: GuidanceCategory.Unspecified.id

        return SsmlPhrase(
            ssml = phraseSsml,
            distanceMetres = triggerDistanceMetres,
            category = GuidanceCategory.fromId(phraseCategory),
        )
    }

    private fun RouteGuidance.buildGeometry(
        originPoint: RoutePoint,
        destinationPoint: RoutePoint,
    ) = buildList {
        val rawGeometry = polyline.map { coordinate ->
            RoutePoint(
                latitude = coordinate.latDegrees,
                longitude = coordinate.lonDegrees,
            )
        }

        if (rawGeometry.isEmpty()) {
            add(originPoint)
            add(destinationPoint)
            return@buildList
        }

        if (rawGeometry.first() != originPoint) add(originPoint)
        addAll(rawGeometry)
        if (rawGeometry.last() != destinationPoint) add(destinationPoint)
    }.toImmutableList()

    private fun RouteCoordinateDto.toRoutePoint(): RoutePoint =
        RoutePoint(
            latitude = latitude,
            longitude = longitude,
        )

    private fun RouteCoordinateDto.toCoord(): Coord =
        Coord.fromDegrees(
            latDeg = latitude,
            lonDeg = longitude,
        )

    private fun RouteTollFeeDto?.toYenOrNull(): Int? {
        val tollFee = this ?: return null
        val isYen = tollFee.currency.equals("JPY", ignoreCase = true)
        if (!isYen) return null

        return tollFee.amount.roundToInt()
            .takeIf { amount -> amount > 0 }
    }

    private fun List<RouteTollDetailDto>?.toTollSegmentFees() =
        orEmpty()
            .mapNotNull { detail -> detail.toTollSegmentFeeOrNull() }
            .toImmutableList()

    private fun List<RouteTollDetailDto>?.toExtNavTollDetails() =
        orEmpty()
            .mapNotNull { detail -> detail.toTollDetailOrNull() }
            .toImmutableList()

    private fun RouteTollDetailDto.toTollSegmentFeeOrNull(): TollSegmentFee? {
        val amount = representativeYenAmountOrNull() ?: return null
        val roadName = tollSystem?.ifBlank { null }
            ?: fareName?.ifBlank { null }
            ?: reason?.ifBlank { null }
            ?: TOLL_DETAIL_FALLBACK_ROAD_NAME

        return TollSegmentFee(
            roadName = roadName,
            amount = amount,
        )
    }

    private fun RouteTollDetailDto.toTollDetailOrNull(): TollDetail? {
        val amount = representativeYenAmountOrNull() ?: return null
        val roadName = tollSystem?.ifBlank { null }
            ?: fareName?.ifBlank { null }
            ?: reason?.ifBlank { null }
            ?: TOLL_DETAIL_FALLBACK_ROAD_NAME

        return TollDetail(
            road = roadName,
            amount = amount,
        )
    }

    private fun RouteTollDetailDto.representativeYenAmountOrNull(): Int? {
        val tollPrice = convertedPrice ?: price ?: return null
        val currency = tollPrice.currency ?: return null
        if (!currency.equals("JPY", ignoreCase = true)) return null

        return tollPrice.amount
            ?.roundToInt()
            ?.takeIf { amount -> amount > 0 }
    }

    private fun RouteCongestionLevelDto.toSeverity(): CongestionSeverity =
        when (this) {
            RouteCongestionLevelDto.SMOOTH -> CongestionSeverity.NORMAL
            RouteCongestionLevelDto.CROWDED -> CongestionSeverity.SLOW
            RouteCongestionLevelDto.JAM,
            RouteCongestionLevelDto.BLOCKED,
            -> CongestionSeverity.TRAFFIC_JAM
            RouteCongestionLevelDto.UNKNOWN -> CongestionSeverity.UNKNOWN
        }

    private fun String.toCongestionSource(): CongestionSegmentSource {
        val normalizedSource = uppercase()

        return when (normalizedSource) {
            "HERE",
            "JARTIC",
            -> CongestionSegmentSource.ROUTE_LINK
            else -> CongestionSegmentSource.UNKNOWN
        }
    }

    private fun RouteIncidentCategoryDto.toModel(): RouteIncidentMarkerCategory =
        when (this) {
            RouteIncidentCategoryDto.REGULATION -> RouteIncidentMarkerCategory.Regulation
            RouteIncidentCategoryDto.INCIDENT -> RouteIncidentMarkerCategory.Accident
        }

    private fun RouteIncidentKindDto.displayText(): String =
        when (this) {
            RouteIncidentKindDto.CLOSURE -> "通行止め"
            RouteIncidentKindDto.LANE_RESTRICTION -> "車線規制"
            RouteIncidentKindDto.SPEED_LIMIT -> "速度規制"
            RouteIncidentKindDto.ALTERNATE_ONE_WAY -> "片側交互通行"
            RouteIncidentKindDto.ONE_WAY -> "片側通行"
            RouteIncidentKindDto.TURN_RESTRICTION -> "進行方向規制"
            RouteIncidentKindDto.WINTER_CLOSURE -> "冬期閉鎖"
            RouteIncidentKindDto.CHAIN_REQUIRED -> "チェーン規制"
            RouteIncidentKindDto.WINTER_TIRE_REQUIRED -> "冬用タイヤ規制"
            RouteIncidentKindDto.INCIDENT -> "事故・故障車"
            RouteIncidentKindDto.OTHER_REGULATION -> "交通規制"
            RouteIncidentKindDto.UNKNOWN -> "交通情報"
        }

    private fun List<RoutePoint>.cumulativeMetres(): DoubleArray {
        val cumulativeMetres = DoubleArray(size)
        for (pointIndex in 1 until size) {
            cumulativeMetres[pointIndex] = cumulativeMetres[pointIndex - 1] + haversineMetres(
                first = this[pointIndex - 1],
                second = this[pointIndex],
            )
        }

        return cumulativeMetres
    }

    private fun Double.nearestGeometryIndex(cumulativeMetres: DoubleArray): Int {
        if (cumulativeMetres.isEmpty()) return 0

        val clampedMetres = coerceIn(0.0, cumulativeMetres.last())
        return cumulativeMetres
            .withIndex()
            .minByOrNull { indexedMetres -> abs(indexedMetres.value - clampedMetres) }
            ?.index
            ?: 0
    }

    private fun haversineMetres(first: RoutePoint, second: RoutePoint): Double {
        val firstLatitudeRadians = Math.toRadians(first.latitude)
        val secondLatitudeRadians = Math.toRadians(second.latitude)
        val latitudeDeltaRadians = Math.toRadians(second.latitude - first.latitude)
        val longitudeDeltaRadians = Math.toRadians(second.longitude - first.longitude)
        val sinHalfLatitude = sin(latitudeDeltaRadians / 2.0)
        val sinHalfLongitude = sin(longitudeDeltaRadians / 2.0)
        val haversineValue = sinHalfLatitude * sinHalfLatitude +
            cos(firstLatitudeRadians) * cos(secondLatitudeRadians) * sinHalfLongitude * sinHalfLongitude
        val angularDistance = 2.0 * atan2(sqrt(haversineValue), sqrt(1.0 - haversineValue))

        return EARTH_RADIUS_METRES * angularDistance
    }

    private fun RoutePriorityDto.toCarPriority(): ExtNavCarPriority =
        when (this) {
            RoutePriorityDto.RECOMMENDED -> ExtNavCarPriority.Recommended
            RoutePriorityDto.AVOID_CONGESTION -> ExtNavCarPriority.AvoidCongestion
            RoutePriorityDto.EXPRESS -> ExtNavCarPriority.Express
            RoutePriorityDto.FREE -> ExtNavCarPriority.Free
            RoutePriorityDto.DISTANCE -> ExtNavCarPriority.Distance
        }

    private fun RoutePriorityDto.toRoutePriority(): RoutePriority =
        when (this) {
            RoutePriorityDto.RECOMMENDED -> RoutePriority.Recommended
            RoutePriorityDto.AVOID_CONGESTION -> RoutePriority.AvoidCongestion
            RoutePriorityDto.EXPRESS -> RoutePriority.Express
            RoutePriorityDto.FREE -> RoutePriority.Free
            RoutePriorityDto.DISTANCE -> RoutePriority.Distance
        }

    private companion object {
        /** server S1 で gpType が未提供のときに使う既定値。 */
        const val DEFAULT_GUIDANCE_POINT_TYPE: Int = 0

        /** 有料道路名が取れない toll detail の fallback 名。 */
        const val TOLL_DETAIL_FALLBACK_ROAD_NAME: String = "通行料金"

        /** 地球半径 (m)。 */
        const val EARTH_RADIUS_METRES: Double = 6_371_000.0
    }
}

/**
 * 1 候補分の中立 route と registry payload。
 */
internal data class ServerRouteMapping(
    val routeResult: RouteResult,
    val payload: ExtNavRoutePayload,
)
