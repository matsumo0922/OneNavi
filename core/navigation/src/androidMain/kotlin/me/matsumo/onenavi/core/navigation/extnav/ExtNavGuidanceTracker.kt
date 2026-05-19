package me.matsumo.onenavi.core.navigation.extnav

import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.matsumo.onenavi.core.datasource.location.UserLocation
import me.matsumo.onenavi.core.model.ManeuverModifier
import me.matsumo.onenavi.core.model.ManeuverType
import me.matsumo.onenavi.core.model.RoadClass
import me.matsumo.onenavi.core.model.RouteDetail
import me.matsumo.onenavi.core.model.RoutePoint
import me.matsumo.onenavi.core.navigation.newguidance.model.GuidanceManeuverInfo
import me.matsumo.onenavi.core.navigation.newguidance.model.GuidanceProgress
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

class ExtNavGuidanceTracker {

    private val _snapshot = MutableStateFlow<ExtNavProgressSnapshot?>(null)
    val snapshot: StateFlow<ExtNavProgressSnapshot?> = _snapshot.asStateFlow()

    private var attachedRoute: AttachedRoute? = null
    private var lastProjection: RouteProjection? = null

    fun attach(payload: ExtNavRoutePayload, route: RouteDetail) {
        val cumulativeMetres = buildCumulativeGeometryMetres(route.geometry)
        val totalGeometryMetres = cumulativeMetres.lastOrNull() ?: 0.0
        val sourceTotalMetres = payload.routeGuidance.summary.distanceMetres
            .toDouble()
            .takeIf { metres -> metres > 0.0 }
            ?: route.distanceMeters
                .takeIf { metres -> metres > 0.0 }
            ?: totalGeometryMetres

        val distanceMapper = RouteDistanceMapper(
            anchors = listOf(
                DistanceAnchor(sourceMetres = 0.0, geometryMetres = 0.0),
                DistanceAnchor(sourceMetres = sourceTotalMetres, geometryMetres = totalGeometryMetres),
            ),
        )
        val guidancePointMetres = payload.routeGuidance.guidancePoints
            .map { guidancePoint ->
                distanceMapper
                    .mapSourceToGeometry(guidancePoint.distanceFromStartMetres.toDouble())
                    .coerceIn(0.0, totalGeometryMetres)
            }
            .toDoubleArray()

        attachedRoute = AttachedRoute(
            payload = payload,
            route = route,
            cumulativeMetres = cumulativeMetres,
            totalGeometryMetres = totalGeometryMetres,
            guidancePointMetres = guidancePointMetres,
            intersections = buildSnappedIntersections(
                payload = payload,
                route = route,
                cumulativeMetres = cumulativeMetres,
            ),
        )
        lastProjection = null
        _snapshot.value = null
    }

    fun onLocation(location: UserLocation) {
        val attached = attachedRoute ?: return
        val projection = projectLocation(
            route = attached.route,
            cumulativeMetres = attached.cumulativeMetres,
            location = location,
            previousProjection = lastProjection,
        )
        lastProjection = projection

        val nextGuidancePointIndex = attached.guidancePointMetres
            .upperBound(projection.currentCumulativeMeters + NEXT_GP_EPSILON_METRES)
        val progress = buildProgress(
            attached = attached,
            projection = projection,
            location = location,
            nextGuidancePointIndex = nextGuidancePointIndex,
        )

        _snapshot.value = ExtNavProgressSnapshot(
            progress = progress,
            rawLocation = location,
            currentCumulativeMeters = projection.currentCumulativeMeters,
            distanceRemainingMeters = (attached.totalGeometryMetres - projection.currentCumulativeMeters).coerceAtLeast(0.0),
            matchedSegmentIndex = projection.matchedSegmentIndex,
            projectionErrorMeters = projection.projectionErrorMeters,
            locationTimestampMillis = location.timestampMillis,
            vehicleSpeedMps = location.speedMps,
            isOffRouteCandidate = isOffRouteCandidate(
                projection = projection,
                location = location,
                attached = attached,
            ),
            nextGuidancePointIndex = nextGuidancePointIndex,
        )
    }

