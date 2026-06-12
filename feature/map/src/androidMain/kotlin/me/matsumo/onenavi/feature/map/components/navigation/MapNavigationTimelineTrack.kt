package me.matsumo.onenavi.feature.map.components.navigation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import me.matsumo.onenavi.core.model.CongestionSegment
import me.matsumo.onenavi.feature.map.components.routeCongestionBodyColorOf
import kotlin.math.abs

/**
 * タイムライン縦軸に重ねる渋滞帯。
 *
 * @property startFraction 行内の開始位置。0 が上端、1 が下端。
 * @property endFraction 行内の終了位置。0 が上端、1 が下端。
 * @property color 渋滞度合いに対応する表示色。
 */
@Immutable
internal data class TimelineCongestionBand(
    val startFraction: Float,
    val endFraction: Float,
    val color: Color,
)

/**
 * タイムライン縦軸。
 *
 * ベースは道路種別色で塗り、SLOW / TRAFFIC_JAM の渋滞区間だけを上に重ねる。
 */
@Composable
internal fun MapNavigationTimelineTrack(
    baseColor: Color,
    congestionBands: ImmutableList<TimelineCongestionBand>,
    modifier: Modifier = Modifier,
) {
    val minBandHeightPx = with(LocalDensity.current) {
        TimelineCongestionMinBandHeight.toPx()
    }

    Canvas(
        modifier = modifier.width(TimelineTrackWidth),
    ) {
        drawTimelineTrack(
            baseColor = baseColor,
            congestionBands = congestionBands,
            minBandHeightPx = minBandHeightPx,
        )
    }
}

internal fun buildTimelineCongestionBands(
    congestionSegments: ImmutableList<CongestionSegment>,
    rowStartMeters: Double,
    rowEndMeters: Double,
): ImmutableList<TimelineCongestionBand> {
    val rowDistanceMeters = rowEndMeters - rowStartMeters
    if (abs(rowDistanceMeters) <= TIMELINE_DISTANCE_EPSILON_METERS) return persistentListOf()

    val rowMinMeters = minOf(rowStartMeters, rowEndMeters)
    val rowMaxMeters = maxOf(rowStartMeters, rowEndMeters)

    return congestionSegments
        .mapNotNull { segment ->
            segment.toTimelineCongestionBand(
                rowStartMeters = rowStartMeters,
                rowEndMeters = rowEndMeters,
                rowMinMeters = rowMinMeters,
                rowMaxMeters = rowMaxMeters,
            )
        }
        .toPersistentList()
}

private fun CongestionSegment.toTimelineCongestionBand(
    rowStartMeters: Double,
    rowEndMeters: Double,
    rowMinMeters: Double,
    rowMaxMeters: Double,
): TimelineCongestionBand? {
    val bandColor = routeCongestionBodyColorOf(severity) ?: return null
    val segmentStartMeters = minOf(startDistanceMeters, endDistanceMeters)
    val segmentEndMeters = maxOf(startDistanceMeters, endDistanceMeters)

    val isPointCongestion = abs(segmentEndMeters - segmentStartMeters) <= TIMELINE_DISTANCE_EPSILON_METERS
    if (isPointCongestion) {
        return pointCongestionBand(
            meters = segmentStartMeters,
            rowStartMeters = rowStartMeters,
            rowEndMeters = rowEndMeters,
            rowMinMeters = rowMinMeters,
            rowMaxMeters = rowMaxMeters,
            bandColor = bandColor,
        )
    }

    val overlapStartMeters = maxOf(rowMinMeters, segmentStartMeters)
    val overlapEndMeters = minOf(rowMaxMeters, segmentEndMeters)
    if (overlapEndMeters <= overlapStartMeters) return null

    val startFraction = timelineFractionOf(overlapStartMeters, rowStartMeters, rowEndMeters)
    val endFraction = timelineFractionOf(overlapEndMeters, rowStartMeters, rowEndMeters)

    return TimelineCongestionBand(
        startFraction = minOf(startFraction, endFraction),
        endFraction = maxOf(startFraction, endFraction),
        color = bandColor,
    )
}

private fun pointCongestionBand(
    meters: Double,
    rowStartMeters: Double,
    rowEndMeters: Double,
    rowMinMeters: Double,
    rowMaxMeters: Double,
    bandColor: Color,
): TimelineCongestionBand? {
    val isOutsideRow = meters < rowMinMeters || meters > rowMaxMeters
    if (isOutsideRow) return null

    val fraction = timelineFractionOf(meters, rowStartMeters, rowEndMeters)
    return TimelineCongestionBand(
        startFraction = fraction,
        endFraction = fraction,
        color = bandColor,
    )
}

private fun timelineFractionOf(
    meters: Double,
    rowStartMeters: Double,
    rowEndMeters: Double,
): Float {
    val rowDistanceMeters = rowEndMeters - rowStartMeters
    val fraction = (meters - rowStartMeters) / rowDistanceMeters
    return fraction
        .coerceIn(0.0, 1.0)
        .toFloat()
}

private fun DrawScope.drawTimelineTrack(
    baseColor: Color,
    congestionBands: ImmutableList<TimelineCongestionBand>,
    minBandHeightPx: Float,
) {
    if (size.width <= 0f || size.height <= 0f) return

    drawRect(color = baseColor)

    for (band in congestionBands) {
        drawCongestionBand(
            band = band,
            minBandHeightPx = minBandHeightPx,
        )
    }
}

private fun DrawScope.drawCongestionBand(band: TimelineCongestionBand, minBandHeightPx: Float) {
    val topFraction = minOf(band.startFraction, band.endFraction).coerceIn(0f, 1f)
    val bottomFraction = maxOf(band.startFraction, band.endFraction).coerceIn(0f, 1f)
    val bandTop = size.height * topFraction
    val bandBottom = size.height * bottomFraction
    val rawBandHeight = bandBottom - bandTop
    val bandHeight = rawBandHeight
        .coerceAtLeast(minBandHeightPx)
        .coerceAtMost(size.height)
    val bandCenter = (bandTop + bandBottom) / 2f
    val adjustedTop = (bandCenter - bandHeight / 2f)
        .coerceIn(0f, size.height - bandHeight)

    drawRect(
        color = band.color,
        topLeft = Offset(0f, adjustedTop),
        size = Size(size.width, bandHeight),
    )
}

/** タイムライン本体の幅。 */
private val TimelineTrackWidth = 8.dp

/** 短い渋滞が潰れないように保証する最小表示高さ。 */
private val TimelineCongestionMinBandHeight = 4.dp

/** 同一点区間とみなす距離差。 */
private const val TIMELINE_DISTANCE_EPSILON_METERS: Double = 0.001
