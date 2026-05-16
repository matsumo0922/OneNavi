package me.matsumo.onenavi.feature.map.components.callout

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize
import me.matsumo.onenavi.core.model.RoutePoint
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * CallOut の候補生成と scoring を行う純粋関数群。
 */
internal object MapCallOutPlacementEngine {

    fun place(
        requests: List<MapCallOutRequest>,
        sizes: List<IntSize>,
        viewportSize: IntSize,
        viewport: Rect,
        tailLengthPx: Float,
        project: (RoutePoint) -> Offset,
    ): List<MapCallOutPlacement> {
        require(requests.size == sizes.size) {
            "requests (${requests.size}) and sizes (${sizes.size}) must have the same length"
        }

        val allPolylines = requests.mapNotNull { request ->
            (request.target as? MapCallOutTarget.PolylineMovable)
                ?.points
                ?.map(project)
        }
        val placed = mutableListOf<MapCallOutPlacement>()
        val orderedIndexes = requests.indices.sortedWith(
            compareByDescending<Int> { requests[it].priority }
                .thenBy { requests[it].id }
                .thenBy { it },
        )
        val routeSlotFractions = routeSlotFractions(
            requests = requests,
            orderedIndexes = orderedIndexes,
        )

        for (requestIndex in orderedIndexes) {
            val request = requests[requestIndex]
            val size = sizes[requestIndex]
            val placement = placeOne(
                requestIndex = requestIndex,
                request = request,
                size = size,
                viewportSize = viewportSize,
                viewport = viewport,
                tailLengthPx = tailLengthPx,
                allPolylines = allPolylines,
                placed = placed,
                preferredRouteFraction = routeSlotFractions[requestIndex],
                project = project,
            )

            if (placement != null) {
                placed += placement
            }
        }

        return placed
    }

    private fun placeOne(
        requestIndex: Int,
        request: MapCallOutRequest,
        size: IntSize,
        viewportSize: IntSize,
        viewport: Rect,
        tailLengthPx: Float,
        allPolylines: List<List<Offset>>,
        placed: List<MapCallOutPlacement>,
        preferredRouteFraction: Float?,
        project: (RoutePoint) -> Offset,
    ): MapCallOutPlacement? {
        if (size.width <= 0 || size.height <= 0 || viewportSize.width <= 0 || viewportSize.height <= 0) {
            return null
        }

        val candidates = buildCandidates(
            request = request,
            viewport = viewport,
            project = project,
        )
        val best = candidates
            .mapNotNull { candidate ->
                scoreCandidate(
                    candidate = candidate,
                    request = request,
                    size = size,
                    viewport = viewport,
                    tailLengthPx = tailLengthPx,
                    allPolylines = allPolylines,
                    placed = placed,
                    preferredRouteFraction = preferredRouteFraction,
                )
            }
            .maxByOrNull { it.score }

        return best?.takeIf { it.score > MIN_ACCEPTED_SCORE }?.toPlacement(
            requestIndex = requestIndex,
            requestId = request.id,
        )
    }

    private fun buildCandidates(
        request: MapCallOutRequest,
        viewport: Rect,
        project: (RoutePoint) -> Offset,
    ): List<Candidate> {
        val supportedSides = request.supportedTailSides.ifEmpty { DEFAULT_MAP_CALLOUT_TAIL_SIDES }
        val sides = rotateTailSides(
            sides = supportedSides,
            preferred = request.previousPlacement?.tailSide,
        )
        val tips = when (val target = request.target) {
            is MapCallOutTarget.PointFixed -> listOf(
                CandidateTip(
                    position = target.point,
                    screenPoint = project(target.point),
                    visibleFraction = null,
                ),
            )
            is MapCallOutTarget.PolylineMovable -> {
                val projected = target.points.map { point ->
                    ProjectedRoutePoint(
                        position = point,
                        screenPoint = project(point),
                    )
                }
                val sampled = samplePolyline(
                    polyline = projected,
                    viewport = viewport,
                    maxSamples = MAX_POLYLINE_SAMPLES,
                )

                buildList {
                    addAll(sampled)
                    request.previousPlacement?.let { previous ->
                        val previousTip = project(previous.position)
                        if (viewport.contains(previousTip)) {
                            add(
                                CandidateTip(
                                    position = previous.position,
                                    screenPoint = previousTip,
                                    visibleFraction = null,
                                ),
                            )
                        }
                    }
                }.distinctBy { it.position }
            }
        }

        return tips.flatMap { tip ->
            sides.map { side ->
                Candidate(
                    position = tip.position,
                    tip = tip.screenPoint,
                    tailSide = side,
                    visibleFraction = tip.visibleFraction,
                )
            }
        }
    }

