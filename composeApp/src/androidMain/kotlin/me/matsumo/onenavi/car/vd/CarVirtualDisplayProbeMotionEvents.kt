package me.matsumo.onenavi.car.vd

import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.ui.platform.ComposeView

internal fun createSinglePointerMotionEvent(
    downTime: Long,
    eventTime: Long,
    action: Int,
    point: CarVirtualDisplaySurfacePoint,
): MotionEvent {
    return MotionEvent.obtain(
        downTime,
        eventTime,
        action,
        point.surfaceX,
        point.surfaceY,
        0,
    ).apply {
        setSource(InputDevice.SOURCE_TOUCHSCREEN)
    }
}

internal fun createScaleMotionEvent(
    scaleGestureState: CarVirtualDisplayScaleGestureState,
    eventTime: Long,
    action: Int,
    pointerCount: Int = 2,
): MotionEvent {
    val pointerPoints = scaleGestureState.pointerPoints()
    val pointerProperties = createPointerProperties(pointerCount = pointerCount)
    val pointerCoords = createPointerCoords(
        pointerPoints = pointerPoints,
        pointerCount = pointerCount,
    )

    return MotionEvent.obtain(
        scaleGestureState.downTime,
        eventTime,
        action,
        pointerCount,
        pointerProperties,
        pointerCoords,
        0,
        0,
        1f,
        1f,
        0,
        0,
        InputDevice.SOURCE_TOUCHSCREEN,
        0,
    )
}

internal fun ComposeView.dispatchRecycledEvents(vararg events: MotionEvent): Boolean {
    return dispatchRecycledEvents(events = events.asList())
}

internal fun ComposeView.dispatchRecycledEvents(
    events: List<MotionEvent>,
): Boolean {
    return try {
        var didHandleAnyEvent = false

        events.forEach { event ->
            didHandleAnyEvent = dispatchTouchEvent(event) || didHandleAnyEvent
        }

        didHandleAnyEvent
    } finally {
        events.forEach { event ->
            event.recycle()
        }
    }
}

private fun createPointerProperties(pointerCount: Int): Array<MotionEvent.PointerProperties> {
    return Array(pointerCount) { pointerIndex ->
        MotionEvent.PointerProperties().apply {
            id = pointerIndex
            toolType = MotionEvent.TOOL_TYPE_FINGER
        }
    }
}

private fun createPointerCoords(
    pointerPoints: List<CarVirtualDisplaySurfacePoint>,
    pointerCount: Int,
): Array<MotionEvent.PointerCoords> {
    return Array(pointerCount) { pointerIndex ->
        val pointerPoint = pointerPoints[pointerIndex]
        MotionEvent.PointerCoords().apply {
            x = pointerPoint.surfaceX
            y = pointerPoint.surfaceY
            pressure = 1f
            size = 1f
        }
    }
}
