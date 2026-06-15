package me.matsumo.onenavi.car.vd

import android.view.MotionEvent
import androidx.compose.runtime.Immutable

/** Synthetic drag の継続状態。 */
@Immutable
internal data class CarVirtualDisplayDragGestureState(
    val downTime: Long,
    val currentPoint: CarVirtualDisplaySurfacePoint,
)

/** 合成 click の遅延 ACTION_UP を送るための状態。 */
@Immutable
internal data class CarVirtualDisplayClickGestureState(
    val downTime: Long,
    val downPoint: CarVirtualDisplaySurfacePoint,
    val didHandleDown: Boolean,
)

/** 合成 fling の move/up を予約実行するための状態。 */
@Immutable
internal data class CarVirtualDisplayFlingGestureState(
    val downTime: Long,
    val currentPoint: CarVirtualDisplaySurfacePoint,
)

/** Synthetic pinch の継続状態。 */
@Immutable
internal data class CarVirtualDisplayScaleGestureState(
    val downTime: Long,
    val focusPoint: CarVirtualDisplaySurfacePoint,
    val currentSpan: Float,
)

/** Surface 上の座標。 */
@Immutable
internal data class CarVirtualDisplaySurfacePoint(
    val surfaceX: Float,
    val surfaceY: Float,
)

/** Surface 上の移動量。 */
@Immutable
internal data class CarVirtualDisplaySurfaceVector(
    val componentX: Float,
    val componentY: Float,
)

internal val CarVirtualDisplayProbeInputState.surfacePointOrNull: CarVirtualDisplaySurfacePoint?
    get() {
        val inputSurfaceX = surfaceX ?: return null
        val inputSurfaceY = surfaceY ?: return null

        return CarVirtualDisplaySurfacePoint(
            surfaceX = inputSurfaceX,
            surfaceY = inputSurfaceY,
        )
    }

internal fun CarVirtualDisplayProbeInputState.scaleFocusPointOrNull(viewport: CarVirtualDisplayProbeViewport): CarVirtualDisplaySurfacePoint? {
    val rawFocusPoint = surfacePointOrNull ?: return null

    if (!viewport.hasObservedFrameInset()) {
        return rawFocusPoint
    }

    val viewportObservedFrame = viewport.observedFrame
    val resolvedFocusX = viewportObservedFrame.left + rawFocusPoint.surfaceX
    val resolvedFocusY = viewportObservedFrame.top + rawFocusPoint.surfaceY

    return CarVirtualDisplaySurfacePoint(
        surfaceX = resolvedFocusX.coerceIn(
            minimumValue = 0f,
            maximumValue = viewport.surfaceWidth.toFloat(),
        ),
        surfaceY = resolvedFocusY.coerceIn(
            minimumValue = 0f,
            maximumValue = viewport.surfaceHeight.toFloat(),
        ),
    )
}

internal val CarVirtualDisplayProbeInputState.scrollDistanceOrNull: CarVirtualDisplaySurfaceVector?
    get() {
        val inputDistanceX = distanceX ?: return null
        val inputDistanceY = distanceY ?: return null

        return CarVirtualDisplaySurfaceVector(
            componentX = inputDistanceX,
            componentY = inputDistanceY,
        )
    }

internal val CarVirtualDisplayProbeInputState.flingVelocityOrNull: CarVirtualDisplaySurfaceVector?
    get() {
        val inputVelocityX = velocityX ?: return null
        val inputVelocityY = velocityY ?: return null

        return CarVirtualDisplaySurfaceVector(
            componentX = inputVelocityX,
            componentY = inputVelocityY,
        )
    }

internal fun CarVirtualDisplayScaleGestureState.pointerPoints(): List<CarVirtualDisplaySurfacePoint> {
    val halfSpan = currentSpan / 2f
    return listOf(
        CarVirtualDisplaySurfacePoint(
            surfaceX = focusPoint.surfaceX - halfSpan,
            surfaceY = focusPoint.surfaceY,
        ),
        CarVirtualDisplaySurfacePoint(
            surfaceX = focusPoint.surfaceX + halfSpan,
            surfaceY = focusPoint.surfaceY,
        ),
    )
}

internal fun CarVirtualDisplayProbeViewport.initialScaleSpanPx(): Float {
    return (observedFrame.width * INITIAL_SCALE_SPAN_RATIO)
        .coerceIn(MIN_SCALE_SPAN_PX, maxScaleSpanPx())
}

internal fun CarVirtualDisplayProbeViewport.maxScaleSpanPx(): Float {
    return (surfaceWidth * MAX_SCALE_SPAN_RATIO).coerceAtLeast(MIN_SCALE_SPAN_PX)
}

