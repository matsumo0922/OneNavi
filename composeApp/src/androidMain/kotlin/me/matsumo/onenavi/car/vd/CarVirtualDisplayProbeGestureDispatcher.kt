package me.matsumo.onenavi.car.vd

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.ui.platform.ComposeView

/** Android Auto host から届く抽象 gesture を ComposeView 向け MotionEvent に変換する dispatcher。 */
class CarVirtualDisplayProbeGestureDispatcher {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val finishDragRunnable = Runnable {
        finishDragGesture(isCanceled = false)
    }
    private val finishScaleRunnable = Runnable {
        finishScaleGesture(isCanceled = false)
    }

    private var composeView: ComposeView? = null
    private var dragGestureState: CarVirtualDisplayDragGestureState? = null
    private var scaleGestureState: CarVirtualDisplayScaleGestureState? = null

    fun attach(composeView: ComposeView) {
        this.composeView = composeView
    }

    fun detach() {
        mainHandler.removeCallbacks(finishDragRunnable)
        mainHandler.removeCallbacks(finishScaleRunnable)
        finishDragGesture(isCanceled = true)
        finishScaleGesture(isCanceled = true)
        composeView = null
    }

    fun dispatchClick(surfaceX: Float, surfaceY: Float): Boolean {
        val targetComposeView = composeView ?: return false
        cancelActiveContinuousGesture()

        val touchPoint = CarVirtualDisplaySurfacePoint(
            surfaceX = surfaceX,
            surfaceY = surfaceY,
        )
        val downTime = SystemClock.uptimeMillis()
        val upTime = downTime + CLICK_UP_DELAY_MS

        return targetComposeView.dispatchRecycledEvents(
            createSinglePointerMotionEvent(
                downTime = downTime,
                eventTime = downTime,
                action = MotionEvent.ACTION_DOWN,
                point = touchPoint,
            ),
            createSinglePointerMotionEvent(
                downTime = downTime,
                eventTime = upTime,
                action = MotionEvent.ACTION_UP,
                point = touchPoint,
            ),
        )
    }

    fun dispatchScroll(
        inputState: CarVirtualDisplayProbeInputState,
        viewport: CarVirtualDisplayProbeViewport,
    ): Boolean {
        val targetComposeView = composeView ?: return false
        val distance = inputState.scrollDistanceOrNull ?: return false
        val anchorPoint = inputState.surfacePointOrNull ?: return false

        finishScaleGesture(isCanceled = true)
        mainHandler.removeCallbacks(finishDragRunnable)

        val now = SystemClock.uptimeMillis()
        val currentDragGestureState = ensureDragGestureState(
            targetComposeView = targetComposeView,
            viewport = viewport,
            anchorPoint = anchorPoint,
            eventTime = now,
        )
        val nextPoint = viewport.coerceObservedSurfacePoint(
            currentDragGestureState.currentPoint - distance,
        )
        dragGestureState = currentDragGestureState.copy(
            currentPoint = nextPoint,
        )

        val didHandleEvent = targetComposeView.dispatchRecycledEvents(
            createSinglePointerMotionEvent(
                downTime = currentDragGestureState.downTime,
                eventTime = now,
                action = MotionEvent.ACTION_MOVE,
                point = nextPoint,
            ),
        )

        scheduleDragFinish()
        return didHandleEvent
    }

    fun dispatchFling(
        inputState: CarVirtualDisplayProbeInputState,
        viewport: CarVirtualDisplayProbeViewport,
    ): Boolean {
        val targetComposeView = composeView ?: return false
        val velocity = inputState.flingVelocityOrNull ?: return false
        val anchorPoint = inputState.surfacePointOrNull ?: return false

        finishScaleGesture(isCanceled = true)
        mainHandler.removeCallbacks(finishDragRunnable)

        val now = SystemClock.uptimeMillis()
        val currentDragGestureState = ensureDragGestureState(
            targetComposeView = targetComposeView,
            viewport = viewport,
            anchorPoint = anchorPoint,
            eventTime = now,
        )
        val events = createFlingMotionEvents(
            dragGestureState = currentDragGestureState,
            viewport = viewport,
            velocity = velocity,
            startEventTime = now,
        )
        val didHandleEvent = targetComposeView.dispatchRecycledEvents(events = events)

        dragGestureState = null
        return didHandleEvent
    }

    fun dispatchScale(
        inputState: CarVirtualDisplayProbeInputState,
        viewport: CarVirtualDisplayProbeViewport,
    ): Boolean {
        val targetComposeView = composeView ?: return false
        val focusPoint = inputState.surfacePointOrNull ?: return false
        val scaleFactor = inputState.scaleFactor ?: return false

        finishDragGesture(isCanceled = true)
        mainHandler.removeCallbacks(finishScaleRunnable)

        val now = SystemClock.uptimeMillis()
        val currentScaleGestureState = ensureScaleGestureState(
            targetComposeView = targetComposeView,
            viewport = viewport,
            focusPoint = focusPoint,
            eventTime = now,
        )
        val nextSpan = (currentScaleGestureState.currentSpan * scaleFactor)
            .coerceIn(MIN_SCALE_SPAN_PX, viewport.maxScaleSpanPx())
        val nextScaleGestureState = currentScaleGestureState.copy(
            focusPoint = focusPoint,
            currentSpan = nextSpan,
        )
        scaleGestureState = nextScaleGestureState

        val didHandleEvent = targetComposeView.dispatchRecycledEvents(
            createScaleMotionEvent(
                scaleGestureState = nextScaleGestureState,
                viewport = viewport,
                eventTime = now,
                action = MotionEvent.ACTION_MOVE,
            ),
        )

        scheduleScaleFinish()
        return didHandleEvent
    }

