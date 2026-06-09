package me.matsumo.onenavi.car.vd

import android.content.Context
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.Surface
import androidx.car.app.SurfaceContainer

/** Android Auto host Surface の上に Presentation 描画用 VirtualDisplay を構築する検証 controller。 */
class CarVirtualDisplayProbeController(
    context: Context,
) {

    private val appContext = context.applicationContext
    private val displayManager = requireNotNull(appContext.getSystemService(DisplayManager::class.java)) {
        "DisplayManager is not available."
    }
    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: CarVirtualDisplayProbePresentation? = null
    private var currentHostSurface: Surface? = null
    private var currentViewport: CarVirtualDisplayProbeViewport? = null
    private var inputSequence = 0L
    private var isInPanMode = false
    private var currentInputState = createInitialCarVirtualDisplayProbeInputState()

    fun attachSurface(surfaceContainer: SurfaceContainer) {
        val hostSurface = surfaceContainer.surface

        if (hostSurface == null) {
            Log.w(TAG, "Host surface is null. container=$surfaceContainer")
            release()
            return
        }

        if (!hostSurface.isValid) {
            Log.w(TAG, "Host surface is invalid. container=$surfaceContainer")
            currentViewport = null
            releaseVirtualDisplay()
            releaseCurrentHostSurface(exceptSurface = hostSurface)
            releaseHostSurface(surface = hostSurface)
            return
        }

        releaseVirtualDisplay()
        releaseCurrentHostSurface(exceptSurface = hostSurface)
        currentHostSurface = hostSurface

        val initialViewport = createCarVirtualDisplayProbeViewport(
            surfaceWidth = normalizePositiveDimension(surfaceContainer.width, DEFAULT_SURFACE_WIDTH),
            surfaceHeight = normalizePositiveDimension(surfaceContainer.height, DEFAULT_SURFACE_HEIGHT),
            densityDpi = normalizePositiveDimension(surfaceContainer.dpi, DEFAULT_DENSITY_DPI),
        )
        currentViewport = initialViewport
        createVirtualDisplayResult(
            surface = hostSurface,
            viewport = initialViewport,
        )
    }

    fun updateVisibleArea(visibleArea: Rect) {
        val updatedViewport = currentViewport?.withVisibleArea(
            visibleArea = visibleArea,
        ) ?: return

        currentViewport = updatedViewport
        presentation?.updateViewport(viewport = updatedViewport)
        Log.i(TAG, "Viewport visible applied. visible=${updatedViewport.visibleAreaLabel}")
    }

    fun updateStableArea(stableArea: Rect) {
        val updatedViewport = currentViewport?.withStableArea(
            stableArea = stableArea,
        ) ?: return

        currentViewport = updatedViewport
        presentation?.updateViewport(viewport = updatedViewport)
        Log.i(TAG, "Viewport stable applied. stable=${updatedViewport.stableAreaLabel}")
    }

    fun updatePanMode(isInPanMode: Boolean) {
        this.isInPanMode = isInPanMode
        publishInputState(
            inputState = createCarVirtualDisplayProbePanModeInputState(
                sequence = nextInputSequence(),
                isInPanMode = isInPanMode,
            ),
        )
    }

    fun updateClickInput(
        hostInputX: Float,
        hostInputY: Float,
        callbackUptimeMillis: Long = SystemClock.uptimeMillis(),
    ) {
        val viewport = findViewportForInput(
            inputLabel = "click",
        ) ?: return

        val inputState = createCarVirtualDisplayProbeClickInputState(
            sequence = nextInputSequence(),
            viewport = viewport,
            hostInputX = hostInputX,
            hostInputY = hostInputY,
            isInPanMode = isInPanMode,
        )

        publishInputState(inputState = inputState)
        dispatchClickInput(
            inputState = inputState,
            callbackUptimeMillis = callbackUptimeMillis,
        )
    }

    fun updateScrollInput(distanceX: Float, distanceY: Float) {
        val viewport = findViewportForInput(
            inputLabel = "scroll",
        ) ?: return
        val inputState = createCarVirtualDisplayProbeScrollInputState(
            sequence = nextInputSequence(),
            viewport = viewport,
            distanceX = distanceX,
            distanceY = distanceY,
            isInPanMode = isInPanMode,
        )

        publishInputState(inputState = inputState)
        dispatchScrollInput(inputState = inputState)
    }

    fun updateFlingInput(velocityX: Float, velocityY: Float) {
        val viewport = findViewportForInput(
            inputLabel = "fling",
        ) ?: return
        val inputState = createCarVirtualDisplayProbeFlingInputState(
            sequence = nextInputSequence(),
            viewport = viewport,
            velocityX = velocityX,
            velocityY = velocityY,
            isInPanMode = isInPanMode,
        )

        publishInputState(inputState = inputState)
        dispatchFlingInput(inputState = inputState)
    }

    fun updateScaleInput(focusX: Float, focusY: Float, scaleFactor: Float) {
        val viewport = findViewportForInput(
            inputLabel = "scale",
        ) ?: return

        val inputState = createCarVirtualDisplayProbeScaleInputState(
            sequence = nextInputSequence(),
            viewport = viewport,
            focusX = focusX,
            focusY = focusY,
            scaleFactor = scaleFactor,
            isInPanMode = isInPanMode,
        )

        publishInputState(inputState = inputState)
        dispatchScaleInput(inputState = inputState)
    }

    fun release() {
        currentViewport = null
        releaseVirtualDisplay()
        releaseCurrentHostSurface()
    }

    private fun createVirtualDisplayResult(
        surface: Surface,
        viewport: CarVirtualDisplayProbeViewport,
    ) {
        val virtualDisplayResult = runCatching {
            checkNotNull(
                displayManager.createVirtualDisplay(
                    VIRTUAL_DISPLAY_NAME,
                    viewport.surfaceWidth,
                    viewport.surfaceHeight,
                    viewport.densityDpi,
                    surface,
                    VIRTUAL_DISPLAY_FLAGS,
                ),
            ) {
                "DisplayManager returned null VirtualDisplay."
            }
        }

        virtualDisplayResult.onSuccess { createdVirtualDisplay ->
            handleVirtualDisplayCreated(
                createdVirtualDisplay = createdVirtualDisplay,
                viewport = viewport,
            )
        }
        virtualDisplayResult.onFailure { throwable ->
            currentViewport = null
            logVirtualDisplayFailure(throwable)
            releaseCurrentHostSurface()
        }
    }

    private fun handleVirtualDisplayCreated(
        createdVirtualDisplay: VirtualDisplay,
        viewport: CarVirtualDisplayProbeViewport,
    ) {
        virtualDisplay = createdVirtualDisplay

        val displayId = createdVirtualDisplay.display.displayId
        Log.i(TAG, "VirtualDisplay created. displayId=$displayId display=${createdVirtualDisplay.display}")
        showProbePresentation(
            display = createdVirtualDisplay.display,
            viewport = viewport,
        )
    }

    private fun showProbePresentation(
        display: Display,
        viewport: CarVirtualDisplayProbeViewport,
    ) {
        dismissProbePresentation()

        val presentationResult = runCatching {
            CarVirtualDisplayProbePresentation(
                outerContext = appContext,
                display = display,
                initialViewport = viewport,
                initialInputState = currentInputState,
            ).also { probePresentation ->
                probePresentation.show()
            }
        }

        presentationResult.onSuccess { probePresentation ->
            presentation = probePresentation
            Log.i(TAG, "Probe presentation shown. displayId=${display.displayId}")
        }
        presentationResult.onFailure { throwable ->
            Log.e(TAG, "Probe presentation show failed. displayId=${display.displayId}", throwable)
        }
    }

    private fun releaseVirtualDisplay() {
        dismissProbePresentation()

        val releasingVirtualDisplay = virtualDisplay ?: return
        virtualDisplay = null

        val releaseResult = runCatching {
            releasingVirtualDisplay.release()
        }

        releaseResult.onSuccess {
            Log.i(TAG, "VirtualDisplay released.")
        }
        releaseResult.onFailure { throwable ->
            Log.e(TAG, "VirtualDisplay release failed.", throwable)
        }
    }

    private fun releaseCurrentHostSurface(exceptSurface: Surface? = null) {
        val releasingHostSurface = currentHostSurface ?: return

        if (releasingHostSurface === exceptSurface) {
            return
        }

        currentHostSurface = null
        releaseHostSurface(surface = releasingHostSurface)
    }

    private fun releaseHostSurface(surface: Surface) {
        if (currentHostSurface === surface) {
            currentHostSurface = null
        }

        val releaseResult = runCatching {
            surface.release()
        }

        releaseResult.onSuccess {
            Log.i(TAG, "Host Surface released.")
        }
        releaseResult.onFailure { throwable ->
            Log.e(TAG, "Host Surface release failed.", throwable)
        }
    }

    private fun logVirtualDisplayFailure(throwable: Throwable) {
        Log.e(TAG, "VirtualDisplay creation failed.", throwable)
    }

    private fun dispatchClickInput(
        inputState: CarVirtualDisplayProbeInputState,
        callbackUptimeMillis: Long,
    ) {
        val dispatchStartedAt = SystemClock.uptimeMillis()
        val didStartClick = presentation?.dispatchClickInput(
            inputState = inputState,
        ) ?: false
        val dispatchFinishedAt = SystemClock.uptimeMillis()

        Log.i(
            TAG,
            "Click injection started. dispatched=$didStartClick " +
                "callbackToDispatchMs=${dispatchStartedAt - callbackUptimeMillis} " +
                "dispatchMs=${dispatchFinishedAt - dispatchStartedAt} ${inputState.logLabel}",
        )
    }

    private fun dispatchScrollInput(inputState: CarVirtualDisplayProbeInputState) {
        dispatchPresentationInput(
            inputLabel = "Scroll",
            inputState = inputState,
            dispatch = CarVirtualDisplayProbePresentation::dispatchScrollInput,
        )
    }

    private fun dispatchFlingInput(inputState: CarVirtualDisplayProbeInputState) {
        dispatchPresentationInput(
            inputLabel = "Fling",
            inputState = inputState,
            dispatch = CarVirtualDisplayProbePresentation::dispatchFlingInput,
        )
    }

    private fun dispatchScaleInput(inputState: CarVirtualDisplayProbeInputState) {
        dispatchPresentationInput(
            inputLabel = "Scale",
            inputState = inputState,
            dispatch = CarVirtualDisplayProbePresentation::dispatchScaleInput,
        )
    }

    private fun dispatchPresentationInput(
        inputLabel: String,
        inputState: CarVirtualDisplayProbeInputState,
        dispatch: CarVirtualDisplayProbePresentation.(CarVirtualDisplayProbeInputState) -> Boolean,
    ) {
        val didDispatch = presentation?.dispatch(inputState) ?: false

        if (!didDispatch) {
            logInputSkipped(
                inputLabel = inputLabel,
                inputState = inputState,
            )
        }
    }

    private fun logInputSkipped(
        inputLabel: String,
        inputState: CarVirtualDisplayProbeInputState,
    ) {
        Log.i(TAG, "$inputLabel injection skipped. ${inputState.logLabel}")
    }

    private fun findViewportForInput(inputLabel: String): CarVirtualDisplayProbeViewport? {
        val viewport = currentViewport

        if (viewport == null) {
            Log.w(TAG, "Input ignored before viewport is ready. input=$inputLabel")
            return null
        }

        return viewport
    }

    private fun publishInputState(inputState: CarVirtualDisplayProbeInputState) {
        currentInputState = inputState
        presentation?.updateInputState(inputState = inputState)
    }

    private fun nextInputSequence(): Long {
        inputSequence += 1
        return inputSequence
    }

    private fun dismissProbePresentation() {
        val dismissingPresentation = presentation ?: return
        presentation = null

        val dismissResult = runCatching {
            dismissingPresentation.dismiss()
        }

        dismissResult.onSuccess {
            Log.i(TAG, "Probe presentation dismissed.")
        }
        dismissResult.onFailure { throwable ->
            Log.e(TAG, "Probe presentation dismiss failed.", throwable)
        }
    }

    private fun normalizePositiveDimension(value: Int, fallback: Int): Int {
        return value.takeIf { it > 0 } ?: fallback
    }

    private companion object {
        /** logcat 抽出用タグ。 */
        const val TAG = "OneNaviCarVd"

        /** host Surface の寸法が未確定な場合に使う検証用の横幅。 */
        const val DEFAULT_SURFACE_WIDTH = 1280

        /** host Surface の寸法が未確定な場合に使う検証用の高さ。 */
        const val DEFAULT_SURFACE_HEIGHT = 720

        /** host Surface の dpi が未確定な場合に使う検証用 density。 */
        const val DEFAULT_DENSITY_DPI = 240

        /** Android Auto host Surface に紐づく VirtualDisplay 名。 */
        const val VIRTUAL_DISPLAY_NAME = "OneNaviCarVirtualDisplayProbe"

        /**
         * host Surface へ Presentation を描画する private VD flags。
         *
         * public VD は Keyguard の対象になるため、ロック画面の時計が host Surface へ重なる。
         */
        const val VIRTUAL_DISPLAY_FLAGS = DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
    }
}
