package me.matsumo.onenavi.core.ui.navigation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.ImmutableList
import me.matsumo.onenavi.core.model.CongestionSegment
import me.matsumo.onenavi.core.model.CongestionSeverity
import me.matsumo.onenavi.core.model.RoadClass
import me.matsumo.onenavi.core.model.RoadClassSegment
import me.matsumo.onenavi.core.model.RoutePoint
import me.matsumo.onenavi.core.resource.Res
import me.matsumo.onenavi.core.resource.ic_vehicle_puck
import me.matsumo.onenavi.core.ui.theme.RouteColors
import org.jetbrains.compose.resources.painterResource
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * ルート全体の道路種別と渋滞区間を 1 本の横線として表示するバー。
 */
@Composable
fun RouteTrafficBar(
    geometry: ImmutableList<RoutePoint>,
    currentCumulativeMeters: Double,
    roadClassSegments: ImmutableList<RoadClassSegment>,
    congestionSegments: ImmutableList<CongestionSegment>,
    modifier: Modifier = Modifier,
) {
    val layout = remember(
        geometry,
        currentCumulativeMeters,
        roadClassSegments,
        congestionSegments,
    ) {
        calculateRouteTrafficBarLayout(
            geometry = geometry,
            currentCumulativeMeters = currentCumulativeMeters,
            roadClassSegments = roadClassSegments,
            congestionSegments = congestionSegments,
        )
    }
    val density = LocalDensity.current

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(RouteTrafficBarHeight),
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(RouteTrafficBarHeight),
        ) {
            val centerY = size.height / 2f
            val strokeWidth = RouteTrafficBarStrokeWidth.toPx()
            for (segment in layout.segments) {
                val startX = size.width * segment.startRatio
                val endX = size.width * segment.endRatio
                if (endX <= startX) continue

                drawLine(
                    color = segment.kind.toColor(),
                    start = Offset(startX, centerY),
                    end = Offset(endX, centerY),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round,
                )
            }
        }

        val markerSizePx = with(density) { RouteTrafficBarMarkerSize.toPx() }
        val markerCenterX = constraints.maxWidth * layout.markerRatio
        val markerOffsetX = (markerCenterX - markerSizePx / 2f).roundToInt()

        Image(
            modifier = Modifier
                .offset { IntOffset(markerOffsetX, 0) }
                .rotate(RouteTrafficBarMarkerRotationDegrees)
                .size(RouteTrafficBarMarkerSize),
            painter = painterResource(Res.drawable.ic_vehicle_puck),
            contentDescription = null,
        )
    }
}

/**
 * ルート渋滞バーの描画用レイアウト。
 *
 * @param markerRatio ルート全体に対する現在地マーカー位置
 * @param segments 描画順に並んだ線分
 */
@Immutable
internal data class RouteTrafficBarLayout(
    val markerRatio: Float,
    val segments: List<RouteTrafficBarSegment>,
)

/**
 * ルート渋滞バーの 1 線分。
 *
 * @param startRatio ルート全体に対する開始比率
 * @param endRatio ルート全体に対する終了比率
 * @param kind 線分の表示種別
 */
@Immutable
internal data class RouteTrafficBarSegment(
    val startRatio: Float,
    val endRatio: Float,
    val kind: RouteTrafficBarSegmentKind,
)

/**
 * ルート渋滞バーの線分種別。
 */
internal enum class RouteTrafficBarSegmentKind {
    /** 通過済み区間。 */
    PASSED,

    /** 高速道路・有料道路の通常区間。 */
    HIGHWAY,

    /** 一般道の通常区間。 */
    ORDINARY,

    /** やや渋滞している区間。 */
    SLOW,

    /** 強い渋滞区間。 */
    TRAFFIC_JAM,
}

internal fun calculateRouteTrafficBarLayout(
    geometry: ImmutableList<RoutePoint>,
    currentCumulativeMeters: Double,
    roadClassSegments: ImmutableList<RoadClassSegment>,
    congestionSegments: ImmutableList<CongestionSegment>,
): RouteTrafficBarLayout {
    val cumulativeMeters = cumulativeGeometryMeters(geometry)
    val routeTotalMeters = cumulativeMeters.lastOrNull()?.takeIf { meters -> meters > 0.0 } ?: return RouteTrafficBarLayout(
        markerRatio = 0f,
        segments = emptyList(),
    )
    val currentMeters = currentCumulativeMeters.validMeters().coerceIn(0.0, routeTotalMeters)
    val segments = mutableListOf<RouteTrafficBarSegment>()

    addRatioSegment(
        segments = segments,
        startMeters = 0.0,
        endMeters = currentMeters,
        routeTotalMeters = routeTotalMeters,
        kind = RouteTrafficBarSegmentKind.PASSED,
    )
    addRoadClassSegments(
        segments = segments,
        cumulativeMeters = cumulativeMeters,
        routeTotalMeters = routeTotalMeters,
        currentMeters = currentMeters,
        roadClassSegments = roadClassSegments,
    )
    addCongestionSegments(
        segments = segments,
        routeTotalMeters = routeTotalMeters,
        currentMeters = currentMeters,
        congestionSegments = congestionSegments,
    )

    return RouteTrafficBarLayout(
        markerRatio = (currentMeters / routeTotalMeters).toFloat().coerceIn(0f, 1f),
        segments = segments,
    )
}

