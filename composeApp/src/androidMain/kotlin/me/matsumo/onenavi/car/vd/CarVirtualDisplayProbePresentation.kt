package me.matsumo.onenavi.car.vd

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import me.matsumo.onenavi.R

/** VD 上へ ComposeView を出す検証用 Presentation。 */
class CarVirtualDisplayProbePresentation(
    outerContext: Context,
    display: Display,
    initialViewport: CarVirtualDisplayProbeViewport,
    initialInputState: CarVirtualDisplayProbeInputState,
) : Presentation(outerContext, display, R.style.Theme_Matsumo) {

    private val runtime = CarVirtualDisplayRuntime()
    private var viewport by mutableStateOf(initialViewport)
    private var inputState by mutableStateOf(initialInputState)
    private var composeView: ComposeView? = null

    fun updateViewport(viewport: CarVirtualDisplayProbeViewport) {
        this.viewport = viewport
    }

    fun updateInputState(inputState: CarVirtualDisplayProbeInputState) {
        this.inputState = inputState
    }

    fun dispatchClickInput(inputState: CarVirtualDisplayProbeInputState): Boolean {
        val targetComposeView = composeView

        if (targetComposeView == null) {
            Log.w(TAG, "Click injection skipped. ComposeView is not ready.")
            return false
        }

        val eventX = inputState.surfaceX
        val eventY = inputState.surfaceY

        if (eventX == null || eventY == null) {
            Log.w(TAG, "Click injection skipped. surface point is missing.")
            return false
        }

        return dispatchClickMotionEvents(
            composeView = targetComposeView,
            eventX = eventX,
            eventY = eventY,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runtime.create(savedInstanceState = savedInstanceState)

        val createdComposeView = createComposeView()
        composeView = createdComposeView
        installViewTreeOwners(composeView = createdComposeView)
        setContentView(createdComposeView)
        installComposeContent(composeView = createdComposeView)
    }

    override fun onStart() {
        super.onStart()
        runtime.start()
        runtime.resume()
    }

    override fun onStop() {
        runtime.destroy()
        composeView = null
        super.onStop()
    }

    override fun onSaveInstanceState(): Bundle {
        val bundle = super.onSaveInstanceState()
        runtime.save(outState = bundle)
        return bundle
    }

    override fun onDisplayRemoved() {
        Log.i(TAG, "Presentation display removed. displayId=${display.displayId}")
        super.onDisplayRemoved()
    }

    override fun onDisplayChanged() {
        Log.i(TAG, "Presentation display changed. displayId=${display.displayId}")
        super.onDisplayChanged()
    }

    private fun installViewTreeOwners(composeView: ComposeView) {
        val decorView = requireNotNull(window).decorView
        runtime.installViewTreeOwners(view = decorView)
        runtime.installViewTreeOwners(view = composeView)
    }

    private fun createComposeView(): ComposeView {
        return ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        }
    }

    private fun installComposeContent(composeView: ComposeView) {
        composeView.setContent {
            CarVirtualDisplayProbeContent(
                modifier = Modifier.fillMaxSize(),
                displayId = display.displayId,
                expectedDisplayId = display.displayId,
                rendererLabel = "Presentation",
                viewport = viewport,
                inputState = inputState,
            )
        }
    }

    private fun dispatchClickMotionEvents(
        composeView: ComposeView,
        eventX: Float,
        eventY: Float,
    ): Boolean {
        val downTime = SystemClock.uptimeMillis()
        val upTime = downTime + CLICK_UP_DELAY_MS
        val downEvent = createClickMotionEvent(
            downTime = downTime,
            eventTime = downTime,
            action = MotionEvent.ACTION_DOWN,
            eventX = eventX,
            eventY = eventY,
        )
        val upEvent = createClickMotionEvent(
            downTime = downTime,
            eventTime = upTime,
            action = MotionEvent.ACTION_UP,
            eventX = eventX,
            eventY = eventY,
        )

        return try {
            val isDownHandled = composeView.dispatchTouchEvent(downEvent)
            val isUpHandled = composeView.dispatchTouchEvent(upEvent)

            Log.i(
                TAG,
                "Click injected. surface=${eventX.toInt()},${eventY.toInt()} " +
                    "down=$isDownHandled up=$isUpHandled",
            )
            isDownHandled || isUpHandled
        } finally {
            downEvent.recycle()
            upEvent.recycle()
        }
    }

    private fun createClickMotionEvent(
        downTime: Long,
        eventTime: Long,
        action: Int,
        eventX: Float,
        eventY: Float,
    ): MotionEvent {
        return MotionEvent.obtain(
            downTime,
            eventTime,
            action,
            eventX,
            eventY,
            0,
        ).apply {
            setSource(InputDevice.SOURCE_TOUCHSCREEN)
        }
    }

    private companion object {
        /** logcat 抽出用タグ。 */
        const val TAG = "OneNaviCarVd"

        /** click 注入時の down/up 間隔。 */
        const val CLICK_UP_DELAY_MS = 32L
    }
}
