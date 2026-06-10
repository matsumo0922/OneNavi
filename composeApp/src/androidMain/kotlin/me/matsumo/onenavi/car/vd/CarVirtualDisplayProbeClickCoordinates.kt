package me.matsumo.onenavi.car.vd

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset

/** split 時に observed frame 起点の座標を Surface 全体へ戻す click 候補名。 */
internal const val CLICK_COORDINATE_OBSERVED_OFFSET_LABEL = "observedOffset"

/** Android Auto callback から届いた raw Surface 座標の click 候補名。 */
internal const val CLICK_COORDINATE_SURFACE_LABEL = "surface"

/** OneNavi の observed frame 左上を原点にした click 候補名。 */
internal const val CLICK_COORDINATE_OBSERVED_LABEL = "observed"

/** Android Auto host visible area 左上を原点にした click 候補名。 */
internal const val CLICK_COORDINATE_HOST_VISIBLE_LABEL = "hostVisible"

/** split 時に host visible 幅で X 座標を拡縮した click 候補名。 */
internal const val CLICK_COORDINATE_VISIBLE_SCALED_LABEL = "visibleScaled"

/** MotionEvent fallback で click 注入したことを示す結果ラベルの接頭辞。 */
internal const val CLICK_COORDINATE_MOTION_EVENT_PREFIX = "motionEvent"

/** VD click 注入で試す座標候補。 */
@Immutable
internal data class CarVirtualDisplayProbeClickCoordinateCandidate(
    val label: String,
    val point: Offset,
)

/** VD click 注入で実際に採用した座標。 */
@Immutable
internal data class CarVirtualDisplayProbeClickCoordinateResult(
    val label: String,
    val point: Offset,
)

internal fun CarVirtualDisplayProbeInputState.createCarVirtualDisplayProbeClickCoordinateCandidates(viewport: CarVirtualDisplayProbeViewport): List<CarVirtualDisplayProbeClickCoordinateCandidate> {
    val inputSurfaceX = surfaceX ?: return emptyList()
    val inputSurfaceY = surfaceY ?: return emptyList()

    if (kind != CarVirtualDisplayProbeInputKind.Click) {
        return emptyList()
    }

    return createCarVirtualDisplayProbeClickCoordinateCandidates(
        viewport = viewport,
        surfaceX = inputSurfaceX,
        surfaceY = inputSurfaceY,
        observedFrameX = observedFrameX,
        observedFrameY = observedFrameY,
        hostVisibleX = hostVisibleX,
        hostVisibleY = hostVisibleY,
    )
}

internal fun CarVirtualDisplayProbeInputState.resolveCarVirtualDisplayProbeClickDispatchCoordinate(viewport: CarVirtualDisplayProbeViewport): CarVirtualDisplayProbeClickCoordinateCandidate? {
    val candidates = createCarVirtualDisplayProbeClickCoordinateCandidates(viewport)

    return candidates.findClickCoordinate(label = CLICK_COORDINATE_OBSERVED_OFFSET_LABEL)
        ?: candidates.findClickCoordinate(label = CLICK_COORDINATE_SURFACE_LABEL)
}

internal fun CarVirtualDisplayProbeViewport.containsClickDispatchCoordinate(candidate: CarVirtualDisplayProbeClickCoordinateCandidate): Boolean {
    val viewportObservedFrame = observedFrame
    val point = candidate.point
    val isAfterLeft = point.x >= viewportObservedFrame.left.toFloat()
    val isBeforeRight = point.x <= viewportObservedFrame.right.toFloat()
    val isAfterTop = point.y >= viewportObservedFrame.top.toFloat()
    val isBeforeBottom = point.y <= viewportObservedFrame.bottom.toFloat()
    val isInsideHorizontalBounds = isAfterLeft && isBeforeRight
    val isInsideVerticalBounds = isAfterTop && isBeforeBottom

    return isInsideHorizontalBounds && isInsideVerticalBounds
}

