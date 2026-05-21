package me.matsumo.onenavi.feature.map.state

import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.CameraPosition
import kotlin.math.abs

/**
 * GoogleMap gesture 中の一時状態と、自車追従を維持するかの判定を扱う controller。
 */
internal class MapCameraGestureController {

    private var wasFollowingBeforeGesture: Boolean = false
    private var gestureStartCameraPosition: CameraPosition? = null

    /** ユーザー gesture 中なら true。 */
    var isGestureInProgress: Boolean = false
        private set

    /**
     * GoogleMap の camera move start を処理する。
     *
     * @param reason GoogleMap の camera move 開始理由
     * @param isFollowingMyLocation gesture 開始直前に自車追従中だったか
     * @param cameraPosition gesture 開始時点の camera position
     * @return ユーザー gesture として扱う場合 true
     */
    fun onCameraMoveStarted(
        reason: Int,
        isFollowingMyLocation: Boolean,
        cameraPosition: CameraPosition,
    ): Boolean {
        if (reason != GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE) return false

        isGestureInProgress = true
        wasFollowingBeforeGesture = isFollowingMyLocation
        gestureStartCameraPosition = cameraPosition

        return true
    }

    /**
     * gesture 終了時に自車追従を維持するか判定する。
     *
     * zoom gesture は指の位置を中心に zoom されるため追従を解除する。rotate / tilt gesture も
     * ユーザーが見たい向きを明示した操作なので追従を解除する。pan gesture は map target が
     * 自車追従 target から離れた場合だけ追従を解除する。
     *
     * @param cameraPosition gesture 終了時点の camera position
     * @param isCameraTargetAwayFromVehicle camera target が自車追従 target から離れているかを返す関数
     * @return gesture 終了処理を行った場合は次の追従状態。gesture 中でない場合は null
     */
    fun finishIfNeeded(
        cameraPosition: CameraPosition,
        isCameraTargetAwayFromVehicle: (CameraPosition) -> Boolean,
    ): Boolean? {
        if (!isGestureInProgress) return null

        val shouldKeepFollowing = shouldKeepFollowingAfterGesture(
            cameraPosition = cameraPosition,
            isCameraTargetAwayFromVehicle = isCameraTargetAwayFromVehicle,
        )
        clear()

        return shouldKeepFollowing
    }

    /**
     * gesture 終了後も自車追従を維持できるかを返す。
     *
     * @param cameraPosition gesture 終了時点の camera position
     * @param isCameraTargetAwayFromVehicle camera target が自車追従 target から離れているかを返す関数
     * @return 自車追従を維持できる場合 true
     */
    private fun shouldKeepFollowingAfterGesture(
        cameraPosition: CameraPosition,
        isCameraTargetAwayFromVehicle: (CameraPosition) -> Boolean,
    ): Boolean {
        val startPosition = gestureStartCameraPosition
        if (!wasFollowingBeforeGesture || startPosition == null) return false

        if (startPosition.zoom != cameraPosition.zoom) return false

        val bearingDeltaDegrees = MapGeodesy.angleDistanceDegrees(
            from = startPosition.bearing,
            to = cameraPosition.bearing,
        )
        if (bearingDeltaDegrees > CAMERA_GESTURE_BEARING_TOLERANCE_DEGREES) {
            return false
        }

        val tiltDeltaDegrees = abs(startPosition.tilt - cameraPosition.tilt)
        if (tiltDeltaDegrees > CAMERA_GESTURE_TILT_TOLERANCE_DEGREES) {
            return false
        }

        return !isCameraTargetAwayFromVehicle(cameraPosition)
    }

    /**
     * gesture の一時状態を破棄する。
     */
    fun clear() {
        isGestureInProgress = false
        wasFollowingBeforeGesture = false
        gestureStartCameraPosition = null
    }

    private companion object {

        /** Gesture による bearing 変更として扱う最小角度。 */
        const val CAMERA_GESTURE_BEARING_TOLERANCE_DEGREES = 1f

        /** Gesture による tilt 変更として扱う最小角度。 */
        const val CAMERA_GESTURE_TILT_TOLERANCE_DEGREES = 1f
    }
}
