package me.matsumo.onenavi.car.vd

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.Display
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

    fun updateViewport(viewport: CarVirtualDisplayProbeViewport) {
        this.viewport = viewport
    }

    fun updateInputState(inputState: CarVirtualDisplayProbeInputState) {
        this.inputState = inputState
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runtime.create(savedInstanceState = savedInstanceState)

        val composeView = createComposeView()
        installViewTreeOwners(composeView = composeView)
        setContentView(composeView)
        installComposeContent(composeView = composeView)
    }

    override fun onStart() {
        super.onStart()
        runtime.start()
        runtime.resume()
    }

    override fun onStop() {
        runtime.destroy()
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

    private companion object {
        /** logcat 抽出用タグ。 */
        const val TAG = "OneNaviCarVd"
    }
}
