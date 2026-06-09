package me.matsumo.onenavi.car.vd

import android.graphics.Rect
import androidx.compose.runtime.Immutable

/** Android Auto host Surface 上でアプリ描画に使える共通 viewport 情報。 */
@Immutable
data class CarVirtualDisplayViewport(
    val surfaceWidth: Int,
    val surfaceHeight: Int,
    val densityDpi: Int,
    val visibleLeft: Int,
    val visibleTop: Int,
    val visibleRight: Int,
    val visibleBottom: Int,
    val stableLeft: Int,
    val stableTop: Int,
    val stableRight: Int,
    val stableBottom: Int,
) {

    val visibleWidth: Int
        get() = visibleRight - visibleLeft

    val visibleHeight: Int
        get() = visibleBottom - visibleTop

    val stableWidth: Int
        get() = stableRight - stableLeft

    val stableHeight: Int
        get() = stableBottom - stableTop

    val visibleAreaLabel: String
        get() = "Rect($visibleLeft, $visibleTop - $visibleRight, $visibleBottom)"

    val stableAreaLabel: String
        get() = "Rect($stableLeft, $stableTop - $stableRight, $stableBottom)"

    val horizontalSafetyInset: Int
        get() = minOf(
            visibleLeft,
            surfaceWidth - visibleRight,
        ).coerceAtLeast(0)

    val observedFrame: CarVirtualDisplayObservedFrame
        get() {
            val observedLeft = (visibleLeft - horizontalSafetyInset).coerceIn(0, surfaceWidth)
            val observedRight = (visibleRight + horizontalSafetyInset).coerceIn(observedLeft, surfaceWidth)

            return CarVirtualDisplayObservedFrame(
                left = observedLeft,
                top = 0,
                right = observedRight,
                bottom = surfaceHeight,
            )
        }

    val observedFrameRightInset: Int
        get() = surfaceWidth - observedFrame.right
}

/** probe 実装から既存名で参照するための viewport 型。 */
internal typealias CarVirtualDisplayProbeViewport = CarVirtualDisplayViewport

/** split 表示とみなして visibleArea 横補正候補を追加する最小 inset。 */
private const val SPLIT_VISIBLE_AREA_MIN_INSET_PX = 120

