package me.matsumo.onenavi.feature.map.state

import androidx.compose.runtime.Immutable
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 自車追従時に地図 viewport 上で使うアンカー座標。
 *
 * @param xPx viewport 左端からの X 座標（px）
 * @param yPx viewport 上端からの Y 座標（px）
 */
@Immutable
internal data class MapCameraScreenPoint(
    val xPx: Float,
    val yPx: Float,
)

/**
 * 自車を viewport アンカーへ寄せるための scroll 差分。
 *
 * @param xPx GoogleMap に渡す X 方向 scroll 量（px）
 * @param yPx GoogleMap に渡す Y 方向 scroll 量（px）
 */
@Immutable
internal data class MapCameraScrollDelta(
    val xPx: Float,
    val yPx: Float,
) {

    /** 無視できない scroll 量を持つ場合 true。 */
    val shouldApply: Boolean
        get() {
            val hasHorizontalScroll = abs(xPx) > VIEWPORT_ANCHOR_SCROLL_EPSILON_PX
            val hasVerticalScroll = abs(yPx) > VIEWPORT_ANCHOR_SCROLL_EPSILON_PX
            return hasHorizontalScroll || hasVerticalScroll
        }
}

/**
 * GoogleMap の padding で意図した padded center を projection 上の自車アンカーとして扱う helper。
 */
internal object MapCameraViewportAnchor {

    /**
     * 現在の viewport と padding から、自車を置くべき画面座標を返す。
     *
     * @param viewportWidthPx 地図 viewport の幅（px）
     * @param viewportHeightPx 地図 viewport の高さ（px）
     * @param startPaddingPx 左端 padding（px）
     * @param endPaddingPx 右端 padding（px）
     * @param topPaddingPx 上端 padding（px）
     * @param bottomPaddingPx 下端 padding（px）
     * @return padding を除いた可視領域の中心座標
     */
    fun resolveAnchorPoint(
        viewportWidthPx: Int,
        viewportHeightPx: Int,
        startPaddingPx: Int,
        endPaddingPx: Int,
        topPaddingPx: Int,
        bottomPaddingPx: Int,
    ): MapCameraScreenPoint = MapCameraScreenPoint(
        xPx = (startPaddingPx + viewportWidthPx - endPaddingPx) / 2f,
        yPx = (topPaddingPx + viewportHeightPx - bottomPaddingPx) / 2f,
    )

    /**
     * projection 上の自車座標からアンカーへ寄せるための scroll 差分を返す。
     *
     * @param vehicleScreenPoint projection 上の自車座標
     * @param anchorPoint 自車を置きたいアンカー座標
     * @return GoogleMap に渡す scroll 差分
     */
    fun resolveScrollDelta(
        vehicleScreenPoint: MapCameraScreenPoint,
        anchorPoint: MapCameraScreenPoint,
    ): MapCameraScrollDelta = MapCameraScrollDelta(
        xPx = vehicleScreenPoint.xPx - anchorPoint.xPx,
        yPx = vehicleScreenPoint.yPx - anchorPoint.yPx,
    )

    /**
     * projection 上の自車座標が追従維持許容範囲から外れているかを返す。
     *
     * @param vehicleScreenPoint projection 上の自車座標
     * @param anchorPoint 自車を置きたいアンカー座標
     * @param viewportHeightPx 地図 viewport の高さ（px）
     * @return gesture による追従解除相当まで離れている場合 true
     */
    fun isVehicleAwayFromAnchor(
        vehicleScreenPoint: MapCameraScreenPoint,
        anchorPoint: MapCameraScreenPoint,
        viewportHeightPx: Int,
    ): Boolean {
        val deltaX = vehicleScreenPoint.xPx - anchorPoint.xPx
        val deltaY = vehicleScreenPoint.yPx - anchorPoint.yPx
        val distancePx = hypot(deltaX.toDouble(), deltaY.toDouble())
        val tolerancePx = viewportHeightPx * FOLLOW_GESTURE_ANCHOR_TOLERANCE_FRACTION
        return distancePx > tolerancePx
    }
}

/** 自車アンカー補正で無視する最小 scroll 量。 */
private const val VIEWPORT_ANCHOR_SCROLL_EPSILON_PX = 0.5f

/** follow 中 gesture 後に追従維持を許容する viewport 高さ比。 */
private const val FOLLOW_GESTURE_ANCHOR_TOLERANCE_FRACTION = 0.12f
