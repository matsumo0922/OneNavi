package me.matsumo.onenavi.car.vd

import android.content.Context
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.util.DisplayMetrics
import android.view.Display
import android.view.Surface
import androidx.car.app.SurfaceContainer
import io.github.aakira.napier.Napier

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
            Napier.w(tag = TAG) { "Host surface is null. container=$surfaceContainer" }
            release()
            return
        }

        if (!hostSurface.isValid) {
            Napier.w(tag = TAG) { "Host surface is invalid. container=$surfaceContainer" }
            currentViewport = null
            releaseVirtualDisplay()
            releaseCurrentHostSurface(exceptSurface = hostSurface)
            releaseHostSurface(hostSurface)
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
        logVirtualDisplayRequest(surfaceContainer, initialViewport)
        currentViewport = initialViewport
        createVirtualDisplayResult(hostSurface, initialViewport)
    }

    fun updateVisibleArea(visibleArea: Rect) {
        val updatedViewport = currentViewport?.withVisibleArea(visibleArea) ?: return

        currentViewport = updatedViewport
        presentation?.updateViewport(updatedViewport)
        Napier.i(tag = TAG) { "Viewport visible applied. visible=${updatedViewport.visibleAreaLabel}" }
    }

    fun updateStableArea(stableArea: Rect) {
        val updatedViewport = currentViewport?.withStableArea(stableArea) ?: return

        currentViewport = updatedViewport
        presentation?.updateViewport(updatedViewport)
        Napier.i(tag = TAG) { "Viewport stable applied. stable=${updatedViewport.stableAreaLabel}" }
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

    fun updateClickInput(hostInputX: Float, hostInputY: Float) {
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

        publishInputState(inputState)
        dispatchClickInput(inputState)
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

        publishInputState(inputState)
        dispatchScrollInput(inputState)
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

        publishInputState(inputState)
        dispatchFlingInput(inputState)
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

        publishInputState(inputState)
        dispatchScaleInput(inputState)
    }

    fun release() {
        currentViewport = null
        releaseVirtualDisplay()
        releaseCurrentHostSurface()
    }

    private fun createVirtualDisplayResult(surface: Surface, viewport: CarVirtualDisplayProbeViewport) {
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
            handleVirtualDisplayCreated(createdVirtualDisplay, viewport)
        }
        virtualDisplayResult.onFailure { throwable ->
            currentViewport = null
            logVirtualDisplayFailure(throwable)
            releaseCurrentHostSurface()
        }
    }

    private fun handleVirtualDisplayCreated(createdVirtualDisplay: VirtualDisplay, viewport: CarVirtualDisplayProbeViewport) {
        virtualDisplay = createdVirtualDisplay

        val displayId = createdVirtualDisplay.display.displayId
        Napier.i(tag = TAG) { "VirtualDisplay created. displayId=$displayId display=${createdVirtualDisplay.display}" }
        showProbePresentation(createdVirtualDisplay.display, viewport)
    }

    private fun showProbePresentation(display: Display, viewport: CarVirtualDisplayProbeViewport) {
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
            Napier.i(tag = TAG) { "Probe presentation shown. displayId=${display.displayId}" }
        }
        presentationResult.onFailure { throwable ->
            Napier.e(tag = TAG, throwable = throwable) {
                "Probe presentation show failed. displayId=${display.displayId}"
            }
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
            Napier.i(tag = TAG) { "VirtualDisplay released." }
        }
        releaseResult.onFailure { throwable ->
            Napier.e(tag = TAG, throwable = throwable) { "VirtualDisplay release failed." }
        }
    }

    private fun releaseCurrentHostSurface(exceptSurface: Surface? = null) {
        val releasingHostSurface = currentHostSurface ?: return

        if (releasingHostSurface === exceptSurface) {
            return
        }

        currentHostSurface = null
        releaseHostSurface(releasingHostSurface)
    }

    private fun releaseHostSurface(surface: Surface) {
        if (currentHostSurface === surface) {
            currentHostSurface = null
        }

        val releaseResult = runCatching {
            surface.release()
        }

        releaseResult.onSuccess {
            Napier.i(tag = TAG) { "Host Surface released." }
        }
        releaseResult.onFailure { throwable ->
            Napier.e(tag = TAG, throwable = throwable) { "Host Surface release failed." }
        }
    }

    private fun logVirtualDisplayFailure(throwable: Throwable) {
        Napier.e(tag = TAG, throwable = throwable) { "VirtualDisplay creation failed." }
    }

    private fun logVirtualDisplayRequest(surfaceContainer: SurfaceContainer, viewport: CarVirtualDisplayProbeViewport) {
        Napier.i(tag = TAG) {
            "VirtualDisplay request. surface=${viewport.surfaceWidth}x${viewport.surfaceHeight} " +
                "requestedDpi=${viewport.densityDpi} containerDpi=${surfaceContainer.dpi} " +
                "appMetrics=${appContext.resources.displayMetrics.toLogLabel()}"
        }
    }

    private fun DisplayMetrics.toLogLabel(): String {
        return "${widthPixels}x$heightPixels density=$density dpi=$densityDpi xdpi=$xdpi ydpi=$ydpi"
    }

    private fun dispatchClickInput(inputState: CarVirtualDisplayProbeInputState) {
        dispatchPresentationInput(
            inputLabel = "Click",
            inputState = inputState,
            dispatch = CarVirtualDisplayProbePresentation::dispatchClickInput,
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
            logInputSkipped(inputLabel, inputState)
        }
    }

    private fun logInputSkipped(inputLabel: String, inputState: CarVirtualDisplayProbeInputState) {
        Napier.i(tag = TAG) { "$inputLabel injection skipped. ${inputState.logLabel}" }
    }

    private fun findViewportForInput(inputLabel: String): CarVirtualDisplayProbeViewport? {
        val viewport = currentViewport

        if (viewport == null) {
            Napier.w(tag = TAG) { "Input ignored before viewport is ready. input=$inputLabel" }
            return null
        }

        return viewport
    }

    private fun publishInputState(inputState: CarVirtualDisplayProbeInputState) {
        currentInputState = inputState
        presentation?.updateInputState(inputState)
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
            Napier.i(tag = TAG) { "Probe presentation dismissed." }
        }
        dismissResult.onFailure { throwable ->
            Napier.e(tag = TAG, throwable = throwable) { "Probe presentation dismiss failed." }
        }
    }

    private fun normalizePositiveDimension(value: Int, fallback: Int): Int {
        return value.takeIf { it > 0 } ?: fallback
    }

    /** VD controller のログタグと既定値。 */
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