    fun detach() {
        attachedRoute = null
        lastProjection = null
        _snapshot.value = null
    }

    private fun buildProgress(
        attached: AttachedRoute,
        projection: RouteProjection,
        location: UserLocation,
        nextGuidancePointIndex: Int?,
    ): GuidanceProgress {
        val distanceRemainingMeters = (attached.totalGeometryMetres - projection.currentCumulativeMeters)
            .coerceAtLeast(0.0)
        val durationRemainingSeconds = remainingDurationSeconds(
            route = attached.route,
            totalGeometryMetres = attached.totalGeometryMetres,
            currentCumulativeMeters = projection.currentCumulativeMeters,
        )
        val nextManeuver = nextGuidancePointIndex?.let { guidancePointIndex ->
            buildManeuverInfo(
                attached = attached,
                guidancePointIndex = guidancePointIndex,
                currentCumulativeMeters = projection.currentCumulativeMeters,
            )
        }
        val followupManeuver = nextGuidancePointIndex
            ?.plus(1)
            ?.takeIf { guidancePointIndex -> guidancePointIndex <= attached.guidancePointMetres.lastIndex }
            ?.let { guidancePointIndex ->
                buildManeuverInfo(
                    attached = attached,
                    guidancePointIndex = guidancePointIndex,
                    currentCumulativeMeters = projection.currentCumulativeMeters,
                )
            }

        return GuidanceProgress(
            distanceRemainingMeters = distanceRemainingMeters.roundToInt(),
            durationRemainingSeconds = durationRemainingSeconds.roundToInt(),
            etaEpochMillis = location.timestampMillis + durationRemainingSeconds.roundToInt().toLong() * MILLIS_PER_SECOND,
            traveledMeters = projection.currentCumulativeMeters.roundToInt(),
            snappedLocation = projection.snappedLocation,
            bearingDegrees = location.bearingDegrees ?: projection.segmentBearingDegrees,
            nextManeuver = nextManeuver,
            followupManeuver = followupManeuver,
            lanes = persistentListOf(),
            directionSign = null,
            highwayPanel = null,
            currentRoadName = null,
            currentRoadClass = currentRoadClass(
                route = attached.route,
                matchedSegmentIndex = projection.matchedSegmentIndex,
            ),
            currentSpeedLimitKmh = null,
        )
    }

    private fun buildManeuverInfo(
        attached: AttachedRoute,
        guidancePointIndex: Int,
        currentCumulativeMeters: Double,
    ): GuidanceManeuverInfo {
        val guidancePoint = attached.payload.routeGuidance.guidancePoints[guidancePointIndex]
        val guidancePointMetres = attached.guidancePointMetres[guidancePointIndex]
        val nearestIntersection = attached.intersections
            .filter { intersection -> abs(intersection.geometryMetres - guidancePointMetres) <= INTERSECTION_SNAP_TOLERANCE_METRES }
            .minByOrNull { intersection -> abs(intersection.geometryMetres - guidancePointMetres) }
        val bearingDiffDegrees = bearingDiffAt(
            route = attached.route,
            cumulativeMetres = attached.cumulativeMetres,
            targetMetres = guidancePointMetres,
        )

        return GuidanceManeuverInfo(
            type = maneuverType(
                categoryNames = guidancePoint.phrases.map { phrase -> phrase.category.name },
                isLastGuidancePoint = guidancePointIndex == attached.guidancePointMetres.lastIndex,
                bearingDiffDegrees = bearingDiffDegrees,
            ),
            modifier = maneuverModifier(bearingDiffDegrees),
            distanceToManeuverMeters = (guidancePointMetres - currentCumulativeMeters)
                .coerceAtLeast(0.0)
                .roundToInt(),
            intersectionName = nearestIntersection?.name?.takeIf { name -> name.isNotBlank() },
            exitNumber = null,
            guidancePointIndex = guidancePointIndex,
        )
    }