internal fun CarVirtualDisplayProbeViewport.coerceObservedSurfacePoint(surfacePoint: CarVirtualDisplaySurfacePoint): CarVirtualDisplaySurfacePoint {
    return coerceObservedSurfacePoint(
        surfaceX = surfacePoint.surfaceX,
        surfaceY = surfacePoint.surfaceY,
    )
}

private fun CarVirtualDisplayProbeViewport.coerceObservedSurfacePoint(surfaceX: Float, surfaceY: Float): CarVirtualDisplaySurfacePoint {
    val viewportObservedFrame = observedFrame
    val coercedSurfaceX = surfaceX.coerceInSafe(
        minimumValue = viewportObservedFrame.left + GESTURE_EDGE_MARGIN_PX,
        maximumValue = viewportObservedFrame.right - GESTURE_EDGE_MARGIN_PX,
    )
    val coercedSurfaceY = surfaceY.coerceInSafe(
        minimumValue = viewportObservedFrame.top + GESTURE_EDGE_MARGIN_PX,
        maximumValue = viewportObservedFrame.bottom - GESTURE_EDGE_MARGIN_PX,
    )

    return CarVirtualDisplaySurfacePoint(
        surfaceX = coercedSurfaceX,
        surfaceY = coercedSurfaceY,
    )
}

internal operator fun CarVirtualDisplaySurfacePoint.plus(vector: CarVirtualDisplaySurfaceVector): CarVirtualDisplaySurfacePoint {
    return copy(
        surfaceX = surfaceX + vector.componentX,
        surfaceY = surfaceY + vector.componentY,
    )
}

internal operator fun CarVirtualDisplaySurfacePoint.minus(vector: CarVirtualDisplaySurfaceVector): CarVirtualDisplaySurfacePoint {
    return copy(
        surfaceX = surfaceX - vector.componentX,
        surfaceY = surfaceY - vector.componentY,
    )
}

internal fun CarVirtualDisplaySurfaceVector.toFlingMoveDelta(decay: Float): CarVirtualDisplaySurfaceVector {
    val frameDurationSeconds = FLING_MOVE_INTERVAL_MS / MILLIS_PER_SECOND

    return CarVirtualDisplaySurfaceVector(
        componentX = componentX * frameDurationSeconds * decay,
        componentY = componentY * frameDurationSeconds * decay,
    )
}

internal fun CarVirtualDisplaySurfacePoint.toLogLabel(): String {
    return "${surfaceX.toInt()},${surfaceY.toInt()}"
}

internal fun CarVirtualDisplaySurfaceVector.toLogLabel(): String {
    return "${componentX.toInt()},${componentY.toInt()}"
}

internal fun CarVirtualDisplaySurfaceVector.isZero(): Boolean {
    return componentX == 0f && componentY == 0f
}

private fun Float.coerceInSafe(minimumValue: Float, maximumValue: Float): Float {
    if (minimumValue <= maximumValue) {
        return coerceIn(minimumValue, maximumValue)
    }

    return (minimumValue + maximumValue) / 2f
}

/** click 注入時の down/up 間隔。 */
internal const val CLICK_UP_DELAY_MS = 16L

/** drag 入力が途切れてから ACTION_UP を送るまでの猶予。 */
internal const val DRAG_FINISH_DELAY_MS = 96L

/** pinch 入力が途切れてから pointer up を送るまでの猶予。 */
internal const val SCALE_FINISH_DELAY_MS = 320L

/** fling 速度を MotionEvent move に変換する際の 1 frame 相当時間。 */
internal const val FLING_MOVE_INTERVAL_MS = 16L

/** fling で追加する synthetic move の数。 */
internal const val FLING_MOVE_COUNT = 4

/** fling synthetic move ごとの速度減衰率。 */
internal const val FLING_VELOCITY_DECAY = 0.55f

/** 速度 px/sec を px/ms に直すための係数。 */
internal const val MILLIS_PER_SECOND = 1_000f

/** 2本目の pointer down action。 */
internal const val SECOND_POINTER_DOWN_ACTION = MotionEvent.ACTION_POINTER_DOWN or
    (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)

/** 2本目の pointer up action。 */
internal const val SECOND_POINTER_UP_ACTION = MotionEvent.ACTION_POINTER_UP or
    (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)

/** pinch 開始時に observed frame 幅へ掛ける比率。 */
private const val INITIAL_SCALE_SPAN_RATIO = 0.55f

/** pinch 中の最大 pointer 間隔として Surface 幅へ掛ける比率。 */
private const val MAX_SCALE_SPAN_RATIO = 4f

/** synthetic pointer を observed frame 端へ貼り付けないための余白。 */
private const val GESTURE_EDGE_MARGIN_PX = 2f

/** pinch 中に pointer 同士を完全に重ねないための最小間隔。 */
internal const val MIN_SCALE_SPAN_PX = 24f
