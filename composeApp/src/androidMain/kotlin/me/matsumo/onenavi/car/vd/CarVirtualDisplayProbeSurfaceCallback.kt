package me.matsumo.onenavi.car.vd

import android.graphics.Rect
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import io.github.aakira.napier.Napier

/** Android Auto host Surface の lifecycle と入力イベントを VD controller に渡す callback。 */
class CarVirtualDisplayProbeSurfaceCallback(
    private val controller: CarVirtualDisplayProbeController,
) : SurfaceCallback {

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        Napier.i(tag = TAG) { "Surface available. container=$surfaceContainer" }
        controller.attachSurface(surfaceContainer)
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        Napier.i(tag = TAG) { "Visible area changed. visibleArea=$visibleArea" }
        controller.updateVisibleArea(visibleArea)
    }

    override fun onStableAreaChanged(stableArea: Rect) {
        Napier.i(tag = TAG) { "Stable area changed. stableArea=$stableArea" }
        controller.updateStableArea(stableArea)
    }

    override fun onScroll(distanceX: Float, distanceY: Float) {
        controller.updateScrollInput(
            distanceX = distanceX,
            distanceY = distanceY,
        )
    }

    override fun onFling(velocityX: Float, velocityY: Float) {
        controller.updateFlingInput(
            velocityX = velocityX,
            velocityY = velocityY,
        )
    }

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        controller.updateScaleInput(
            focusX = focusX,
            focusY = focusY,
            scaleFactor = scaleFactor,
        )
    }

    override fun onClick(surfaceX: Float, surfaceY: Float) {
        controller.updateClickInput(
            hostInputX = surfaceX,
            hostInputY = surfaceY,
        )
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        Napier.i(tag = TAG) { "Surface destroyed. container=$surfaceContainer" }
        controller.release()
    }

    /** Surface callback のログタグ。 */
    private companion object {
        /** logcat 抽出用タグ。 */
        const val TAG = "OneNaviCarVd"
    }
}
