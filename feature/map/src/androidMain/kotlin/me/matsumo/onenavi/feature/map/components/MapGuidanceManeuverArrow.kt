package me.matsumo.onenavi.feature.map.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.createBitmap
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CustomCap
import com.google.android.gms.maps.model.JointType
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions
import com.google.android.gms.maps.model.RoundCap
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import me.matsumo.onenavi.core.model.RoadClass
import me.matsumo.onenavi.core.model.RoadClassSegment
import me.matsumo.onenavi.core.model.RoutePoint
import me.matsumo.onenavi.core.navigation.newguidance.model.GuidanceState
import me.matsumo.onenavi.core.navigation.newguidance.presentation.ManeuverCallout
import me.matsumo.onenavi.core.ui.theme.RouteColors
import me.matsumo.onenavi.feature.map.state.RouteMeterIndex

/**
 * 案内地点前後に描画する route 上の矢印。
 *
 * @param id GoogleMap overlay の安定した識別子
 * @param points 矢印を描画する route 点列
 * @param roadClass 枠線色を決める道路種別
 */
@Immutable
internal data class MapGuidanceManeuverArrowSpec(
    val id: String,
    val points: ImmutableList<RoutePoint>,
    val roadClass: RoadClass,
)

/**
 * 案内地点矢印の route window と道路種別を組み立てる。
 */
internal object MapGuidanceManeuverArrowGeometry {

    fun specs(
        routeId: String,
        maneuvers: List<ManeuverCallout>,
        routeMeterIndex: RouteMeterIndex?,
        currentCumulativeMeters: Double,
        roadClassSegments: ImmutableList<RoadClassSegment>,
        fallbackRoadClass: RoadClass,
    ): ImmutableList<MapGuidanceManeuverArrowSpec> {
        val meterIndex = routeMeterIndex ?: return persistentListOf()
        val targetManeuvers = maneuvers.take(MAX_GUIDANCE_ARROW_COUNT)

        return targetManeuvers
            .mapIndexedNotNull { maneuverOrder, maneuver ->
                spec(
                    routeId = routeId,
                    maneuverOrder = maneuverOrder,
                    maneuver = maneuver,
                    routeMeterIndex = meterIndex,
                    currentCumulativeMeters = currentCumulativeMeters,
                    roadClassSegments = roadClassSegments,
                    fallbackRoadClass = fallbackRoadClass,
                )
            }
            .toImmutableList()
    }

    private fun spec(
        routeId: String,
        maneuverOrder: Int,
        maneuver: ManeuverCallout,
        routeMeterIndex: RouteMeterIndex,
        currentCumulativeMeters: Double,
        roadClassSegments: ImmutableList<RoadClassSegment>,
        fallbackRoadClass: RoadClass,
    ): MapGuidanceManeuverArrowSpec? {
        val targetMeters = routeMeterIndex.coerceDistance(maneuver.geometryDistanceFromStartMeters)
        val points = routeMeterIndex.pointsAroundTarget(
            currentDistanceMeters = currentCumulativeMeters,
            targetDistanceMeters = targetMeters,
            approachMeters = GUIDANCE_ARROW_ROUTE_APPROACH_METERS,
            exitMeters = GUIDANCE_ARROW_ROUTE_EXIT_METERS,
            fallbackBearingDegrees = null,
        )
        if (points.size < MIN_GUIDANCE_ARROW_POINT_COUNT) return null

        return MapGuidanceManeuverArrowSpec(
            id = "guidance-arrow-$routeId-$maneuverOrder-${maneuver.guidancePointIndex}",
            points = points.toImmutableList(),
            roadClass = roadClassAt(
                routeMeterIndex = routeMeterIndex,
                targetDistanceMeters = targetMeters,
                roadClassSegments = roadClassSegments,
                fallbackRoadClass = fallbackRoadClass,
            ),
        )
    }

    private fun roadClassAt(
        routeMeterIndex: RouteMeterIndex,
        targetDistanceMeters: Double,
        roadClassSegments: ImmutableList<RoadClassSegment>,
        fallbackRoadClass: RoadClass,
    ): RoadClass {
        val lookaheadMeters = routeMeterIndex.coerceDistance(targetDistanceMeters + GUIDANCE_ARROW_ROAD_CLASS_LOOKAHEAD_METERS)
        val segmentIndex = routeMeterIndex.segmentIndexAt(lookaheadMeters)
        val roadClassSegment = roadClassSegments.firstOrNull { segment ->
            val isAfterStart = segmentIndex >= segment.startPointIndex
            val isBeforeEnd = segmentIndex < segment.endPointIndex

            isAfterStart && isBeforeEnd
        }

        return roadClassSegment?.roadClass ?: fallbackRoadClass
    }
}