    private fun projectLocation(
        route: RouteDetail,
        cumulativeMetres: DoubleArray,
        location: UserLocation,
        previousProjection: RouteProjection?,
    ): RouteProjection {
        val geometry = route.geometry
        if (geometry.isEmpty()) {
            val point = RoutePoint(latitude = location.latitude, longitude = location.longitude)
            return RouteProjection(
                snappedLocation = point,
                currentCumulativeMeters = 0.0,
                matchedSegmentIndex = 0,
                projectionErrorMeters = 0.0,
                segmentBearingDegrees = location.bearingDegrees ?: 0f,
            )
        }

        if (geometry.size == 1 || cumulativeMetres.size <= 1) {
            val point = geometry.first()
            return RouteProjection(
                snappedLocation = point,
                currentCumulativeMeters = 0.0,
                matchedSegmentIndex = 0,
                projectionErrorMeters = haversineMetres(point, location.toRoutePoint()),
                segmentBearingDegrees = location.bearingDegrees ?: 0f,
            )
        }

        val searchStartIndex = previousProjection
            ?.matchedSegmentIndex
            ?.coerceIn(0, geometry.lastIndex - 1)
            ?: 0
        val searchEndIndex = if (previousProjection == null) {
            geometry.lastIndex - 1
        } else {
            min(
                geometry.lastIndex - 1,
                searchStartIndex + MAX_SEGMENT_LOOKAHEAD,
            )
        }

        var bestProjection: RouteProjection? = null
        for (segmentIndex in searchStartIndex..searchEndIndex) {
            val candidate = projectToSegment(
                start = geometry[segmentIndex],
                end = geometry[segmentIndex + 1],
                cumulativeMetres = cumulativeMetres,
                segmentIndex = segmentIndex,
                location = location,
            )
            if (bestProjection == null || candidate.projectionErrorMeters < bestProjection.projectionErrorMeters) {
                bestProjection = candidate
            }
        }

        val projection = bestProjection ?: projectToSegment(
            start = geometry[0],
            end = geometry[1],
            cumulativeMetres = cumulativeMetres,
            segmentIndex = 0,
            location = location,
        )
        val previousCumulativeMeters = previousProjection?.currentCumulativeMeters
            ?: return projection

        return if (projection.currentCumulativeMeters < previousCumulativeMeters &&
            previousCumulativeMeters - projection.currentCumulativeMeters <= BACKWARD_HYSTERESIS_METRES
        ) {
            previousProjection
        } else {
            projection
        }
    }

    private fun projectToSegment(
        start: RoutePoint,
        end: RoutePoint,
        cumulativeMetres: DoubleArray,
        segmentIndex: Int,
        location: UserLocation,
    ): RouteProjection {
        val point = location.toRoutePoint()
        val scale = meterScaleAt((start.latitude + end.latitude) / 2.0)
        val segmentX = (end.longitude - start.longitude) * scale.longitudeMetresPerDegree
        val segmentY = (end.latitude - start.latitude) * scale.latitudeMetresPerDegree
        val pointX = (point.longitude - start.longitude) * scale.longitudeMetresPerDegree
        val pointY = (point.latitude - start.latitude) * scale.latitudeMetresPerDegree
        val segmentLengthSquared = segmentX * segmentX + segmentY * segmentY
        val ratio = if (segmentLengthSquared <= 0.0) {
            0.0
        } else {
            ((pointX * segmentX + pointY * segmentY) / segmentLengthSquared).coerceIn(0.0, 1.0)
        }
        val snappedLocation = RoutePoint(
            latitude = start.latitude + (end.latitude - start.latitude) * ratio,
            longitude = start.longitude + (end.longitude - start.longitude) * ratio,
        )
        val segmentMetres = cumulativeMetres[segmentIndex + 1] - cumulativeMetres[segmentIndex]

        return RouteProjection(
            snappedLocation = snappedLocation,
            currentCumulativeMeters = cumulativeMetres[segmentIndex] + segmentMetres * ratio,
            matchedSegmentIndex = segmentIndex,
            projectionErrorMeters = haversineMetres(point, snappedLocation),
            segmentBearingDegrees = bearingDegrees(start, end),
        )
    }

    private fun buildCumulativeGeometryMetres(geometry: List<RoutePoint>): DoubleArray {
        if (geometry.isEmpty()) return DoubleArray(0)
        val cumulativeMetres = DoubleArray(geometry.size)
        for (index in 1 until geometry.size) {
            cumulativeMetres[index] = cumulativeMetres[index - 1] + haversineMetres(geometry[index - 1], geometry[index])
        }
        return cumulativeMetres
    }