    private fun routeSlotFractions(
        requests: List<MapCallOutRequest>,
        orderedIndexes: List<Int>,
    ): Map<Int, Float> {
        val routeIndexes = orderedIndexes.filter { index ->
            requests[index].target is MapCallOutTarget.PolylineMovable
        }
        val fractions = visibleRouteFractions(routeIndexes.size)

        return routeIndexes.mapIndexed { slotIndex, requestIndex ->
            requestIndex to fractions[slotIndex]
        }.toMap()
    }

    private fun visibleRouteFractions(count: Int): List<Float> {
        if (count <= 0) return emptyList()
        if (count == 1) return listOf(ROUTE_SLOT_CENTER_FRACTION)

        val step = (ROUTE_SLOT_END_FRACTION - ROUTE_SLOT_START_FRACTION) / (count - 1)
        return (0 until count)
            .map { index -> ROUTE_SLOT_START_FRACTION + step * index }
            .sortedWith(
                compareBy<Float> { fraction -> abs(fraction - ROUTE_SLOT_CENTER_FRACTION) }
                    .thenBy { fraction -> fraction },
            )
    }

    private fun rotateTailSides(
        sides: List<MapCallOutTailSide>,
        preferred: MapCallOutTailSide?,
    ): List<MapCallOutTailSide> {
        if (preferred == null) return sides

        val preferredIndex = sides.indexOf(preferred)
        if (preferredIndex < 0) return sides

        return sides.drop(preferredIndex) + sides.take(preferredIndex)
    }

    private fun samplePolyline(
        polyline: List<ProjectedRoutePoint>,
        viewport: Rect,
        maxSamples: Int,
    ): List<CandidateTip> {
        if (polyline.isEmpty()) return emptyList()
        if (maxSamples <= 0) return emptyList()

        val visibleViewport = viewport
        if (polyline.size == 1) {
            return polyline.filter { visibleViewport.contains(it.screenPoint) }
                .map { point ->
                    CandidateTip(
                        position = point.position,
                        screenPoint = point.screenPoint,
                        visibleFraction = ROUTE_SLOT_CENTER_FRACTION,
                    )
                }
        }

        val visibleSegments = polyline.zipWithNext().mapNotNull { (start, end) ->
            clipPolylineSegment(
                start = start,
                end = end,
                rect = visibleViewport,
            )
        }
        val totalLength = visibleSegments.sumOf { segment ->
            segment.length.toDouble()
        }.toFloat()
        if (totalLength <= 0f) return emptyList()

        val sampleCount = max(1, maxSamples)
        return (1..sampleCount).map { index ->
            val distance = totalLength * index / (sampleCount + 1)
            val point = pointAtVisibleDistance(
                segments = visibleSegments,
                distance = distance,
            )

            CandidateTip(
                position = point.position,
                screenPoint = point.screenPoint,
                visibleFraction = (distance / totalLength).coerceIn(0f, 1f),
            )
        }
    }

    private fun pointAtVisibleDistance(
        segments: List<VisiblePolylineSegment>,
        distance: Float,
    ): ProjectedRoutePoint {
        var remaining = distance

        for (segment in segments) {
            val segmentLength = segment.length
            if (segmentLength <= 0f) continue
            if (remaining <= segmentLength) {
                val fraction = remaining / segmentLength
                return interpolate(segment.start, segment.end, fraction)
            }
            remaining -= segmentLength
        }

        return segments.last().end
    }

    private fun clipPolylineSegment(
        start: ProjectedRoutePoint,
        end: ProjectedRoutePoint,
        rect: Rect,
    ): VisiblePolylineSegment? {
        val (startFraction, endFraction) = clipRange(
            rect = rect,
            start = start.screenPoint,
            end = end.screenPoint,
        ) ?: return null
        val clippedStart = if (startFraction == 0f) start else interpolate(start, end, startFraction)
        val clippedEnd = if (endFraction == 1f) end else interpolate(start, end, endFraction)
        val length = clippedStart.screenPoint.distanceTo(clippedEnd.screenPoint)

        return if (length > 0f) {
            VisiblePolylineSegment(
                start = clippedStart,
                end = clippedEnd,
                length = length,
            )
        } else {
            null
        }
    }

    private fun interpolate(
        start: ProjectedRoutePoint,
        end: ProjectedRoutePoint,
        fraction: Float,
    ): ProjectedRoutePoint {
        return ProjectedRoutePoint(
            position = RoutePoint(
                latitude = start.position.latitude + (end.position.latitude - start.position.latitude) * fraction,
                longitude = start.position.longitude + (end.position.longitude - start.position.longitude) * fraction,
            ),
            screenPoint = Offset(
                x = start.screenPoint.x + (end.screenPoint.x - start.screenPoint.x) * fraction,
                y = start.screenPoint.y + (end.screenPoint.y - start.screenPoint.y) * fraction,
            ),
        )
    }