private fun addRoadClassSegments(
    segments: MutableList<RouteTrafficBarSegment>,
    cumulativeMeters: DoubleArray,
    routeTotalMeters: Double,
    currentMeters: Double,
    roadClassSegments: ImmutableList<RoadClassSegment>,
) {
    val roadSegments = roadClassSegments.mapNotNull { segment -> segment.toRouteTrafficRoadSegment(cumulativeMeters) }
    val usableSegments = roadSegments.sortedBy { segment -> segment.startMeters }

    if (usableSegments.isEmpty()) {
        addRatioSegment(
            segments = segments,
            startMeters = currentMeters,
            endMeters = routeTotalMeters,
            routeTotalMeters = routeTotalMeters,
            kind = RouteTrafficBarSegmentKind.ORDINARY,
        )
        return
    }

    var cursorMeters = 0.0
    for (segment in usableSegments) {
        if (segment.startMeters > cursorMeters) {
            addClippedRatioSegment(
                segments = segments,
                startMeters = cursorMeters,
                endMeters = segment.startMeters,
                routeTotalMeters = routeTotalMeters,
                currentMeters = currentMeters,
                kind = RouteTrafficBarSegmentKind.ORDINARY,
            )
        }

        val segmentStartMeters = segment.startMeters.coerceAtLeast(cursorMeters)
        addClippedRatioSegment(
            segments = segments,
            startMeters = segmentStartMeters,
            endMeters = segment.endMeters,
            routeTotalMeters = routeTotalMeters,
            currentMeters = currentMeters,
            kind = segment.roadClass.toSegmentKind(),
        )
        cursorMeters = cursorMeters.coerceAtLeast(segment.endMeters)
    }

    if (cursorMeters < routeTotalMeters) {
        addClippedRatioSegment(
            segments = segments,
            startMeters = cursorMeters,
            endMeters = routeTotalMeters,
            routeTotalMeters = routeTotalMeters,
            currentMeters = currentMeters,
            kind = RouteTrafficBarSegmentKind.ORDINARY,
        )
    }
}

private fun addCongestionSegments(
    segments: MutableList<RouteTrafficBarSegment>,
    routeTotalMeters: Double,
    currentMeters: Double,
    congestionSegments: ImmutableList<CongestionSegment>,
) {
    for (segment in congestionSegments) {
        val kind = segment.severity.toSegmentKind() ?: continue
        addClippedRatioSegment(
            segments = segments,
            startMeters = segment.startDistanceMeters.validMeters(),
            endMeters = segment.endDistanceMeters.validMeters(),
            routeTotalMeters = routeTotalMeters,
            currentMeters = currentMeters,
            kind = kind,
        )
    }
}

private fun addClippedRatioSegment(
    segments: MutableList<RouteTrafficBarSegment>,
    startMeters: Double,
    endMeters: Double,
    routeTotalMeters: Double,
    currentMeters: Double,
    kind: RouteTrafficBarSegmentKind,
) {
    addRatioSegment(
        segments = segments,
        startMeters = startMeters.coerceAtLeast(currentMeters),
        endMeters = endMeters,
        routeTotalMeters = routeTotalMeters,
        kind = kind,
    )
}

private fun addRatioSegment(
    segments: MutableList<RouteTrafficBarSegment>,
    startMeters: Double,
    endMeters: Double,
    routeTotalMeters: Double,
    kind: RouteTrafficBarSegmentKind,
) {
    val coercedStartMeters = startMeters.validMeters().coerceIn(0.0, routeTotalMeters)
    val coercedEndMeters = endMeters.validMeters().coerceIn(0.0, routeTotalMeters)
    if (coercedEndMeters <= coercedStartMeters) return

    segments += RouteTrafficBarSegment(
        startRatio = (coercedStartMeters / routeTotalMeters).toFloat().coerceIn(0f, 1f),
        endRatio = (coercedEndMeters / routeTotalMeters).toFloat().coerceIn(0f, 1f),
        kind = kind,
    )
}