    private fun buildSnappedIntersections(
        payload: ExtNavRoutePayload,
        route: RouteDetail,
        cumulativeMetres: DoubleArray,
    ): List<TrackerIntersection> {
        if (route.geometry.isEmpty() || cumulativeMetres.isEmpty()) return emptyList()

        return payload.routeGuidance.intersections
            .map { intersection ->
                val geometryIndex = nearestGeometryIndex(
                    geometry = route.geometry,
                    point = RoutePoint(
                        latitude = intersection.position.latDegrees,
                        longitude = intersection.position.lonDegrees,
                    ),
                )
                TrackerIntersection(
                    geometryMetres = cumulativeMetres[geometryIndex],
                    name = intersection.name,
                )
            }
            .sortedBy { intersection -> intersection.geometryMetres }
    }

    private fun nearestGeometryIndex(
        geometry: List<RoutePoint>,
        point: RoutePoint,
    ): Int {
        var bestIndex = 0
        var bestDistanceMetres = Double.MAX_VALUE
        for ((index, routePoint) in geometry.withIndex()) {
            val distanceMetres = haversineMetres(routePoint, point)
            if (distanceMetres < bestDistanceMetres) {
                bestDistanceMetres = distanceMetres
                bestIndex = index
            }
        }
        return bestIndex
    }

    private fun DoubleArray.upperBound(value: Double): Int? {
        if (isEmpty()) return null
        var lowIndex = 0
        var highIndex = size
        while (lowIndex < highIndex) {
            val middleIndex = (lowIndex + highIndex) / 2
            if (this[middleIndex] <= value) {
                lowIndex = middleIndex + 1
            } else {
                highIndex = middleIndex
            }
        }
        return lowIndex.takeIf { index -> index < size }
    }

    private fun remainingDurationSeconds(
        route: RouteDetail,
        totalGeometryMetres: Double,
        currentCumulativeMeters: Double,
    ): Double {
        if (route.durationSeconds <= 0.0) return 0.0
        if (totalGeometryMetres <= 0.0) return route.durationSeconds
        val remainingRatio = (1.0 - currentCumulativeMeters / totalGeometryMetres).coerceIn(0.0, 1.0)
        return route.durationSeconds * remainingRatio
    }

    private fun isOffRouteCandidate(
        projection: RouteProjection,
        location: UserLocation,
        attached: AttachedRoute,
    ): Boolean {
        if (attached.totalGeometryMetres - projection.currentCumulativeMeters <= ARRIVAL_SUPPRESSION_METRES) return false
        if (location.accuracyMeters > MAX_OFF_ROUTE_ACCURACY_METRES) return false
        if ((location.speedMps ?: 0f) < MIN_OFF_ROUTE_SPEED_MPS) return false

        val errorThreshold = max(
            MIN_OFF_ROUTE_ERROR_METRES,
            location.accuracyMeters * OFF_ROUTE_ACCURACY_MULTIPLIER,
        )
        if (projection.projectionErrorMeters < errorThreshold) return false

        val bearingDegrees = location.bearingDegrees ?: return true
        val bearingDiffDegrees = abs(normalizeDegrees(bearingDegrees - projection.segmentBearingDegrees))
        return bearingDiffDegrees >= OFF_ROUTE_BEARING_DIFF_DEGREES
    }

    private fun currentRoadClass(
        route: RouteDetail,
        matchedSegmentIndex: Int,
    ): RoadClass = route.roadClassSegments
        .firstOrNull { segment ->
            matchedSegmentIndex >= segment.startPointIndex &&
                matchedSegmentIndex < segment.endPointIndex
        }
        ?.roadClass
        ?: RoadClass.ORDINARY