/** Android Auto host Surface 上で OneNaviApp を実際に描画する領域。 */
@Immutable
data class CarVirtualDisplayObservedFrame(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {

    val width: Int
        get() = right - left

    val height: Int
        get() = bottom - top

    val frameLabel: String
        get() = "Rect($left,$top - $right,$bottom)"
}

/** Surface 座標を OneNaviApp が描画される observed frame 座標へ変換した結果。 */
@Immutable
data class CarVirtualDisplayInputCoordinate(
    val hostInputX: Float,
    val hostInputY: Float,
    val surfaceX: Float,
    val surfaceY: Float,
    val observedFrameX: Float,
    val observedFrameY: Float,
    val hostVisibleX: Float,
    val hostVisibleY: Float,
    val isInsideSurface: Boolean,
    val isInsideObservedFrame: Boolean,
    val isInsideHostVisibleArea: Boolean,
)

internal fun createCarVirtualDisplayProbeViewport(
    surfaceWidth: Int,
    surfaceHeight: Int,
    densityDpi: Int,
): CarVirtualDisplayProbeViewport {
    return CarVirtualDisplayProbeViewport(
        surfaceWidth = surfaceWidth,
        surfaceHeight = surfaceHeight,
        densityDpi = densityDpi,
        visibleLeft = 0,
        visibleTop = 0,
        visibleRight = surfaceWidth,
        visibleBottom = surfaceHeight,
        stableLeft = 0,
        stableTop = 0,
        stableRight = surfaceWidth,
        stableBottom = surfaceHeight,
    )
}

internal fun CarVirtualDisplayProbeViewport.hasHorizontalSplitVisibleArea(): Boolean {
    val leftInset = visibleLeft
    val rightInset = surfaceWidth - visibleRight
    val maxHorizontalInset = maxOf(leftInset, rightInset)
    val hasValidSurface = surfaceWidth > 0 && visibleWidth > 0
    val hasSplitInset = maxHorizontalInset >= SPLIT_VISIBLE_AREA_MIN_INSET_PX

    return hasValidSurface && hasSplitInset
}

internal fun CarVirtualDisplayProbeViewport.resolveInputCoordinate(
    hostInputX: Float,
    hostInputY: Float,
): CarVirtualDisplayInputCoordinate {
    val viewportObservedFrame = observedFrame
    val resolvedSurfaceX = hostInputX
    val resolvedSurfaceY = hostInputY
    val resolvedObservedFrameX = resolvedSurfaceX - viewportObservedFrame.left
    val resolvedObservedFrameY = resolvedSurfaceY - viewportObservedFrame.top

    return CarVirtualDisplayInputCoordinate(
        hostInputX = hostInputX,
        hostInputY = hostInputY,
        surfaceX = resolvedSurfaceX,
        surfaceY = resolvedSurfaceY,
        observedFrameX = resolvedObservedFrameX,
        observedFrameY = resolvedObservedFrameY,
        hostVisibleX = resolvedSurfaceX - visibleLeft,
        hostVisibleY = resolvedSurfaceY - visibleTop,
        isInsideSurface = containsSurfacePoint(
            surfaceX = resolvedSurfaceX,
            surfaceY = resolvedSurfaceY,
        ),
        isInsideObservedFrame = viewportObservedFrame.containsSurfacePoint(
            surfaceX = resolvedSurfaceX,
            surfaceY = resolvedSurfaceY,
        ),
        isInsideHostVisibleArea = containsHostVisiblePoint(
            surfaceX = resolvedSurfaceX,
            surfaceY = resolvedSurfaceY,
        ),
    )
}

internal fun CarVirtualDisplayProbeViewport.withVisibleArea(
    visibleArea: Rect,
): CarVirtualDisplayProbeViewport {
    val isEmptyVisibleArea = visibleArea.width() <= 0 || visibleArea.height() <= 0

    if (isEmptyVisibleArea) {
        return this
    }

    val coercedArea = visibleArea.coerceToSurfaceBounds(
        surfaceWidth = surfaceWidth,
        surfaceHeight = surfaceHeight,
    )

    return copy(
        visibleLeft = coercedArea.left,
        visibleTop = coercedArea.top,
        visibleRight = coercedArea.right,
        visibleBottom = coercedArea.bottom,
    )
}

internal fun CarVirtualDisplayProbeViewport.withStableArea(
    stableArea: Rect,
): CarVirtualDisplayProbeViewport {
    val coercedArea = stableArea.coerceToSurfaceBounds(
        surfaceWidth = surfaceWidth,
        surfaceHeight = surfaceHeight,
    )

    return copy(
        stableLeft = coercedArea.left,
        stableTop = coercedArea.top,
        stableRight = coercedArea.right,
        stableBottom = coercedArea.bottom,
    )
}

private fun CarVirtualDisplayProbeViewport.containsSurfacePoint(
    surfaceX: Float,
    surfaceY: Float,
): Boolean {
    val isInsideHorizontalBounds = surfaceX >= 0f && surfaceX <= surfaceWidth.toFloat()
    val isInsideVerticalBounds = surfaceY >= 0f && surfaceY <= surfaceHeight.toFloat()

    return isInsideHorizontalBounds && isInsideVerticalBounds
}

private fun CarVirtualDisplayObservedFrame.containsSurfacePoint(
    surfaceX: Float,
    surfaceY: Float,
): Boolean {
    val isInsideHorizontalBounds = surfaceX >= left.toFloat() && surfaceX <= right.toFloat()
    val isInsideVerticalBounds = surfaceY >= top.toFloat() && surfaceY <= bottom.toFloat()

    return isInsideHorizontalBounds && isInsideVerticalBounds
}

private fun CarVirtualDisplayProbeViewport.containsHostVisiblePoint(
    surfaceX: Float,
    surfaceY: Float,
): Boolean {
    val isInsideHorizontalBounds = surfaceX >= visibleLeft && surfaceX <= visibleRight
    val isInsideVerticalBounds = surfaceY >= visibleTop && surfaceY <= visibleBottom

    return isInsideHorizontalBounds && isInsideVerticalBounds
}

private fun Rect.coerceToSurfaceBounds(
    surfaceWidth: Int,
    surfaceHeight: Int,
): Rect {
    val coercedLeft = left.coerceIn(0, surfaceWidth)
    val coercedTop = top.coerceIn(0, surfaceHeight)
    val coercedRight = right.coerceIn(coercedLeft, surfaceWidth)
    val coercedBottom = bottom.coerceIn(coercedTop, surfaceHeight)

    return Rect(
        coercedLeft,
        coercedTop,
        coercedRight,
        coercedBottom,
    )
}
