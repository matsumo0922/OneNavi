package me.matsumo.onenavi.car.vd

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
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
    private val finishClickRunnable = Runnable {
        finishClickGesture(isCanceled = false)
    }

    private var composeView: ComposeView? = null
    private var clickGestureState: CarVirtualDisplayClickGestureState? = null
    private var dragGestureState: CarVirtualDisplayDragGestureState? = null
    private var flingGestureState: CarVirtualDisplayFlingGestureState? = null
    private var scaleGestureState: CarVirtualDisplayScaleGestureState? = null
    private var pendingFlingRunnable: Runnable? = null

    fun attach(composeView: ComposeView) {
        this.composeView = composeView
    }

    fun detach() {
        mainHandler.removeCallbacks(finishClickRunnable)
        mainHandler.removeCallbacks(finishDragRunnable)
        mainHandler.removeCallbacks(finishScaleRunnable)
        finishClickGesture(isCanceled = true)
        finishDragGesture(isCanceled = true)
        finishFlingGesture(isCanceled = true)
        finishScaleGesture(isCanceled = true)
        composeView = null
    }

    fun dispatchClick(surfaceX: Float, surfaceY: Float): Boolean {
        val targetComposeView = composeView ?: return false
        cancelActiveGestures()

        val touchPoint = CarVirtualDisplaySurfacePoint(
            surfaceX = surfaceX,
            surfaceY = surfaceY,
        )
        val downTime = SystemClock.uptimeMillis()
        clickGestureState = CarVirtualDisplayClickGestureState(
            downTime = downTime,
            downPoint = touchPoint,
        )
        val didHandleDown = targetComposeView.dispatchRecycledEvents(
            createSinglePointerMotionEvent(
                downTime = downTime,
                eventTime = downTime,
                action = MotionEvent.ACTION_DOWN,
                point = touchPoint,
            ),
        )

        scheduleClickFinish()
        return didHandleDown
    }

    fun dispatchScroll(
        inputState: CarVirtualDisplayProbeInputState,
        viewport: CarVirtualDisplayProbeViewport,
    ): Boolean {
        val targetComposeView = composeView ?: return false
        val distance = inputState.scrollDistanceOrNull ?: return false
        val anchorPoint = inputState.surfacePointOrNull ?: return false

        if (distance.isZero()) {
            val hadActiveDragGesture = dragGestureState != null
            finishDragGesture(isCanceled = false)
            Log.i(TAG, "Scroll move ignored. zeroDistance=true activeDrag=$hadActiveDragGesture")
            return true
        }

        finishClickGesture(isCanceled = true)
        finishFlingGesture(isCanceled = true)
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
        val didReuseDragGesture = currentDragGestureState.downTime != now
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

        Log.i(
            TAG,
            "Scroll move dispatched. handled=$didHandleEvent reuseDrag=$didReuseDragGesture " +
                "distance=${distance.toLogLabel()} point=${nextPoint.toLogLabel()}",
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
        val hadActiveDragGesture = dragGestureState != null

        finishClickGesture(isCanceled = true)
        finishFlingGesture(isCanceled = true)
        finishScaleGesture(isCanceled = true)
        mainHandler.removeCallbacks(finishDragRunnable)

        val now = SystemClock.uptimeMillis()
        val currentDragGestureState = ensureDragGestureState(
            targetComposeView = targetComposeView,
            viewport = viewport,
            anchorPoint = anchorPoint,
            eventTime = now,
        )
        flingGestureState = CarVirtualDisplayFlingGestureState(
            downTime = currentDragGestureState.downTime,
            currentPoint = currentDragGestureState.currentPoint,
        )
        dragGestureState = null

        Log.i(
            TAG,
            "Fling injection started. activeDrag=$hadActiveDragGesture " +
                "anchor=${anchorPoint.toLogLabel()} start=${currentDragGestureState.currentPoint.toLogLabel()} " +
                "velocity=${velocity.toLogLabel()}",
        )

        return dispatchFlingMove(
            viewport = viewport,
            velocity = velocity,
            moveIndex = 0,
            decay = 1f,
        )
    }

    fun dispatchScale(
        inputState: CarVirtualDisplayProbeInputState,
        viewport: CarVirtualDisplayProbeViewport,
    ): Boolean {
        val targetComposeView = composeView ?: return false
        val rawFocusPoint = inputState.surfacePointOrNull ?: return false
        val scaleFactor = inputState.scaleFactor ?: return false
        val isPlaceholderFocus = rawFocusPoint.surfaceX == 0f && rawFocusPoint.surfaceY == 0f
        val isNoopScale = scaleFactor == 1f

        if (isNoopScale) {
            Log.i(
                TAG,
                "Scale move ignored. noopScale=true placeholderFocus=$isPlaceholderFocus " +
                    "rawFocus=${rawFocusPoint.toLogLabel()}",
            )
            return true
        }

        val focusPoint = inputState.scaleFocusPointOrNull(viewport = viewport) ?: return false

        finishClickGesture(isCanceled = true)
        finishFlingGesture(isCanceled = true)
        finishDragGesture(isCanceled = true)
        mainHandler.removeCallbacks(finishScaleRunnable)

        val now = SystemClock.uptimeMillis()
        val currentScaleGestureState = ensureScaleGestureState(
            targetComposeView = targetComposeView,
            viewport = viewport,
            focusPoint = focusPoint,
            eventTime = now,
        )
        val boundedScaleGestureState = resetScaleGestureAtSpanLimit(
            targetComposeView = targetComposeView,
            viewport = viewport,
            focusPoint = focusPoint,
            currentScaleGestureState = currentScaleGestureState,
            scaleFactor = scaleFactor,
        )
        val rawNextSpan = boundedScaleGestureState.currentSpan * scaleFactor
        val nextSpan = rawNextSpan
            .coerceIn(MIN_SCALE_SPAN_PX, viewport.maxScaleSpanPx())
        val spanWasClamped = nextSpan != rawNextSpan
        val nextScaleGestureState = boundedScaleGestureState.copy(
            focusPoint = focusPoint,
            currentSpan = nextSpan,
        )
        val moveEventTime = SystemClock.uptimeMillis()
        scaleGestureState = nextScaleGestureState

        val didHandleEvent = targetComposeView.dispatchRecycledEvents(
            createScaleMotionEvent(
                scaleGestureState = nextScaleGestureState,
                viewport = viewport,
                eventTime = moveEventTime,
                action = MotionEvent.ACTION_MOVE,
            ),
        )

        Log.i(
            TAG,
            "Scale move dispatched. handled=$didHandleEvent rawFocus=${rawFocusPoint.toLogLabel()} " +
                "focus=${focusPoint.toLogLabel()} scale=$scaleFactor " +
                "span=${boundedScaleGestureState.currentSpan.toInt()}->${nextSpan.toInt()} " +
                "clamped=$spanWasClamped",
        )

        scheduleScaleFinish()
        return didHandleEvent
    }

    private fun resetScaleGestureAtSpanLimit(
        targetComposeView: ComposeView,
        viewport: CarVirtualDisplayProbeViewport,
        focusPoint: CarVirtualDisplaySurfacePoint,
        currentScaleGestureState: CarVirtualDisplayScaleGestureState,
        scaleFactor: Float,
    ): CarVirtualDisplayScaleGestureState {
        val maximumSpan = viewport.maxScaleSpanPx()
        val rawNextSpan = currentScaleGestureState.currentSpan * scaleFactor
        val isBelowMinimumSpan = rawNextSpan < MIN_SCALE_SPAN_PX
        val isAboveMaximumSpan = rawNextSpan > maximumSpan
        val shouldResetScaleGesture = isBelowMinimumSpan || isAboveMaximumSpan

        if (!shouldResetScaleGesture) {
            return currentScaleGestureState
        }

        finishScaleGesture(isCanceled = false)

        val restartTime = SystemClock.uptimeMillis()
        val restartedScaleGestureState = ensureScaleGestureState(
            targetComposeView = targetComposeView,
            viewport = viewport,
            focusPoint = focusPoint,
            eventTime = restartTime,
        )

        Log.i(
            TAG,
            "Scale gesture restarted. rawSpan=${rawNextSpan.toInt()} " +
                "bounds=${MIN_SCALE_SPAN_PX.toInt()}..${maximumSpan.toInt()} " +
                "focus=${focusPoint.toLogLabel()}",
        )

        return restartedScaleGestureState
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
        val didHandleDown = targetComposeView.dispatchRecycledEvents(
            createSinglePointerMotionEvent(
                downTime = eventTime,
                eventTime = eventTime,
                action = MotionEvent.ACTION_DOWN,
                point = coercedAnchorPoint,
            ),
        )

        Log.i(
            TAG,
            "Drag gesture started. handled=$didHandleDown anchor=${anchorPoint.toLogLabel()} " +
                "coerced=${coercedAnchorPoint.toLogLabel()}",
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

        val didHandleStart = targetComposeView.dispatchRecycledEvents(
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

        Log.i(
            TAG,
            "Scale gesture started. handled=$didHandleStart focus=${focusPoint.toLogLabel()} " +
                "span=${initialSpan.toInt()} maxSpan=${viewport.maxScaleSpanPx().toInt()}",
        )

        return initialScaleGestureState.also {
            scaleGestureState = it
        }
    }

    private fun dispatchFlingMove(
        viewport: CarVirtualDisplayProbeViewport,
        velocity: CarVirtualDisplaySurfaceVector,
        moveIndex: Int,
        decay: Float,
    ): Boolean {
        val targetComposeView = composeView ?: return false
        val currentFlingGestureState = flingGestureState ?: return false
        val moveDelta = velocity.toFlingMoveDelta(decay)
        val nextPoint = viewport.coerceObservedSurfacePoint(
            currentFlingGestureState.currentPoint + moveDelta,
        )
        val eventTime = SystemClock.uptimeMillis()
        val nextFlingGestureState = currentFlingGestureState.copy(
            currentPoint = nextPoint,
        )
        flingGestureState = nextFlingGestureState
        val didHandleEvent = targetComposeView.dispatchRecycledEvents(
            createSinglePointerMotionEvent(
                downTime = currentFlingGestureState.downTime,
                eventTime = eventTime,
                action = MotionEvent.ACTION_MOVE,
                point = nextPoint,
            ),
        )
        val nextMoveIndex = moveIndex + 1

        Log.i(
            TAG,
            "Fling move dispatched. handled=$didHandleEvent index=$moveIndex " +
                "delta=${moveDelta.toLogLabel()} point=${nextPoint.toLogLabel()} decay=$decay",
        )

        if (nextMoveIndex >= FLING_MOVE_COUNT) {
            scheduleFlingFinish()
        } else {
            scheduleFlingMove(
                viewport = viewport,
                velocity = velocity,
                moveIndex = nextMoveIndex,
                decay = decay * FLING_VELOCITY_DECAY,
            )
        }

        return didHandleEvent
    }

    private fun finishClickGesture(isCanceled: Boolean) {
        val currentClickGestureState = clickGestureState ?: return
        clickGestureState = null
        mainHandler.removeCallbacks(finishClickRunnable)

        val targetComposeView = composeView ?: return
        val eventTime = SystemClock.uptimeMillis()
        val action = if (isCanceled) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP
        val didHandleEvent = targetComposeView.dispatchRecycledEvents(
            createSinglePointerMotionEvent(
                downTime = currentClickGestureState.downTime,
                eventTime = eventTime,
                action = action,
                point = currentClickGestureState.downPoint,
            ),
        )

        Log.i(
            TAG,
            "Click finish dispatched. canceled=$isCanceled handled=$didHandleEvent " +
                "elapsedMs=${eventTime - currentClickGestureState.downTime}",
        )
    }

    private fun finishDragGesture(isCanceled: Boolean) {
        val targetComposeView = composeView ?: return
        val currentDragGestureState = dragGestureState ?: return
        dragGestureState = null
        mainHandler.removeCallbacks(finishDragRunnable)

        val action = if (isCanceled) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP
        val eventTime = SystemClock.uptimeMillis()
        val event = createSinglePointerMotionEvent(
            downTime = currentDragGestureState.downTime,
            eventTime = eventTime,
            action = action,
            point = currentDragGestureState.currentPoint,
        )
        val didHandleEvent = targetComposeView.dispatchRecycledEvents(event)

        Log.i(
            TAG,
            "Drag finish dispatched. canceled=$isCanceled handled=$didHandleEvent " +
                "point=${currentDragGestureState.currentPoint.toLogLabel()} " +
                "elapsedMs=${eventTime - currentDragGestureState.downTime}",
        )
    }

    private fun finishFlingGesture(isCanceled: Boolean) {
        val currentFlingGestureState = flingGestureState ?: return
        flingGestureState = null
        removePendingFlingRunnable()

        val targetComposeView = composeView ?: return
        val eventTime = SystemClock.uptimeMillis()
        val action = if (isCanceled) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP
        val didHandleEvent = targetComposeView.dispatchRecycledEvents(
            createSinglePointerMotionEvent(
                downTime = currentFlingGestureState.downTime,
                eventTime = eventTime,
                action = action,
                point = currentFlingGestureState.currentPoint,
            ),
        )

        Log.i(
            TAG,
            "Fling finish dispatched. canceled=$isCanceled handled=$didHandleEvent " +
                "elapsedMs=${eventTime - currentFlingGestureState.downTime}",
        )
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
                eventTime = eventTime,
                action = MotionEvent.ACTION_UP,
                pointerCount = 1,
            )
            listOf(pointerUpEvent, upEvent)
        }

        val didHandleEvent = targetComposeView.dispatchRecycledEvents(events = finalEvents)

        Log.i(
            TAG,
            "Scale finish dispatched. canceled=$isCanceled handled=$didHandleEvent " +
                "elapsedMs=${eventTime - currentScaleGestureState.downTime}",
        )
    }

    private fun cancelActiveGestures() {
        finishClickGesture(isCanceled = true)
        finishDragGesture(isCanceled = true)
        finishFlingGesture(isCanceled = true)
        finishScaleGesture(isCanceled = true)
    }

    private fun scheduleClickFinish() {
        mainHandler.postDelayed(
            finishClickRunnable,
            CLICK_UP_DELAY_MS,
        )
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

    private fun scheduleFlingMove(
        viewport: CarVirtualDisplayProbeViewport,
        velocity: CarVirtualDisplaySurfaceVector,
        moveIndex: Int,
        decay: Float,
    ) {
        schedulePendingFlingRunnable(
            Runnable {
                pendingFlingRunnable = null
                dispatchFlingMove(
                    viewport = viewport,
                    velocity = velocity,
                    moveIndex = moveIndex,
                    decay = decay,
                )
            },
        )
    }

    private fun scheduleFlingFinish() {
        schedulePendingFlingRunnable(
            Runnable {
                pendingFlingRunnable = null
                finishFlingGesture(isCanceled = false)
            },
        )
    }

    private fun schedulePendingFlingRunnable(runnable: Runnable) {
        removePendingFlingRunnable()
        pendingFlingRunnable = runnable
        mainHandler.postDelayed(
            runnable,
            FLING_MOVE_INTERVAL_MS,
        )
    }

    private fun removePendingFlingRunnable() {
        val removingRunnable = pendingFlingRunnable ?: return
        pendingFlingRunnable = null
        mainHandler.removeCallbacks(removingRunnable)
    }

    private companion object {
        /** logcat 抽出用タグ。 */
        const val TAG = "OneNaviCarVd"
    }
}
