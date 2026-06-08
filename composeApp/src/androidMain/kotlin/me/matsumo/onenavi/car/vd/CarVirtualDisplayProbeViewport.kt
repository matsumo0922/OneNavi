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

internal fun CarVirtualDisplayProbeViewport.withVisibleArea(
    visibleArea: Rect,
): CarVirtualDisplayProbeViewport {
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
