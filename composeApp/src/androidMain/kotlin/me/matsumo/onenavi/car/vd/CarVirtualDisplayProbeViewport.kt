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
            effectiveVisibleLeft,
            surfaceWidth - effectiveVisibleRight,
        ).coerceAtLeast(0)

    val observedFrame: CarVirtualDisplayObservedFrame
        get() {
            val observedLeft = (effectiveVisibleLeft - horizontalSafetyInset).coerceIn(0, surfaceWidth)
            val observedRight = (effectiveVisibleRight + horizontalSafetyInset).coerceIn(observedLeft, surfaceWidth)

            return CarVirtualDisplayObservedFrame(
                left = observedLeft,
                top = 0,
                right = observedRight,
                bottom = surfaceHeight,
            )
        }

    val observedFrameRightInset: Int
        get() = surfaceWidth - observedFrame.right

    private val effectiveVisibleLeft: Int
        get() = if (visibleLeft >= VISIBLE_AREA_INSET_NOISE_THRESHOLD_PX) visibleLeft else 0

    private val effectiveVisibleRight: Int
        get() {
            val rightInset = surfaceWidth - visibleRight

            return if (rightInset >= VISIBLE_AREA_INSET_NOISE_THRESHOLD_PX) visibleRight else surfaceWidth
        }
}

/** probe 実装から既存名で参照するための viewport 型。 */
internal typealias CarVirtualDisplayProbeViewport = CarVirtualDisplayViewport

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

internal fun CarVirtualDisplayProbeViewport.hasVisibleAreaInset(): Boolean {
    val hasValidSurface = surfaceWidth > 0 && surfaceHeight > 0
    val hasValidVisibleArea = visibleWidth > 0 && visibleHeight > 0
    val leftInset = visibleLeft
    val topInset = visibleTop
    val rightInset = surfaceWidth - visibleRight
    val bottomInset = surfaceHeight - visibleBottom
    val hasLeftInset = leftInset >= VISIBLE_AREA_INSET_NOISE_THRESHOLD_PX
    val hasTopInset = topInset >= VISIBLE_AREA_INSET_NOISE_THRESHOLD_PX
    val hasRightInset = rightInset >= VISIBLE_AREA_INSET_NOISE_THRESHOLD_PX
    val hasBottomInset = bottomInset >= VISIBLE_AREA_INSET_NOISE_THRESHOLD_PX
    val hasHorizontalInset = hasLeftInset || hasRightInset
    val hasVerticalInset = hasTopInset || hasBottomInset

    return hasValidSurface && hasValidVisibleArea && (hasHorizontalInset || hasVerticalInset)
}

internal fun CarVirtualDisplayProbeViewport.hasObservedFrameInset(): Boolean {
    val viewportObservedFrame = observedFrame
    val hasLeftInset = viewportObservedFrame.left >= VISIBLE_AREA_INSET_NOISE_THRESHOLD_PX
    val hasRightInset = surfaceWidth - viewportObservedFrame.right >= VISIBLE_AREA_INSET_NOISE_THRESHOLD_PX
    val hasTopInset = viewportObservedFrame.top >= VISIBLE_AREA_INSET_NOISE_THRESHOLD_PX
    val hasBottomInset = surfaceHeight - viewportObservedFrame.bottom >= VISIBLE_AREA_INSET_NOISE_THRESHOLD_PX

    return hasLeftInset || hasRightInset || hasTopInset || hasBottomInset
}

internal fun CarVirtualDisplayProbeViewport.resolveInputCoordinate(hostInputX: Float, hostInputY: Float): CarVirtualDisplayInputCoordinate {
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

internal fun CarVirtualDisplayProbeViewport.withVisibleArea(visibleArea: Rect): CarVirtualDisplayProbeViewport {
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

internal fun CarVirtualDisplayProbeViewport.withStableArea(stableArea: Rect): CarVirtualDisplayProbeViewport {
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

private fun CarVirtualDisplayProbeViewport.containsSurfacePoint(surfaceX: Float, surfaceY: Float): Boolean {
    val isInsideHorizontalBounds = surfaceX >= 0f && surfaceX <= surfaceWidth.toFloat()
    val isInsideVerticalBounds = surfaceY >= 0f && surfaceY <= surfaceHeight.toFloat()

    return isInsideHorizontalBounds && isInsideVerticalBounds
}

private fun CarVirtualDisplayObservedFrame.containsSurfacePoint(surfaceX: Float, surfaceY: Float): Boolean {
    val isInsideHorizontalBounds = surfaceX >= left.toFloat() && surfaceX <= right.toFloat()
    val isInsideVerticalBounds = surfaceY >= top.toFloat() && surfaceY <= bottom.toFloat()

    return isInsideHorizontalBounds && isInsideVerticalBounds
}

private fun CarVirtualDisplayProbeViewport.containsHostVisiblePoint(surfaceX: Float, surfaceY: Float): Boolean {
    val isInsideHorizontalBounds = surfaceX >= visibleLeft && surfaceX <= visibleRight
    val isInsideVerticalBounds = surfaceY >= visibleTop && surfaceY <= visibleBottom

    return isInsideHorizontalBounds && isInsideVerticalBounds
}

private fun Rect.coerceToSurfaceBounds(surfaceWidth: Int, surfaceHeight: Int): Rect {
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

/** host が返す visible area の 1px 程度の丸めノイズを無視する閾値。 */
private const val VISIBLE_AREA_INSET_NOISE_THRESHOLD_PX = 4