/**
 * 案内地点前後の polyline 上に、白背景と道路種別色の枠線を持つ矢印を描画する。
 */
@Composable
internal fun MapGuidanceManeuverArrowEffect(
    googleMap: GoogleMap,
    guidanceState: GuidanceState.Guiding,
    modifier: Modifier = Modifier,
) {
    val maneuvers = listOfNotNull(
        guidanceState.presentation.nextManeuver,
        guidanceState.presentation.followupManeuver,
    )
    val routeMeterIndex = remember(guidanceState.route.id, guidanceState.route.geometry) {
        RouteMeterIndex.from(guidanceState.route.geometry)
    }
    val specs = remember(
        guidanceState.route.id,
        maneuvers,
        routeMeterIndex,
        guidanceState.progress.currentCumulativeMeters,
        guidanceState.route.roadClassSegments,
        guidanceState.progress.currentRoadClass,
    ) {
        MapGuidanceManeuverArrowGeometry.specs(
            routeId = guidanceState.route.id,
            maneuvers = maneuvers,
            routeMeterIndex = routeMeterIndex,
            currentCumulativeMeters = guidanceState.progress.currentCumulativeMeters,
            roadClassSegments = guidanceState.route.roadClassSegments,
            fallbackRoadClass = guidanceState.progress.currentRoadClass,
        )
    }
    val highwayBorderHeadIcon = rememberGuidanceManeuverArrowHeadIcon(
        color = RouteColors.polyline(RoadClass.HIGHWAY).border,
        insetPx = 0f,
    )
    val ordinaryBorderHeadIcon = rememberGuidanceManeuverArrowHeadIcon(
        color = RouteColors.polyline(RoadClass.ORDINARY).border,
        insetPx = 0f,
    )
    val bodyHeadIcon = rememberGuidanceManeuverArrowHeadIcon(
        color = Color.White,
        insetPx = guidanceArrowHeadInsetPx(),
    )

    DisposableEffect(googleMap, specs, highwayBorderHeadIcon, ordinaryBorderHeadIcon, bodyHeadIcon) {
        val polylines = mutableListOf<Polyline>()

        for ((specIndex, spec) in specs.withIndex()) {
            val borderColor = RouteColors.polyline(spec.roadClass).border
            val borderHeadIcon = headIconFor(
                roadClass = spec.roadClass,
                highwayHeadIcon = highwayBorderHeadIcon,
                ordinaryHeadIcon = ordinaryBorderHeadIcon,
            )
            val zIndex = GUIDANCE_ARROW_Z_INDEX_BASE - specIndex * GUIDANCE_ARROW_Z_INDEX_STEP
            val latLngPoints = spec.points.toLatLngPoints()

            polylines += googleMap.addPolyline(
                PolylineOptions()
                    .addAll(latLngPoints)
                    .color(borderColor.toArgb())
                    .width(GUIDANCE_ARROW_BORDER_WIDTH_PX)
                    .zIndex(zIndex)
                    .jointType(JointType.ROUND)
                    .startCap(RoundCap())
                    .endCap(CustomCap(borderHeadIcon, GUIDANCE_ARROW_BORDER_WIDTH_PX)),
            )
            polylines += googleMap.addPolyline(
                PolylineOptions()
                    .addAll(latLngPoints)
                    .color(Color.White.toArgb())
                    .width(GUIDANCE_ARROW_BODY_WIDTH_PX)
                    .zIndex(zIndex + GUIDANCE_ARROW_BODY_Z_OFFSET)
                    .jointType(JointType.ROUND)
                    .startCap(RoundCap())
                    .endCap(CustomCap(bodyHeadIcon, GUIDANCE_ARROW_BODY_WIDTH_PX)),
            )
        }

        onDispose {
            for (polyline in polylines) {
                polyline.remove()
            }
        }
    }

    Box(modifier = modifier)
}

@Composable
private fun rememberGuidanceManeuverArrowHeadIcon(
    color: Color,
    insetPx: Float,
): BitmapDescriptor {
    val colorArgb = color.toArgb()

    return remember(colorArgb, insetPx) {
        BitmapDescriptorFactory.fromBitmap(
            createGuidanceManeuverArrowHeadBitmap(
                colorArgb = colorArgb,
                insetPx = insetPx,
            ),
        )
    }
}

