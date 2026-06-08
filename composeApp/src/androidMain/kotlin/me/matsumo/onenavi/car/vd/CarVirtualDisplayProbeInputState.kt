package me.matsumo.onenavi.car.vd

import androidx.compose.runtime.Immutable

/** Android Auto host Surface 経由で観測した入力イベントの種類。 */
enum class CarVirtualDisplayProbeInputKind(
    val label: String,
) {
    /** まだ入力イベントを受け取っていない状態。 */
    None("none"),

    /** pan mode の有効状態が変わったイベント。 */
    PanMode("pan"),

    /** host が click として解釈した入力イベント。 */
    Click("click"),

    /** host が scroll として解釈した入力イベント。 */
    Scroll("scroll"),

    /** host が fling として解釈した入力イベント。 */
    Fling("fling"),

    /** host が scale として解釈した入力イベント。 */
    Scale("scale"),
}

/** Android Auto host Surface から届いた入力イベントを Compose で観測するための状態。 */
@Immutable
data class CarVirtualDisplayProbeInputState(
    val sequence: Long,
    val kind: CarVirtualDisplayProbeInputKind,
    val isInPanMode: Boolean,
    val hostInputX: Float?,
    val hostInputY: Float?,
    val surfaceX: Float?,
    val surfaceY: Float?,
    val isInsideSurface: Boolean?,
    val observedFrameX: Float?,
    val observedFrameY: Float?,
    val isInsideObservedFrame: Boolean?,
    val hostVisibleX: Float?,
    val hostVisibleY: Float?,
    val isInsideHostVisibleArea: Boolean?,
    val distanceX: Float?,
    val distanceY: Float?,
    val velocityX: Float?,
    val velocityY: Float?,
    val scaleFactor: Float?,
) {

    val panModeLabel: String
        get() = if (isInPanMode) "on" else "off"

    val hostInputPointLabel: String
        get() = createPointLabel(
            pointX = hostInputX,
            pointY = hostInputY,
        )

    val surfacePointLabel: String
        get() = createPointLabel(
            pointX = surfaceX,
            pointY = surfaceY,
        )

    val insideSurfaceLabel: String
        get() = isInsideSurface?.toString() ?: "n/a"

    val observedFramePointLabel: String
        get() = createPointLabel(
            pointX = observedFrameX,
            pointY = observedFrameY,
        )

    val insideObservedFrameLabel: String
        get() = isInsideObservedFrame?.toString() ?: "n/a"

    val hostVisiblePointLabel: String
        get() = createPointLabel(
            pointX = hostVisibleX,
            pointY = hostVisibleY,
        )

    val insideHostVisibleAreaLabel: String
        get() = isInsideHostVisibleArea?.toString() ?: "n/a"

    val distanceLabel: String
        get() = createPointLabel(
            pointX = distanceX,
            pointY = distanceY,
        )

    val velocityLabel: String
        get() = createPointLabel(
            pointX = velocityX,
            pointY = velocityY,
        )

    val scaleFactorLabel: String
        get() = scaleFactor?.toString() ?: "n/a"

    val logLabel: String
        get() {
            val hostInputLabel = "hostInput=$hostInputPointLabel"
            val surfaceLabel = "surface=$surfacePointLabel, surfaceIn=$insideSurfaceLabel"
            val observedFrameLabel = "observed=$observedFramePointLabel, observedIn=$insideObservedFrameLabel"
            val hostVisibleLabel = "hostVisible=$hostVisiblePointLabel, hostVisibleIn=$insideHostVisibleAreaLabel"
            val gestureLabel = "distance=$distanceLabel, velocity=$velocityLabel, scale=$scaleFactorLabel"

            return "kind=${kind.label}, pan=$panModeLabel, $hostInputLabel, $surfaceLabel, $observedFrameLabel, " +
                "$hostVisibleLabel, $gestureLabel"
        }
}

internal fun createInitialCarVirtualDisplayProbeInputState(): CarVirtualDisplayProbeInputState {
    return CarVirtualDisplayProbeInputState(
        sequence = 0L,
        kind = CarVirtualDisplayProbeInputKind.None,
        isInPanMode = false,
        hostInputX = null,
        hostInputY = null,
        surfaceX = null,
        surfaceY = null,
        isInsideSurface = null,
        observedFrameX = null,
        observedFrameY = null,
        isInsideObservedFrame = null,
        hostVisibleX = null,
        hostVisibleY = null,
        isInsideHostVisibleArea = null,
        distanceX = null,
        distanceY = null,
        velocityX = null,
        velocityY = null,
        scaleFactor = null,
    )
}