private fun RoadClassSegment.toRouteTrafficRoadSegment(cumulativeMeters: DoubleArray): RouteTrafficRoadSegment? {
    val startIndex = startPointIndex.coerceIn(0, cumulativeMeters.lastIndex)
    val endIndex = endPointIndex.coerceIn(startIndex, cumulativeMeters.lastIndex)
    val startMeters = cumulativeMeters[startIndex]
    val endMeters = cumulativeMeters[endIndex]
    if (endMeters <= startMeters) return null

    return RouteTrafficRoadSegment(
        startMeters = startMeters,
        endMeters = endMeters,
        roadClass = roadClass,
    )
}

/**
 * 道路種別区間の距離表現。
 *
 * @param startMeters ルート始点から区間開始までの累積距離
 * @param endMeters ルート始点から区間終了までの累積距離
 * @param roadClass 区間の道路種別
 */
@Immutable
private data class RouteTrafficRoadSegment(
    val startMeters: Double,
    val endMeters: Double,
    val roadClass: RoadClass,
)

private fun cumulativeGeometryMeters(geometry: ImmutableList<RoutePoint>): DoubleArray {
    if (geometry.isEmpty()) return DoubleArray(0)

    val cumulativeMeters = DoubleArray(geometry.size)
    for (index in 1 until geometry.size) {
        val previousMeters = cumulativeMeters[index - 1]
        val stepMeters = haversineMeters(geometry[index - 1], geometry[index])
        cumulativeMeters[index] = previousMeters + stepMeters
    }
    return cumulativeMeters
}

private fun haversineMeters(from: RoutePoint, to: RoutePoint): Double {
    val fromLatitudeRadians = Math.toRadians(from.latitude)
    val toLatitudeRadians = Math.toRadians(to.latitude)
    val latitudeDeltaRadians = Math.toRadians(to.latitude - from.latitude)
    val longitudeDeltaRadians = Math.toRadians(to.longitude - from.longitude)
    val halfLatitudeSinSquared = sin(latitudeDeltaRadians / 2.0) * sin(latitudeDeltaRadians / 2.0)
    val halfLongitudeSinSquared = sin(longitudeDeltaRadians / 2.0) * sin(longitudeDeltaRadians / 2.0)
    val haversine = halfLatitudeSinSquared + cos(fromLatitudeRadians) * cos(toLatitudeRadians) * halfLongitudeSinSquared
    return EarthRadiusMeters * 2.0 * atan2(sqrt(haversine), sqrt(1.0 - haversine))
}

private fun RouteTrafficBarSegmentKind.toColor(): Color = when (this) {
    RouteTrafficBarSegmentKind.PASSED -> PassedRouteColor
    RouteTrafficBarSegmentKind.HIGHWAY -> RouteColors.polyline(RoadClass.HIGHWAY).body
    RouteTrafficBarSegmentKind.ORDINARY -> RouteColors.polyline(RoadClass.ORDINARY).body
    RouteTrafficBarSegmentKind.SLOW -> SlowCongestionColor
    RouteTrafficBarSegmentKind.TRAFFIC_JAM -> TrafficJamColor
}

private fun RoadClass.toSegmentKind(): RouteTrafficBarSegmentKind = when (this) {
    RoadClass.HIGHWAY -> RouteTrafficBarSegmentKind.HIGHWAY
    RoadClass.ORDINARY -> RouteTrafficBarSegmentKind.ORDINARY
}

private fun CongestionSeverity.toSegmentKind(): RouteTrafficBarSegmentKind? = when (this) {
    CongestionSeverity.SLOW -> RouteTrafficBarSegmentKind.SLOW
    CongestionSeverity.TRAFFIC_JAM -> RouteTrafficBarSegmentKind.TRAFFIC_JAM
    CongestionSeverity.NORMAL, CongestionSeverity.UNKNOWN -> null
}

private fun Double.validMeters(): Double = if (isFinite()) this else 0.0

/** haversine 距離計算で使う地球半径メートル。 */
private const val EarthRadiusMeters = 6_371_000.0

/** ルート渋滞バーの高さ。 */
private val RouteTrafficBarHeight = 24.dp

/** ルート渋滞バーの線幅。 */
private val RouteTrafficBarStrokeWidth = 5.dp

/** 現在地マーカーのサイズ。 */
private val RouteTrafficBarMarkerSize = 24.dp

/** 横バー上で進行方向を右向きにする回転角度。 */
private const val RouteTrafficBarMarkerRotationDegrees = 90f

/** 通過済み区間の線色。 */
private val PassedRouteColor = Color(0xFFC4C7C5)

/** やや渋滞区間の線色。 */
private val SlowCongestionColor = Color(0xFFFB8C00)

/** 強い渋滞区間の線色。 */
private val TrafficJamColor = Color(0xFFE53935)