    private fun ensureDragGestureState(
        targetComposeView: ComposeView,
        viewport: CarVirtualDisplayProbeViewport,
        anchorPoint: CarVirtualDisplaySurfacePoint,
        eventTime: Long,
    ): CarVirtualDisplayDragGestureState {
        val currentDragGestureState = dragGestureState

        if (currentDragGestureState != null) {
            return currentDragGestureState
        }

        val coercedAnchorPoint = viewport.coerceObservedSurfacePoint(anchorPoint)
        targetComposeView.dispatchRecycledEvents(
            createSinglePointerMotionEvent(
                downTime = eventTime,
                eventTime = eventTime,
                action = MotionEvent.ACTION_DOWN,
                point = coercedAnchorPoint,
            ),
        )

        return CarVirtualDisplayDragGestureState(
            downTime = eventTime,
            currentPoint = coercedAnchorPoint,
        ).also {
            dragGestureState = it
        }
    }

    private fun ensureScaleGestureState(
        targetComposeView: ComposeView,
        viewport: CarVirtualDisplayProbeViewport,
        focusPoint: CarVirtualDisplaySurfacePoint,
        eventTime: Long,
    ): CarVirtualDisplayScaleGestureState {
        val currentScaleGestureState = scaleGestureState

        if (currentScaleGestureState != null) {
            return currentScaleGestureState
        }

        val initialSpan = viewport.initialScaleSpanPx()
        val initialScaleGestureState = CarVirtualDisplayScaleGestureState(
            downTime = eventTime,
            focusPoint = focusPoint,
            currentSpan = initialSpan,
        )

        targetComposeView.dispatchRecycledEvents(
            createScaleMotionEvent(
                scaleGestureState = initialScaleGestureState,
                viewport = viewport,
                eventTime = eventTime,
                action = MotionEvent.ACTION_DOWN,
                pointerCount = 1,
            ),
            createScaleMotionEvent(
                scaleGestureState = initialScaleGestureState,
                viewport = viewport,
                eventTime = eventTime,
                action = SECOND_POINTER_DOWN_ACTION,
            ),
        )

        return initialScaleGestureState.also {
            scaleGestureState = it
        }
    }

    private fun createFlingMotionEvents(
        dragGestureState: CarVirtualDisplayDragGestureState,
        viewport: CarVirtualDisplayProbeViewport,
        velocity: CarVirtualDisplaySurfaceVector,
        startEventTime: Long,
    ): List<MotionEvent> {
        val events = mutableListOf<MotionEvent>()
        var currentPoint = dragGestureState.currentPoint
        var decay = 1f

        repeat(FLING_MOVE_COUNT) { moveIndex ->
            val nextPoint = viewport.coerceObservedSurfacePoint(
                currentPoint + velocity.toFlingMoveDelta(decay),
            )
            val eventTime = startEventTime + FLING_MOVE_INTERVAL_MS * (moveIndex + 1)
            currentPoint = nextPoint
            events += createSinglePointerMotionEvent(
                downTime = dragGestureState.downTime,
                eventTime = eventTime,
                action = MotionEvent.ACTION_MOVE,
                point = currentPoint,
            )
            decay *= FLING_VELOCITY_DECAY
        }

        val upEventTime = startEventTime + FLING_MOVE_INTERVAL_MS * (FLING_MOVE_COUNT + 1)
        events += createSinglePointerMotionEvent(
            downTime = dragGestureState.downTime,
            eventTime = upEventTime,
            action = MotionEvent.ACTION_UP,
            point = currentPoint,
        )

        return events
    }

    private fun finishDragGesture(isCanceled: Boolean) {
        val targetComposeView = composeView ?: return
        val currentDragGestureState = dragGestureState ?: return
        dragGestureState = null
        mainHandler.removeCallbacks(finishDragRunnable)

        val action = if (isCanceled) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP
        val event = createSinglePointerMotionEvent(
            downTime = currentDragGestureState.downTime,
            eventTime = SystemClock.uptimeMillis(),
            action = action,
            point = currentDragGestureState.currentPoint,
        )
        targetComposeView.dispatchRecycledEvents(event)
    }

    private fun finishScaleGesture(isCanceled: Boolean) {
        val targetComposeView = composeView ?: return
        val currentScaleGestureState = scaleGestureState ?: return
        scaleGestureState = null
        mainHandler.removeCallbacks(finishScaleRunnable)

        val eventTime = SystemClock.uptimeMillis()
        val pointerUpAction = if (isCanceled) MotionEvent.ACTION_CANCEL else SECOND_POINTER_UP_ACTION
        val pointerUpEvent = createScaleMotionEvent(
            scaleGestureState = currentScaleGestureState,
            viewport = null,
            eventTime = eventTime,
            action = pointerUpAction,
        )
        val finalEvents = if (isCanceled) {
            listOf(pointerUpEvent)
        } else {
            val upEvent = createScaleMotionEvent(
                scaleGestureState = currentScaleGestureState,
                viewport = null,
                eventTime = eventTime + CLICK_UP_DELAY_MS,
                action = MotionEvent.ACTION_UP,
                pointerCount = 1,
            )
            listOf(pointerUpEvent, upEvent)
        }

        targetComposeView.dispatchRecycledEvents(events = finalEvents)
    }

    private fun cancelActiveContinuousGesture() {
        finishDragGesture(isCanceled = true)
        finishScaleGesture(isCanceled = true)
    }

    private fun scheduleDragFinish() {
        mainHandler.postDelayed(
            finishDragRunnable,
            DRAG_FINISH_DELAY_MS,
        )
    }

    private fun scheduleScaleFinish() {
        mainHandler.postDelayed(
            finishScaleRunnable,
            SCALE_FINISH_DELAY_MS,
        )
    }
}