internal fun createCarVirtualDisplayProbePanModeInputState(
    sequence: Long,
    isInPanMode: Boolean,
): CarVirtualDisplayProbeInputState {
    return createInitialCarVirtualDisplayProbeInputState().copy(
        sequence = sequence,
        kind = CarVirtualDisplayProbeInputKind.PanMode,
        isInPanMode = isInPanMode,
    )
}

internal fun createCarVirtualDisplayProbeClickInputState(
    sequence: Long,
    viewport: CarVirtualDisplayProbeViewport,
    surfaceX: Float,
    surfaceY: Float,
    isInPanMode: Boolean,
): CarVirtualDisplayProbeInputState {
    return createPositionedCarVirtualDisplayProbeInputState(
        sequence = sequence,
        kind = CarVirtualDisplayProbeInputKind.Click,
        viewport = viewport,
        surfaceX = surfaceX,
        surfaceY = surfaceY,
        isInPanMode = isInPanMode,
    )
}

internal fun createCarVirtualDisplayProbeScrollInputState(
    sequence: Long,
    distanceX: Float,
    distanceY: Float,
    isInPanMode: Boolean,
): CarVirtualDisplayProbeInputState {
    return createInitialCarVirtualDisplayProbeInputState().copy(
        sequence = sequence,
        kind = CarVirtualDisplayProbeInputKind.Scroll,
        isInPanMode = isInPanMode,
        distanceX = distanceX,
        distanceY = distanceY,
    )
}

internal fun createCarVirtualDisplayProbeFlingInputState(
    sequence: Long,
    velocityX: Float,
    velocityY: Float,
    isInPanMode: Boolean,
): CarVirtualDisplayProbeInputState {
    return createInitialCarVirtualDisplayProbeInputState().copy(
        sequence = sequence,
        kind = CarVirtualDisplayProbeInputKind.Fling,
        isInPanMode = isInPanMode,
        velocityX = velocityX,
        velocityY = velocityY,
    )
}

internal fun createCarVirtualDisplayProbeScaleInputState(
    sequence: Long,
    viewport: CarVirtualDisplayProbeViewport,
    focusX: Float,
    focusY: Float,
    scaleFactor: Float,
    isInPanMode: Boolean,
): CarVirtualDisplayProbeInputState {
    val hasFocusPoint = focusX >= 0f && focusY >= 0f
    val positionedInputState = if (hasFocusPoint) {
        createPositionedCarVirtualDisplayProbeInputState(
            sequence = sequence,
            kind = CarVirtualDisplayProbeInputKind.Scale,
            viewport = viewport,
            surfaceX = focusX,
            surfaceY = focusY,
            isInPanMode = isInPanMode,
        )
    } else {
        createInitialCarVirtualDisplayProbeInputState().copy(
            sequence = sequence,
            kind = CarVirtualDisplayProbeInputKind.Scale,
            isInPanMode = isInPanMode,
        )
    }

    return positionedInputState.copy(
        scaleFactor = scaleFactor,
    )
}

private fun createPositionedCarVirtualDisplayProbeInputState(
    sequence: Long,
    kind: CarVirtualDisplayProbeInputKind,
    viewport: CarVirtualDisplayProbeViewport,
    surfaceX: Float,
    surfaceY: Float,
    isInPanMode: Boolean,
): CarVirtualDisplayProbeInputState {
    val inputCoordinate = viewport.resolveInputCoordinate(
        hostInputX = surfaceX,
        hostInputY = surfaceY,
    )

    return createInitialCarVirtualDisplayProbeInputState().copy(
        sequence = sequence,
        kind = kind,
        isInPanMode = isInPanMode,
        hostInputX = inputCoordinate.hostInputX,
        hostInputY = inputCoordinate.hostInputY,
        surfaceX = inputCoordinate.surfaceX,
        surfaceY = inputCoordinate.surfaceY,
        isInsideSurface = inputCoordinate.isInsideSurface,
        observedFrameX = inputCoordinate.observedFrameX,
        observedFrameY = inputCoordinate.observedFrameY,
        isInsideObservedFrame = inputCoordinate.isInsideObservedFrame,
        hostVisibleX = inputCoordinate.hostVisibleX,
        hostVisibleY = inputCoordinate.hostVisibleY,
        isInsideHostVisibleArea = inputCoordinate.isInsideHostVisibleArea,
    )
}

private fun createPointLabel(
    pointX: Float?,
    pointY: Float?,
): String {
    if (pointX == null || pointY == null) {
        return "n/a"
    }

    return "${pointX.toInt()},${pointY.toInt()}"
}