    private fun maneuverType(
        categoryNames: List<String>,
        isLastGuidancePoint: Boolean,
        bearingDiffDegrees: Float,
    ): ManeuverType {
        if (isLastGuidancePoint) return ManeuverType.ARRIVE
        if (categoryNames.any { name -> name in MERGE_CATEGORY_NAMES }) return ManeuverType.MERGE
        if (categoryNames.any { name -> name in FORK_CATEGORY_NAMES }) return ManeuverType.FORK
        if (categoryNames.any { name -> name == ROAD_NAME_CATEGORY_NAME }) return ManeuverType.NAME_CHANGE
        if (abs(bearingDiffDegrees) >= TURN_BEARING_DIFF_DEGREES ||
            categoryNames.any { name -> name in TURN_CATEGORY_NAMES }
        ) {
            return ManeuverType.TURN
        }
        return ManeuverType.CONTINUE
    }

    private fun maneuverModifier(bearingDiffDegrees: Float): ManeuverModifier {
        val absDiffDegrees = abs(bearingDiffDegrees)
        return when {
            absDiffDegrees <= STRAIGHT_MAX_DEGREES -> ManeuverModifier.STRAIGHT
            absDiffDegrees <= SLIGHT_MAX_DEGREES -> if (bearingDiffDegrees >= 0f) {
                ManeuverModifier.SLIGHT_RIGHT
            } else {
                ManeuverModifier.SLIGHT_LEFT
            }
            absDiffDegrees <= TURN_MAX_DEGREES -> if (bearingDiffDegrees >= 0f) {
                ManeuverModifier.RIGHT
            } else {
                ManeuverModifier.LEFT
            }
            else -> ManeuverModifier.UTURN
        }
    }

    private fun bearingDiffAt(
        route: RouteDetail,
        cumulativeMetres: DoubleArray,
        targetMetres: Double,
    ): Float {
        if (route.geometry.size < 3 || cumulativeMetres.size < 3) return 0f
        val segmentIndex = segmentIndexAt(cumulativeMetres, targetMetres)
        val beforeIndex = (segmentIndex - 1).coerceAtLeast(0)
        val afterIndex = (segmentIndex + 1).coerceAtMost(route.geometry.lastIndex - 1)
        val beforeBearing = bearingDegrees(route.geometry[beforeIndex], route.geometry[beforeIndex + 1])
        val afterBearing = bearingDegrees(route.geometry[afterIndex], route.geometry[afterIndex + 1])
        return normalizeDegrees(afterBearing - beforeBearing)
    }

    private fun segmentIndexAt(
        cumulativeMetres: DoubleArray,
        targetMetres: Double,
    ): Int {
        if (cumulativeMetres.size <= 1) return 0
        if (targetMetres <= 0.0) return 0
        if (targetMetres >= cumulativeMetres.last()) return cumulativeMetres.lastIndex - 1

        var lowIndex = 1
        var highIndex = cumulativeMetres.lastIndex
        while (lowIndex < highIndex) {
            val middleIndex = (lowIndex + highIndex) / 2
            if (cumulativeMetres[middleIndex] < targetMetres) {
                lowIndex = middleIndex + 1
            } else {
                highIndex = middleIndex
            }
        }
        return (lowIndex - 1).coerceIn(0, cumulativeMetres.lastIndex - 1)
    }

    private fun UserLocation.toRoutePoint(): RoutePoint = RoutePoint(
        latitude = latitude,
        longitude = longitude,
    )

    private fun meterScaleAt(latitude: Double): MeterScale {
        val latitudeRadians = Math.toRadians(latitude)
        return MeterScale(
            latitudeMetresPerDegree = METRES_PER_DEGREE,
            longitudeMetresPerDegree = METRES_PER_DEGREE * cos(latitudeRadians),
        )
    }

    private fun haversineMetres(from: RoutePoint, to: RoutePoint): Double {
        val fromLatRadians = Math.toRadians(from.latitude)
        val toLatRadians = Math.toRadians(to.latitude)
        val deltaLatRadians = Math.toRadians(to.latitude - from.latitude)
        val deltaLngRadians = Math.toRadians(to.longitude - from.longitude)
        val haversineTerm = sin(deltaLatRadians / 2.0) * sin(deltaLatRadians / 2.0) +
            cos(fromLatRadians) * cos(toLatRadians) * sin(deltaLngRadians / 2.0) * sin(deltaLngRadians / 2.0)
        return EARTH_RADIUS_METRES * 2.0 * atan2(sqrt(haversineTerm), sqrt(1.0 - haversineTerm))
    }

