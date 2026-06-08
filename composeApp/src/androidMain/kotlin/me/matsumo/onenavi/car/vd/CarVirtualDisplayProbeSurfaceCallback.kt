package me.matsumo.onenavi.car.vd

import android.graphics.Rect
import android.util.Log
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer

/** Android Auto host Surface の lifecycle を VD controller に渡す callback。 */
class CarVirtualDisplayProbeSurfaceCallback(
    private val controller: CarVirtualDisplayProbeController,
) : SurfaceCallback {

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        Log.i(TAG, "Surface available. container=$surfaceContainer")
        controller.attachSurface(surfaceContainer)
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        Log.i(TAG, "Visible area changed. visibleArea=$visibleArea")
        controller.updateVisibleArea(visibleArea)
    }

    override fun onStableAreaChanged(stableArea: Rect) {
        Log.i(TAG, "Stable area changed. stableArea=$stableArea")
        controller.updateStableArea(stableArea)
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        Log.i(TAG, "Surface destroyed. container=$surfaceContainer")
        controller.release()
    }

    private companion object {
        /** logcat 抽出用タグ。 */
        const val TAG = "OneNaviCarVd"
    }
}
