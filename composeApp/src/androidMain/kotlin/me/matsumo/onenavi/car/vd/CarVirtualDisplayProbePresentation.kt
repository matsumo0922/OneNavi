package me.matsumo.onenavi.car.vd

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.view.Display
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import io.github.aakira.napier.Napier
import me.matsumo.onenavi.R

/** VD 上へ ComposeView を出す検証用 Presentation。 */
class CarVirtualDisplayProbePresentation(
    outerContext: Context,
    display: Display,
    initialViewport: CarVirtualDisplayProbeViewport,
    initialInputState: CarVirtualDisplayProbeInputState,
) : Presentation(outerContext, display, R.style.Theme_Matsumo) {

    private val runtime = CarVirtualDisplayRuntime()
    private val gestureDispatcher = CarVirtualDisplayProbeGestureDispatcher()
    private val semanticsClickDispatcher = CarVirtualDisplayProbeSemanticsClickDispatcher()
    private var viewport by mutableStateOf(initialViewport)
    private var inputState by mutableStateOf(initialInputState)
    private var clickCoordinateResult by mutableStateOf<CarVirtualDisplayProbeClickCoordinateResult?>(null)
    private var composeView: ComposeView? = null

    fun updateViewport(viewport: CarVirtualDisplayProbeViewport) {
        this.viewport = viewport
    }

    fun updateInputState(inputState: CarVirtualDisplayProbeInputState) {
        this.inputState = inputState

        if (inputState.kind != CarVirtualDisplayProbeInputKind.Click) {
            clickCoordinateResult = null
        }
    }

    fun dispatchClickInput(inputState: CarVirtualDisplayProbeInputState): Boolean {
        val targetComposeView = composeView

        if (targetComposeView == null) {
            Napier.w(tag = TAG) { "Click injection skipped. ComposeView is not ready." }
            return false
        }

        val eventX = inputState.surfaceX
        val eventY = inputState.surfaceY

        if (eventX == null || eventY == null) {
            Napier.w(tag = TAG) { "Click injection skipped. surface point is missing." }
            return false
        }

        return dispatchClickMotionEvents(inputState)
    }

    fun dispatchScrollInput(inputState: CarVirtualDisplayProbeInputState): Boolean {
        return gestureDispatcher.dispatchScroll(inputState, viewport)
    }

    fun dispatchFlingInput(inputState: CarVirtualDisplayProbeInputState): Boolean {
        return gestureDispatcher.dispatchFling(inputState, viewport)
    }

    fun dispatchScaleInput(inputState: CarVirtualDisplayProbeInputState): Boolean {
        return gestureDispatcher.dispatchScale(inputState, viewport)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runtime.create(savedInstanceState)

        val createdComposeView = createComposeView()
        composeView = createdComposeView
        gestureDispatcher.attach(createdComposeView)
        installViewTreeOwners(createdComposeView)
        setContentView(createdComposeView)
        installComposeContent(createdComposeView)
    }

    override fun onStart() {
        super.onStart()
        runtime.start()
        runtime.resume()
    }

    override fun onStop() {
        runtime.destroy()
        gestureDispatcher.detach()
        composeView = null
        super.onStop()
    }

    override fun onSaveInstanceState(): Bundle {
        val bundle = super.onSaveInstanceState()
        runtime.save(bundle)
        return bundle
    }

    override fun onDisplayRemoved() {
        Napier.i(tag = TAG) { "Presentation display removed. displayId=${display.displayId}" }
        super.onDisplayRemoved()
    }

    override fun onDisplayChanged() {
        Napier.i(tag = TAG) { "Presentation display changed. displayId=${display.displayId}" }
        super.onDisplayChanged()
    }

    private fun installViewTreeOwners(composeView: ComposeView) {
        val decorView = requireNotNull(window).decorView
        runtime.installViewTreeOwners(decorView)
        runtime.installViewTreeOwners(composeView)
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
                clickCoordinateResult = clickCoordinateResult,
            )
        }
    }

    private fun dispatchClickMotionEvents(inputState: CarVirtualDisplayProbeInputState): Boolean {
        val targetComposeView = composeView
        clickCoordinateResult = null

        val dispatchCoordinate = inputState.resolveCarVirtualDisplayProbeClickDispatchCoordinate(viewport) ?: return false

        if (!viewport.containsClickDispatchCoordinate(dispatchCoordinate)) {
            return false
        }

        val semanticsCoordinateResult = targetComposeView?.let { composeView ->
            semanticsClickDispatcher.dispatchClick(composeView, dispatchCoordinate)
        }

        if (semanticsCoordinateResult != null) {
            clickCoordinateResult = semanticsCoordinateResult
            return true
        }

        val didHandleClick = gestureDispatcher.dispatchClick(
            surfaceX = dispatchCoordinate.point.x,
            surfaceY = dispatchCoordinate.point.y,
        )
        clickCoordinateResult = CarVirtualDisplayProbeClickCoordinateResult(
            label = "$CLICK_COORDINATE_MOTION_EVENT_PREFIX:${dispatchCoordinate.label}",
            point = dispatchCoordinate.point,
        )

        return didHandleClick
    }

    /** Presentation のログタグ。 */
    private companion object {
        /** logcat 抽出用タグ。 */
        const val TAG = "OneNaviCarVd"
    }
}