    private fun bearingDegrees(from: RoutePoint, to: RoutePoint): Float {
        val fromLatRadians = Math.toRadians(from.latitude)
        val toLatRadians = Math.toRadians(to.latitude)
        val deltaLngRadians = Math.toRadians(to.longitude - from.longitude)
        val y = sin(deltaLngRadians) * cos(toLatRadians)
        val x = cos(fromLatRadians) * sin(toLatRadians) -
            sin(fromLatRadians) * cos(toLatRadians) * cos(deltaLngRadians)
        return ((Math.toDegrees(atan2(y, x)) + FULL_CIRCLE_DEGREES) % FULL_CIRCLE_DEGREES).toFloat()
    }

    private fun normalizeDegrees(degrees: Float): Float {
        var normalized = degrees % FULL_CIRCLE_DEGREES
        if (normalized > HALF_CIRCLE_DEGREES) normalized -= FULL_CIRCLE_DEGREES
        if (normalized < -HALF_CIRCLE_DEGREES) normalized += FULL_CIRCLE_DEGREES
        return normalized
    }

    private data class AttachedRoute(
        val payload: ExtNavRoutePayload,
        val route: RouteDetail,
        val cumulativeMetres: DoubleArray,
        val totalGeometryMetres: Double,
        val guidancePointMetres: DoubleArray,
        val intersections: List<TrackerIntersection>,
    )

    private data class RouteProjection(
        val snappedLocation: RoutePoint,
        val currentCumulativeMeters: Double,
        val matchedSegmentIndex: Int,
        val projectionErrorMeters: Double,
        val segmentBearingDegrees: Float,
    )

    private data class TrackerIntersection(
        val geometryMetres: Double,
        val name: String,
    )

    private data class MeterScale(
        val latitudeMetresPerDegree: Double,
        val longitudeMetresPerDegree: Double,
    )

    private companion object {
        private const val EARTH_RADIUS_METRES: Double = 6_371_000.0
        private const val METRES_PER_DEGREE: Double = 111_320.0
        private const val FULL_CIRCLE_DEGREES: Float = 360f
        private const val HALF_CIRCLE_DEGREES: Float = 180f
        private const val MILLIS_PER_SECOND: Long = 1_000L
        private const val MAX_SEGMENT_LOOKAHEAD: Int = 300
        private const val NEXT_GP_EPSILON_METRES: Double = 1.0
        private const val BACKWARD_HYSTERESIS_METRES: Double = 5.0
        private const val INTERSECTION_SNAP_TOLERANCE_METRES: Double = 300.0
        private const val ARRIVAL_SUPPRESSION_METRES: Double = 100.0
        private const val MAX_OFF_ROUTE_ACCURACY_METRES: Float = 50f
        private const val MIN_OFF_ROUTE_SPEED_MPS: Float = 1.5f
        private const val MIN_OFF_ROUTE_ERROR_METRES: Double = 30.0
        private const val OFF_ROUTE_ACCURACY_MULTIPLIER: Double = 1.5
        private const val OFF_ROUTE_BEARING_DIFF_DEGREES: Float = 70f
        private const val TURN_BEARING_DIFF_DEGREES: Float = 30f
        private const val STRAIGHT_MAX_DEGREES: Float = 5f
        private const val SLIGHT_MAX_DEGREES: Float = 60f
        private const val TURN_MAX_DEGREES: Float = 150f

        private val MERGE_CATEGORY_NAMES = setOf(
            "Merge",
            "MergeAttention",
            "HighwayLaneReduction",
        )
        private val FORK_CATEGORY_NAMES = setOf(
            "AutoExpresswayEntry",
            "TunnelBranch",
        )
        private val TURN_CATEGORY_NAMES = setOf(
            "IntersectionGuide",
            "IntersectionGuideSoon",
            "TrafficLight",
            "TurnAttention",
            "LocalRoadDirection",
        )
        private const val ROAD_NAME_CATEGORY_NAME: String = "RoadName"
    }
}