    private fun scoreCandidate(
        candidate: Candidate,
        request: MapCallOutRequest,
        size: IntSize,
        viewport: Rect,
        tailLengthPx: Float,
        allPolylines: List<List<Offset>>,
        placed: List<MapCallOutPlacement>,
        preferredRouteFraction: Float?,
    ): ScoredCandidate? {
        val topLeft = tipToTopLeft(
            tip = candidate.tip,
            tailSide = candidate.tailSide,
            size = size,
        )
        val bounds = Rect(topLeft, Size(size.width.toFloat(), size.height.toFloat()))
        val bodyBounds = bounds.deflate(tailLengthPx)
        val viewportRatio = viewportIntersectionRatio(bodyBounds, viewport)

        if (viewportRatio <= MIN_VIEWPORT_RATIO) return null
        if (placed.any { it.bodyBounds.inflate(CALLOUT_COLLISION_MARGIN_PX).overlaps(bodyBounds) }) return null

        val polylineCoverage = polylineCoverageRatio(
            rect = bodyBounds,
            polylines = allPolylines,
        )
        val dispersionScore = dispersionScore(
            tip = candidate.tip,
            placed = placed,
        )
        val routeSlotScore = routeSlotScore(
            candidate = candidate,
            preferredRouteFraction = preferredRouteFraction,
        )
        val stickinessScore = stickinessScore(
            candidate = candidate,
            previousPlacement = request.previousPlacement,
        )

        val score = viewportRatio * VIEWPORT_WEIGHT +
            (1f - polylineCoverage) * POLYLINE_CLEARANCE_WEIGHT +
            dispersionScore * DISPERSION_WEIGHT +
            routeSlotScore * ROUTE_SLOT_WEIGHT +
            stickinessScore

        return ScoredCandidate(
            candidate = candidate,
            topLeft = topLeft,
            size = size,
            bodyBounds = bodyBounds,
            score = score,
        )
    }

    private fun viewportIntersectionRatio(
        rect: Rect,
        viewport: Rect,
    ): Float {
        val intersection = rect.intersectOrNull(viewport) ?: return 0f
        return (intersection.area() / rect.area()).coerceIn(0f, 1f)
    }

    private fun polylineCoverageRatio(
        rect: Rect,
        polylines: List<List<Offset>>,
    ): Float {
        val coveredLength = polylines.sumOf { polyline ->
            polyline.zipWithNext().sumOf { (start, end) ->
                clippedSegmentLength(rect, start, end).toDouble()
            }
        }.toFloat()
        val diagonal = hypotFloat(rect.width, rect.height).coerceAtLeast(1f)

        return (coveredLength / diagonal).coerceIn(0f, 1f)
    }

    private fun clippedSegmentLength(
        rect: Rect,
        start: Offset,
        end: Offset,
    ): Float {
        val (startFraction, endFraction) = clipRange(
            rect = rect,
            start = start,
            end = end,
        ) ?: return 0f
        val dx = end.x - start.x
        val dy = end.y - start.y

        val clippedStart = Offset(start.x + dx * startFraction, start.y + dy * startFraction)
        val clippedEnd = Offset(start.x + dx * endFraction, start.y + dy * endFraction)
        return clippedStart.distanceTo(clippedEnd)
    }

    private fun clipRange(
        rect: Rect,
        start: Offset,
        end: Offset,
    ): Pair<Float, Float>? {
        val dx = end.x - start.x
        val dy = end.y - start.y
        var t0 = 0f
        var t1 = 1f

        fun clip(p: Float, q: Float): Boolean {
            if (p == 0f) return q >= 0f

            val r = q / p
            return when {
                p < 0f -> {
                    if (r > t1) return false
                    if (r > t0) t0 = r
                    true
                }
                else -> {
                    if (r < t0) return false
                    if (r < t1) t1 = r
                    true
                }
            }
        }

        val visible = clip(-dx, start.x - rect.left) &&
            clip(dx, rect.right - start.x) &&
            clip(-dy, start.y - rect.top) &&
            clip(dy, rect.bottom - start.y)

        return if (visible && t1 > t0) {
            t0 to t1
        } else {
            null
        }
    }

    private fun dispersionScore(
        tip: Offset,
        placed: List<MapCallOutPlacement>,
    ): Float {
        if (placed.isEmpty()) return 1f

        val minDistance = placed.minOf { placement ->
            tip.distanceTo(placement.tip)
        }
        return (minDistance / DISPERSION_TARGET_DISTANCE_PX).coerceIn(0f, 1f)
    }

