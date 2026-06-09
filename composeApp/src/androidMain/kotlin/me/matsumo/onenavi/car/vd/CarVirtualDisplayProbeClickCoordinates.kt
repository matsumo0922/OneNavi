package me.matsumo.onenavi.car.vd

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset

/** split 表示とみなして visibleArea 横補正候補を追加する最小 inset。 */
private const val SPLIT_VISIBLE_AREA_MIN_INSET_PX = 120

/** VD click 注入で試す座標候補。 */
@Immutable
data class CarVirtualDisplayProbeClickCoordinateCandidate(
    val label: String,
    val point: Offset,
)

/** VD click 注入で実際に採用した座標。 */
@Immutable
data class CarVirtualDisplayProbeClickCoordinateResult(
    val label: String,
    val point: Offset,
)

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
    val visibleOffsetPoint = viewport.createVisibleOffsetTouchPoint(
        surfaceX = surfaceX,
        surfaceY = surfaceY,
    )
    val visibleScaledPoint = viewport.createVisibleScaledTouchPoint(
        surfaceX = surfaceX,
        surfaceY = surfaceY,
    )
    val candidatePoints = mutableListOf<CarVirtualDisplayProbeClickCoordinateCandidate>()

    candidatePoints.addUniqueClickCoordinateCandidate(
        label = "visibleOffset",
        touchPoint = visibleOffsetPoint,
    )
    candidatePoints.addUniqueClickCoordinateCandidate(
        label = "surface",
        touchPoint = surfacePoint,
    )
    candidatePoints.addUniqueClickCoordinateCandidate(
        label = "observed",
        touchPoint = observedFramePoint,
    )
    candidatePoints.addUniqueClickCoordinateCandidate(
        label = "hostVisible",
        touchPoint = hostVisiblePoint,
    )
    candidatePoints.addUniqueClickCoordinateCandidate(
        label = "visibleScaled",
        touchPoint = visibleScaledPoint,
    )

    return candidatePoints
}

private fun createNullableOffset(
    pointX: Float?,
    pointY: Float?,
): Offset? {
    if (pointX == null || pointY == null) {
        return null
    }

    return Offset(
        x = pointX,
        y = pointY,
    )
}

private fun CarVirtualDisplayProbeViewport.createVisibleOffsetTouchPoint(
    surfaceX: Float,
    surfaceY: Float,
): Offset? {
    if (!hasHorizontalSplitVisibleArea()) {
        return null
    }

    val offsetSurfaceX = (visibleLeft + surfaceX).coerceIn(
        minimumValue = 0f,
        maximumValue = surfaceWidth.toFloat(),
    )

    return Offset(
        x = offsetSurfaceX,
        y = surfaceY,
    )
}

private fun CarVirtualDisplayProbeViewport.createVisibleScaledTouchPoint(
    surfaceX: Float,
    surfaceY: Float,
): Offset? {
    if (!hasHorizontalSplitVisibleArea()) {
        return null
    }

    val scaledSurfaceX = visibleLeft + surfaceX * visibleWidth / surfaceWidth

    return Offset(
        x = scaledSurfaceX,
        y = surfaceY,
    )
}

private fun MutableList<CarVirtualDisplayProbeClickCoordinateCandidate>.addUniqueClickCoordinateCandidate(
    label: String,
    touchPoint: Offset?,
) {
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

private fun CarVirtualDisplayProbeViewport.hasHorizontalSplitVisibleArea(): Boolean {
    val leftInset = visibleLeft
    val rightInset = surfaceWidth - visibleRight
    val maxHorizontalInset = maxOf(leftInset, rightInset)

    return surfaceWidth > 0 && visibleWidth > 0 && maxHorizontalInset >= SPLIT_VISIBLE_AREA_MIN_INSET_PX
}