internal fun createCarVirtualDisplayProbeClickCoordinateCandidates(
    viewport: CarVirtualDisplayProbeViewport,
    surfaceX: Float,
    surfaceY: Float,
    observedFrameX: Float?,
    observedFrameY: Float?,
    hostVisibleX: Float?,
    hostVisibleY: Float?,
): List<CarVirtualDisplayProbeClickCoordinateCandidate> {
    val surfacePoint = Offset(
        x = surfaceX,
        y = surfaceY,
    )
    val observedFramePoint = createNullableOffset(
        pointX = observedFrameX,
        pointY = observedFrameY,
    )
    val hostVisiblePoint = createNullableOffset(
        pointX = hostVisibleX,
        pointY = hostVisibleY,
    )
    val observedOffsetPoint = viewport.createObservedOffsetTouchPoint(
        surfaceX = surfaceX,
        surfaceY = surfaceY,
    )
    val visibleScaledPoint = viewport.createVisibleScaledTouchPoint(
        surfaceX = surfaceX,
        surfaceY = surfaceY,
    )
    val candidatePoints = mutableListOf<CarVirtualDisplayProbeClickCoordinateCandidate>()

    candidatePoints.addUniqueClickCoordinateCandidate(
        label = CLICK_COORDINATE_OBSERVED_OFFSET_LABEL,
        touchPoint = observedOffsetPoint,
    )
    candidatePoints.addUniqueClickCoordinateCandidate(
        label = CLICK_COORDINATE_SURFACE_LABEL,
        touchPoint = surfacePoint,
    )
    candidatePoints.addUniqueClickCoordinateCandidate(
        label = CLICK_COORDINATE_OBSERVED_LABEL,
        touchPoint = observedFramePoint,
    )
    candidatePoints.addUniqueClickCoordinateCandidate(
        label = CLICK_COORDINATE_HOST_VISIBLE_LABEL,
        touchPoint = hostVisiblePoint,
    )
    candidatePoints.addUniqueClickCoordinateCandidate(
        label = CLICK_COORDINATE_VISIBLE_SCALED_LABEL,
        touchPoint = visibleScaledPoint,
    )

    return candidatePoints
}

private fun createNullableOffset(pointX: Float?, pointY: Float?): Offset? {
    if (pointX == null || pointY == null) {
        return null
    }

    return Offset(
        x = pointX,
        y = pointY,
    )
}

private fun CarVirtualDisplayProbeViewport.createObservedOffsetTouchPoint(surfaceX: Float, surfaceY: Float): Offset? {
    if (!hasObservedFrameInset()) {
        return null
    }

    val viewportObservedFrame = observedFrame
    val offsetSurfaceX = (viewportObservedFrame.left + surfaceX).coerceIn(
        minimumValue = 0f,
        maximumValue = surfaceWidth.toFloat(),
    )
    val offsetSurfaceY = (viewportObservedFrame.top + surfaceY).coerceIn(
        minimumValue = 0f,
        maximumValue = surfaceHeight.toFloat(),
    )

    return Offset(
        x = offsetSurfaceX,
        y = offsetSurfaceY,
    )
}

private fun CarVirtualDisplayProbeViewport.createVisibleScaledTouchPoint(surfaceX: Float, surfaceY: Float): Offset? {
    if (!hasVisibleAreaInset()) {
        return null
    }

    val scaledSurfaceX = visibleLeft + surfaceX * visibleWidth / surfaceWidth
    val scaledSurfaceY = visibleTop + surfaceY * visibleHeight / surfaceHeight

    return Offset(
        x = scaledSurfaceX,
        y = scaledSurfaceY,
    )
}

private fun MutableList<CarVirtualDisplayProbeClickCoordinateCandidate>.addUniqueClickCoordinateCandidate(label: String, touchPoint: Offset?) {
    if (touchPoint == null) {
        return
    }

    val isDuplicate = any { candidate ->
        candidate.point == touchPoint
    }

    if (isDuplicate) {
        return
    }

    add(
        CarVirtualDisplayProbeClickCoordinateCandidate(
            label = label,
            point = touchPoint,
        ),
    )
}

private fun List<CarVirtualDisplayProbeClickCoordinateCandidate>.findClickCoordinate(label: String): CarVirtualDisplayProbeClickCoordinateCandidate? {
    return firstOrNull { candidate ->
        candidate.label == label
    }
}