    private fun routeSlotScore(
        candidate: Candidate,
        preferredRouteFraction: Float?,
    ): Float {
        val visibleFraction = candidate.visibleFraction ?: return 0f
        if (preferredRouteFraction == null) return 0f

        return (1f - abs(visibleFraction - preferredRouteFraction) / ROUTE_SLOT_SCORE_DISTANCE)
            .coerceIn(0f, 1f)
    }

    private fun stickinessScore(
        candidate: Candidate,
        previousPlacement: MapCallOutPreviousPlacement?,
    ): Float {
        if (previousPlacement == null) return 0f

        val sameTailSide = candidate.tailSide == previousPlacement.tailSide
        val samePosition = candidate.position == previousPlacement.position

        return when {
            sameTailSide && samePosition -> EXACT_PREVIOUS_REWARD
            sameTailSide -> SAME_TAIL_SIDE_REWARD
            samePosition -> SAME_POSITION_REWARD
            else -> 0f
        }
    }

    private fun tipToTopLeft(
        tip: Offset,
        tailSide: MapCallOutTailSide,
        size: IntSize,
    ): Offset = when (tailSide) {
        MapCallOutTailSide.BottomLeft -> Offset(tip.x, tip.y - size.height)
        MapCallOutTailSide.BottomRight -> Offset(tip.x - size.width, tip.y - size.height)
    }

    private fun ScoredCandidate.toPlacement(
        requestIndex: Int,
        requestId: String,
    ): MapCallOutPlacement {
        return MapCallOutPlacement(
            requestIndex = requestIndex,
            requestId = requestId,
            position = candidate.position,
            tip = candidate.tip,
            tailSide = candidate.tailSide,
            topLeft = topLeft,
            size = size,
            bodyBounds = bodyBounds,
            score = score,
        )
    }

    private fun Offset.distanceTo(other: Offset): Float {
        return hypotFloat(x - other.x, y - other.y)
    }

    private fun hypotFloat(x: Float, y: Float): Float {
        return hypot(x.toDouble(), y.toDouble()).toFloat()
    }

    private fun Rect.area(): Float {
        return width.coerceAtLeast(0f) * height.coerceAtLeast(0f)
    }

    private fun Rect.intersectOrNull(other: Rect): Rect? {
        val left = max(left, other.left)
        val top = max(top, other.top)
        val right = min(right, other.right)
        val bottom = min(bottom, other.bottom)

        return if (right > left && bottom > top) {
            Rect(left, top, right, bottom)
        } else {
            null
        }
    }

    private fun Rect.inflate(value: Float): Rect {
        return Rect(
            left = left - value,
            top = top - value,
            right = right + value,
            bottom = bottom + value,
        )
    }

    private fun Rect.deflate(value: Float): Rect {
        return Rect(
            left = left + value,
            top = top + value,
            right = right - value,
            bottom = bottom - value,
        )
    }

    @Immutable
    private data class ProjectedRoutePoint(
        val position: RoutePoint,
        val screenPoint: Offset,
    )

    @Immutable
    private data class CandidateTip(
        val position: RoutePoint,
        val screenPoint: Offset,
        val visibleFraction: Float?,
    )

    @Immutable
    private data class VisiblePolylineSegment(
        val start: ProjectedRoutePoint,
        val end: ProjectedRoutePoint,
        val length: Float,
    )

    @Immutable
    private data class Candidate(
        val position: RoutePoint,
        val tip: Offset,
        val tailSide: MapCallOutTailSide,
        val visibleFraction: Float?,
    )

    @Immutable
    private data class ScoredCandidate(
        val candidate: Candidate,
        val topLeft: Offset,
        val size: IntSize,
        val bodyBounds: Rect,
        val score: Float,
    )
}

private const val MAX_POLYLINE_SAMPLES = 24
private const val CALLOUT_COLLISION_MARGIN_PX = 8f
private const val MIN_VIEWPORT_RATIO = 0.45f
private const val MIN_ACCEPTED_SCORE = 1f
private const val VIEWPORT_WEIGHT = 40f
private const val POLYLINE_CLEARANCE_WEIGHT = 35f
private const val DISPERSION_WEIGHT = 28f
private const val DISPERSION_TARGET_DISTANCE_PX = 360f
private const val ROUTE_SLOT_WEIGHT = 45f
private const val ROUTE_SLOT_START_FRACTION = 0.2f
private const val ROUTE_SLOT_CENTER_FRACTION = 0.5f
private const val ROUTE_SLOT_END_FRACTION = 0.8f
private const val ROUTE_SLOT_SCORE_DISTANCE = 0.5f
private const val EXACT_PREVIOUS_REWARD = 6f
private const val SAME_TAIL_SIDE_REWARD = 2f
private const val SAME_POSITION_REWARD = 4f
