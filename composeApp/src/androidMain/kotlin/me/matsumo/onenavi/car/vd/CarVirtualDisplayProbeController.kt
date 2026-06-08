package me.matsumo.onenavi.car.vd

import android.content.Context
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
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
    private var currentViewport: CarVirtualDisplayProbeViewport? = null

    fun attachSurface(surfaceContainer: SurfaceContainer) {
        val hostSurface = surfaceContainer.surface

        if (hostSurface == null) {
            Log.w(TAG, "Host surface is null. container=$surfaceContainer")
            return
        }

        if (!hostSurface.isValid) {
            Log.w(TAG, "Host surface is invalid. container=$surfaceContainer")
            return
        }

        releaseVirtualDisplay()
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

    fun release() {
        currentViewport = null
        releaseVirtualDisplay()
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
        virtualDisplayResult.onFailure(::logVirtualDisplayFailure)
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

    private fun logVirtualDisplayFailure(throwable: Throwable) {
        Log.e(TAG, "VirtualDisplay creation failed.", throwable)
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

        /** host Surface へ Presentation を描画しつつ、外部 content の mirror 混入を避ける VD flags。 */
        const val VIRTUAL_DISPLAY_FLAGS = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
    }
}
