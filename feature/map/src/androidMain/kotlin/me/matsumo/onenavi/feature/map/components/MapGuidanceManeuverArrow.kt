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
import kotlin.math.pow
import kotlin.math.roundToInt

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
 * 案内地点矢印の生成対象。
 *
 * @param maneuverOrder 表示対象 maneuver 内の順序
 * @param maneuver 案内地点の presentation 値
 * @param targetMeters route 始点から案内地点までの距離
 */
@Immutable
private data class GuidanceArrowTarget(
    val maneuverOrder: Int,
    val maneuver: ManeuverCallout,
    val targetMeters: Double,
)

/**
 * 1 本の矢印として描画する案内地点のまとまり。
 *
 * @param targets 連結後の矢印が通過する案内地点
 */
@Immutable
private data class GuidanceArrowTargetGroup(
    val targets: ImmutableList<GuidanceArrowTarget>,
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
        val targetGroups = guidanceArrowTargets(
            maneuvers = maneuvers,
            routeMeterIndex = meterIndex,
        ).toMergedGroups()

        return targetGroups
            .mapNotNull { targetGroup ->
                spec(
                    routeId = routeId,
                    targetGroup = targetGroup,
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
        targetGroup: GuidanceArrowTargetGroup,
        routeMeterIndex: RouteMeterIndex,
        currentCumulativeMeters: Double,
        roadClassSegments: ImmutableList<RoadClassSegment>,
        fallbackRoadClass: RoadClass,
    ): MapGuidanceManeuverArrowSpec? {
        val firstTarget = targetGroup.targets.first()
        val points = guidanceArrowPoints(
            targetGroup = targetGroup,
            routeMeterIndex = routeMeterIndex,
            currentCumulativeMeters = currentCumulativeMeters,
        )
        if (points.size < MIN_GUIDANCE_ARROW_POINT_COUNT) return null

        return MapGuidanceManeuverArrowSpec(
            id = targetGroup.id(routeId = routeId),
            points = points.toImmutableList(),
            roadClass = roadClassAt(
                routeMeterIndex = routeMeterIndex,
                targetDistanceMeters = firstTarget.targetMeters,
                roadClassSegments = roadClassSegments,
                fallbackRoadClass = fallbackRoadClass,
            ),
        )
    }

    private fun guidanceArrowTargets(
        maneuvers: List<ManeuverCallout>,
        routeMeterIndex: RouteMeterIndex,
    ): List<GuidanceArrowTarget> {
        val targetManeuvers = maneuvers.take(MAX_GUIDANCE_ARROW_COUNT)
        val targets = targetManeuvers.mapIndexed { maneuverOrder, maneuver ->
            GuidanceArrowTarget(
                maneuverOrder = maneuverOrder,
                maneuver = maneuver,
                targetMeters = routeMeterIndex.coerceDistance(maneuver.geometryDistanceFromStartMeters),
            )
        }

        return targets.sortedBy { target -> target.targetMeters }
    }

    private fun List<GuidanceArrowTarget>.toMergedGroups(): List<GuidanceArrowTargetGroup> {
        if (isEmpty()) return emptyList()

        val groups = mutableListOf<GuidanceArrowTargetGroup>()
        var currentGroup = first().toTargetGroup()

        for (targetIndex in 1 until size) {
            val nextTarget = this[targetIndex]
            if (currentGroup.shouldMerge(nextTarget)) {
                currentGroup = currentGroup.merge(nextTarget)
            } else {
                groups += currentGroup
                currentGroup = nextTarget.toTargetGroup()
            }
        }

        groups += currentGroup
        return groups
    }

    private fun GuidanceArrowTarget.toTargetGroup(): GuidanceArrowTargetGroup {
        return GuidanceArrowTargetGroup(
            targets = persistentListOf(this),
        )
    }

    private fun GuidanceArrowTargetGroup.shouldMerge(target: GuidanceArrowTarget): Boolean {
        val distanceBetweenTargets = target.targetMeters - targets.last().targetMeters

        return distanceBetweenTargets < GUIDANCE_ARROW_ROUTE_LENGTH_METERS
    }

    private fun GuidanceArrowTargetGroup.merge(target: GuidanceArrowTarget): GuidanceArrowTargetGroup {
        return copy(
            targets = (targets + target).toImmutableList(),
        )
    }

    private fun GuidanceArrowTargetGroup.id(routeId: String): String {
        val firstTarget = targets.first()
        if (targets.size == 1) {
            return "guidance-arrow-$routeId-${firstTarget.maneuverOrder}-${firstTarget.maneuver.guidancePointIndex}"
        }

        val lastTarget = targets.last()
        return "guidance-arrow-$routeId-${firstTarget.maneuverOrder}-${firstTarget.maneuver.guidancePointIndex}-${lastTarget.maneuver.guidancePointIndex}"
    }

    private fun guidanceArrowPoints(
        targetGroup: GuidanceArrowTargetGroup,
        routeMeterIndex: RouteMeterIndex,
        currentCumulativeMeters: Double,
    ): List<RoutePoint> {
        val firstTarget = targetGroup.targets.first()
        val lastTarget = targetGroup.targets.last()
        val startMeters = guidanceArrowStartMeters(
            routeMeterIndex = routeMeterIndex,
            firstTargetMeters = firstTarget.targetMeters,
            currentCumulativeMeters = currentCumulativeMeters,
        )
        val endMeters = routeMeterIndex.coerceDistance(lastTarget.targetMeters + GUIDANCE_ARROW_ROUTE_EXIT_METERS)
        val boundaryMeters = buildList {
            add(startMeters)
            for (target in targetGroup.targets) {
                add(target.targetMeters)
            }
            add(endMeters)
        }

        return pointsBetweenBoundaries(
            boundaryMeters = boundaryMeters,
            routeMeterIndex = routeMeterIndex,
        )
    }

    private fun guidanceArrowStartMeters(
        routeMeterIndex: RouteMeterIndex,
        firstTargetMeters: Double,
        currentCumulativeMeters: Double,
    ): Double {
        val approachStartMeters = routeMeterIndex.coerceDistance(firstTargetMeters - GUIDANCE_ARROW_ROUTE_APPROACH_METERS)
        val currentStartMeters = routeMeterIndex.coerceDistance(currentCumulativeMeters)

        return maxOf(approachStartMeters, currentStartMeters).coerceAtMost(firstTargetMeters)
    }

    private fun pointsBetweenBoundaries(
        boundaryMeters: List<Double>,
        routeMeterIndex: RouteMeterIndex,
    ): List<RoutePoint> {
        val routePoints = mutableListOf<RoutePoint>()

        for (boundaryIndex in 0 until boundaryMeters.lastIndex) {
            val segmentPoints = routeMeterIndex.pointsBetween(
                startDistanceMeters = boundaryMeters[boundaryIndex],
                endDistanceMeters = boundaryMeters[boundaryIndex + 1],
                fallbackBearingDegrees = null,
            )
            if (routePoints.isEmpty()) {
                routePoints += segmentPoints
            } else {
                routePoints += segmentPoints.drop(1)
            }
        }

        return routePoints
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
 *
 * @param googleMap overlay 描画先の GoogleMap
 * @param guidanceState 案内中 state
 * @param cameraZoom 現在の GoogleMap zoom
 * @param modifier 空の Compose node に適用する modifier
 */
@Composable
internal fun MapGuidanceManeuverArrowEffect(
    googleMap: GoogleMap,
    guidanceState: GuidanceState.Guiding,
    cameraZoom: Float,
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
    val headScale = guidanceArrowHeadScaleForZoom(cameraZoom)
    val highwayBorderHeadIcon = rememberGuidanceManeuverArrowHeadIcon(
        color = RouteColors.maneuver(RoadClass.HIGHWAY).primary,
        insetPx = 0f,
        headScale = headScale,
    )
    val ordinaryBorderHeadIcon = rememberGuidanceManeuverArrowHeadIcon(
        color = RouteColors.maneuver(RoadClass.ORDINARY).primary,
        insetPx = 0f,
        headScale = headScale,
    )
    val bodyHeadIcon = rememberGuidanceManeuverArrowHeadIcon(
        color = Color.White,
        insetPx = guidanceArrowHeadInsetPx(headScale = headScale),
        headScale = headScale,
    )

    DisposableEffect(googleMap, specs, highwayBorderHeadIcon, ordinaryBorderHeadIcon, bodyHeadIcon) {
        val polylines = mutableListOf<Polyline>()

        for ((specIndex, spec) in specs.withIndex()) {
            val borderColor = RouteColors.maneuver(spec.roadClass).primary
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
    headScale: Float,
): BitmapDescriptor {
    val colorArgb = color.toArgb()

    return remember(colorArgb, insetPx, headScale) {
        BitmapDescriptorFactory.fromBitmap(
            createGuidanceManeuverArrowHeadBitmap(
                colorArgb = colorArgb,
                insetPx = insetPx,
                headScale = headScale,
            ),
        )
    }
}

private fun createGuidanceManeuverArrowHeadBitmap(
    colorArgb: Int,
    insetPx: Float,
    headScale: Float,
): Bitmap {
    val headWidthPx = (GUIDANCE_ARROW_BORDER_WIDTH_PX * GUIDANCE_ARROW_HEAD_WIDTH_RATIO * headScale)
        .toInt()
        .coerceAtLeast(MIN_GUIDANCE_ARROW_HEAD_SIZE_PX)
    val headHeightPx = (GUIDANCE_ARROW_BORDER_WIDTH_PX * GUIDANCE_ARROW_HEAD_HEIGHT_RATIO * headScale)
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

/**
 * route overview のような低 zoom で案内地点矢印のヘッドが過大表示されないようにする倍率を返す。
 *
 * @param zoom 現在の GoogleMap zoom
 * @return ヘッド bitmap に適用する倍率
 */
internal fun guidanceArrowHeadScaleForZoom(zoom: Float): Float {
    val zoomStepCount = (zoom * GUIDANCE_ARROW_HEAD_ZOOM_QUANTIZATION_STEPS).roundToInt()
    val quantizedZoom = zoomStepCount.toFloat() / GUIDANCE_ARROW_HEAD_ZOOM_QUANTIZATION_STEPS
    val zoomDelta = quantizedZoom - GUIDANCE_ARROW_HEAD_REFERENCE_ZOOM
    val scale = 2.0.pow((zoomDelta / GUIDANCE_ARROW_HEAD_ZOOM_SCALE_STEP).toDouble()).toFloat()

    return scale.coerceIn(
        minimumValue = GUIDANCE_ARROW_HEAD_MIN_SCALE,
        maximumValue = GUIDANCE_ARROW_HEAD_MAX_SCALE,
    )
}

private fun guidanceArrowHeadInsetPx(headScale: Float): Float {
    return (GUIDANCE_ARROW_BORDER_WIDTH_PX - GUIDANCE_ARROW_BODY_WIDTH_PX) / 2f * headScale
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

/** 近接する案内地点矢印を連結する距離しきい値。 */
private const val GUIDANCE_ARROW_ROUTE_LENGTH_METERS =
    GUIDANCE_ARROW_ROUTE_APPROACH_METERS + GUIDANCE_ARROW_ROUTE_EXIT_METERS

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

/** ヘッドを等倍表示する基準 zoom。 */
private const val GUIDANCE_ARROW_HEAD_REFERENCE_ZOOM = 17f

/** 何 zoom ごとにヘッドサイズを 2 倍 / 0.5 倍させるか。 */
private const val GUIDANCE_ARROW_HEAD_ZOOM_SCALE_STEP = 2f

/** camera move 中の overlay 再作成頻度を抑える zoom 丸め単位。 */
private const val GUIDANCE_ARROW_HEAD_ZOOM_QUANTIZATION_STEPS = 4f

/** ズームアウト時のヘッド最小倍率。body 線幅との接続を保つため 0.5 倍までにする。 */
private const val GUIDANCE_ARROW_HEAD_MIN_SCALE = 0.5f

/** ズームイン時のヘッド最大倍率。通常案内時の見た目を上限にする。 */
private const val GUIDANCE_ARROW_HEAD_MAX_SCALE = 1f
