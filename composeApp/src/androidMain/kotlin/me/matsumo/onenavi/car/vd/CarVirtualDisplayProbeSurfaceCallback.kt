package me.matsumo.onenavi.car.vd

import android.graphics.Rect
import android.util.Log
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer

/** Android Auto host Surface の lifecycle と入力イベントを VD controller に渡す callback。 */
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

    override fun onScroll(distanceX: Float, distanceY: Float) {
        Log.i(TAG, "Surface scroll. distanceX=$distanceX distanceY=$distanceY")
        controller.updateScrollInput(
            distanceX = distanceX,
            distanceY = distanceY,
        )
    }

    override fun onFling(velocityX: Float, velocityY: Float) {
        Log.i(TAG, "Surface fling. velocityX=$velocityX velocityY=$velocityY")
        controller.updateFlingInput(
            velocityX = velocityX,
            velocityY = velocityY,
        )
    }

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        Log.i(TAG, "Surface scale. focusX=$focusX focusY=$focusY scaleFactor=$scaleFactor")
        controller.updateScaleInput(
            focusX = focusX,
            focusY = focusY,
            scaleFactor = scaleFactor,
        )
    }

    override fun onClick(surfaceX: Float, surfaceY: Float) {
        Log.i(TAG, "Surface click. surfaceX=$surfaceX surfaceY=$surfaceY")
        controller.updateClickInput(
            surfaceX = surfaceX,
            surfaceY = surfaceY,
        )
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