private fun createGuidanceManeuverArrowHeadBitmap(
    colorArgb: Int,
    insetPx: Float,
): Bitmap {
    val headWidthPx = (GUIDANCE_ARROW_BORDER_WIDTH_PX * GUIDANCE_ARROW_HEAD_WIDTH_RATIO)
        .toInt()
        .coerceAtLeast(MIN_GUIDANCE_ARROW_HEAD_SIZE_PX)
    val headHeightPx = (GUIDANCE_ARROW_BORDER_WIDTH_PX * GUIDANCE_ARROW_HEAD_HEIGHT_RATIO)
        .toInt()
        .coerceAtLeast(MIN_GUIDANCE_ARROW_HEAD_SIZE_PX)
    val bitmap = createBitmap(
        width = headWidthPx,
        height = headHeightPx,
    )
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = colorArgb
    }
    val path = guidanceArrowHeadPath(
        headWidthPx = headWidthPx,
        headHeightPx = headHeightPx,
        insetPx = insetPx,
    )

    Canvas(bitmap).drawPath(path, paint)
    return bitmap
}

private fun guidanceArrowHeadPath(
    headWidthPx: Int,
    headHeightPx: Int,
    insetPx: Float,
): Path {
    val centerX = headWidthPx / 2f
    val tipY = insetPx.coerceAtMost(headHeightPx / 2f)
    val baseY = headHeightPx.toFloat()
    val leftX = insetPx
    val rightX = headWidthPx - insetPx

    return Path().apply {
        moveTo(centerX, tipY)
        lineTo(rightX, baseY)
        lineTo(leftX, baseY)
        close()
    }
}

private fun headIconFor(
    roadClass: RoadClass,
    highwayHeadIcon: BitmapDescriptor,
    ordinaryHeadIcon: BitmapDescriptor,
): BitmapDescriptor = when (roadClass) {
    RoadClass.HIGHWAY -> highwayHeadIcon
    RoadClass.ORDINARY -> ordinaryHeadIcon
}

private fun ImmutableList<RoutePoint>.toLatLngPoints(): List<LatLng> {
    return map { point ->
        LatLng(
            point.latitude,
            point.longitude,
        )
    }
}

private fun guidanceArrowHeadInsetPx(): Float {
    return (GUIDANCE_ARROW_BORDER_WIDTH_PX - GUIDANCE_ARROW_BODY_WIDTH_PX) / 2f
}

/** 地図上に同時表示する案内地点矢印の最大数。CallOut と同じく次 / その次までにする。 */
private const val MAX_GUIDANCE_ARROW_COUNT = 2

/** GoogleMap の polyline として描画できる最小点数。 */
private const val MIN_GUIDANCE_ARROW_POINT_COUNT = 2

/** head bitmap が 0px にならないようにする最小サイズ。 */
private const val MIN_GUIDANCE_ARROW_HEAD_SIZE_PX = 1

/** 案内地点手前側に矢印を伸ばす距離。 */
private const val GUIDANCE_ARROW_ROUTE_APPROACH_METERS = 60.0

/** 案内地点通過後に矢印を伸ばす距離。 */
private const val GUIDANCE_ARROW_ROUTE_EXIT_METERS = 60.0

/** 枠線色に使う道路種別を、案内地点直後の区間から判定するための先読み距離。 */
private const val GUIDANCE_ARROW_ROAD_CLASS_LOOKAHEAD_METERS = 8.0

/** 矢印外側の道路種別色 polyline 幅。 */
private const val GUIDANCE_ARROW_BORDER_WIDTH_PX = 30f

/** 矢印内側の白背景 polyline 幅。 */
private const val GUIDANCE_ARROW_BODY_WIDTH_PX = 20f

/** 矢印外側 polyline の zIndex。route 本体より前、自車や CallOut より後ろに置く。 */
private const val GUIDANCE_ARROW_Z_INDEX_BASE = 40f

/** 複数矢印を重ねたとき、次の案内地点を前面に保つための zIndex 差分。 */
private const val GUIDANCE_ARROW_Z_INDEX_STEP = 0.4f

/** 白背景 polyline を外側 polyline より前面に出す zIndex 差分。 */
private const val GUIDANCE_ARROW_BODY_Z_OFFSET = 0.1f

/** polyline 幅に対する矢印ヘッド bitmap の横幅倍率。 */
private const val GUIDANCE_ARROW_HEAD_WIDTH_RATIO = 2.0f

/** polyline 幅に対する矢印ヘッド bitmap の高さ倍率。 */
private const val GUIDANCE_ARROW_HEAD_HEIGHT_RATIO = 2.0f
