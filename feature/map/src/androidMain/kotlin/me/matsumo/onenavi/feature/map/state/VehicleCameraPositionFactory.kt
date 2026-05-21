package me.matsumo.onenavi.feature.map.state

import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import me.matsumo.onenavi.core.model.RoutePoint
import kotlin.math.cos
import kotlin.math.pow

/**
 * 自車追従カメラ用の [CameraPosition] を作る factory。
 */
internal class VehicleCameraPositionFactory {

    private var viewportHeightPx: Int = 0
    private var density: Float = DEFAULT_DENSITY
    private var isGuidanceCameraActive: Boolean = false

    /**
     * 地図ビューの高さを更新する。
     *
     * @param heightPx 地図ビューの高さ（px）
     */
    fun updateViewportHeight(heightPx: Int) {
        viewportHeightPx = heightPx
    }

    /**
     * 画面密度を更新する。
     *
     * @param density 画面密度
     */
    fun updateDensity(density: Float) {
        this.density = density
    }

    /**
     * 案内中カメラとして扱うかを更新する。
     *
     * @param isActive 案内中カメラとして扱う場合 true
     */
    fun setGuidanceCameraActive(isActive: Boolean) {
        isGuidanceCameraActive = isActive
    }

    /**
     * 自車追従用のカメラ位置を作る。
     *
     * @param vehiclePose frame 時点の自車 pose
     * @param current 現在のカメラ位置
     * @param zoom 設定する zoom 値
     * @param perspective 設定する camera perspective
     * @return perspective と zoom を反映したカメラ位置
     */
    fun vehicleCameraPosition(
        vehiclePose: VehiclePose,
        current: CameraPosition,
        zoom: Float,
        perspective: Int,
    ): CameraPosition = CameraPosition.Builder()
        .target(
            vehicleCameraTarget(
                vehiclePose = vehiclePose,
                zoom = zoom,
                perspective = perspective,
            ),
        )
        .zoom(zoom)
        .bearing(
            vehicleBearingDegrees(
                vehiclePose = vehiclePose,
                current = current,
                perspective = perspective,
            ),
        )
        .tilt(vehicleTiltDegrees(perspective))
        .build()

    /**
     * 自車が画面手前側に表示されるよう、案内中 3D 追従時は進行方向の少し先を camera target にする。
     *
     * @param vehiclePose frame 時点の自車 pose
     * @param zoom camera target 算出に使う zoom 値
     * @param perspective target 算出に使う camera perspective
     * @return GoogleMap に渡す camera target
     */
    fun vehicleCameraTarget(
        vehiclePose: VehiclePose,
        zoom: Float,
        perspective: Int,
    ): LatLng {
        val bearingDegrees = vehiclePose.bearingDegrees
        if (!isGuidanceCameraActive || perspective != GoogleMap.CameraPerspective.TILTED || bearingDegrees == null) {
            return vehiclePose.location.toLatLng()
        }

        val viewportHeightDp = viewportHeightPx.toDouble() / density
        if (viewportHeightDp <= 0.0) {
            return vehiclePose.location.toLatLng()
        }

        val forwardMeters = viewportHeightDp *
            (VEHICLE_SCREEN_ANCHOR_Y_FRACTION - SCREEN_CENTER_Y_FRACTION) *
            metersPerDp(latitude = vehiclePose.location.latitude, zoom = zoom)

        return MapGeodesy.destinationLatLng(
            origin = vehiclePose.location,
            bearingDegrees = bearingDegrees,
            distanceMeters = forwardMeters,
        )
    }

    /**
     * 現在の中心・ズームを維持したまま、指定 perspective の tilt / bearing を反映したカメラ位置を作る。
     *
     * @param current 現在のカメラ位置
     * @param perspective 切り替え後の camera perspective
     * @return SDK に渡す camera position
     */
    fun compassCameraPosition(
        current: CameraPosition,
        perspective: Int,
    ): CameraPosition = CameraPosition.Builder()
        .target(current.target)
        .zoom(current.zoom)
        .bearing(compassBearingDegrees(current = current, perspective = perspective))
        .tilt(vehicleTiltDegrees(perspective))
        .build()

    /**
     * follow 中 gesture 後に追従維持を許容する camera target の距離閾値を返す。
     *
     * @param latitude 判定地点の緯度
     * @param zoom GoogleMap の zoom
     * @return viewport 高さに比例した距離閾値（m）
     */
    private fun followGestureTargetToleranceMeters(latitude: Double, zoom: Float): Double {
        val viewportHeightDp = viewportHeightPx.toDouble() / density
        if (viewportHeightDp <= 0.0) return 0.0

        return viewportHeightDp *
            FOLLOW_GESTURE_TARGET_TOLERANCE_FRACTION *
            metersPerDp(latitude = latitude, zoom = zoom)
    }

    /**
     * camera target が自車追従時の target から離れているかを返す。
     *
     * @param cameraPosition 判定対象の camera position
     * @param vehiclePose frame 時点の自車 pose
     * @param perspective camera target 算出に使う perspective
     * @return 追従解除相当まで離れている場合 true
     */
    fun isCameraTargetAwayFromVehicle(
        cameraPosition: CameraPosition,
        vehiclePose: VehiclePose,
        perspective: Int,
    ): Boolean {
        val expectedTarget = vehicleCameraTarget(
            vehiclePose = vehiclePose,
            zoom = cameraPosition.zoom,
            perspective = perspective,
        )
        val distanceMeters = MapGeodesy.haversineMeters(
            from = expectedTarget,
            to = cameraPosition.target,
        )
        val toleranceMeters = followGestureTargetToleranceMeters(
            latitude = expectedTarget.latitude,
            zoom = cameraPosition.zoom,
        )

        return toleranceMeters > 0.0 && distanceMeters > toleranceMeters
    }

    /**
     * 現在の perspective に応じた自車追従カメラの tilt を返す。
     *
     * @param perspective camera perspective
     * @return 次に設定する camera tilt
     */
    fun vehicleTiltDegrees(perspective: Int): Float = when (perspective) {
        GoogleMap.CameraPerspective.TILTED -> VEHICLE_TILTED_CAMERA_DEGREES
        else -> 0f
    }

    /**
     * 現在の perspective に応じた自車追従カメラの bearing を返す。
     *
     * @param vehiclePose frame 時点の自車 pose
     * @param current 現在のカメラ位置
     * @param perspective camera perspective
     * @return 次に設定する camera bearing
     */
    private fun vehicleBearingDegrees(
        vehiclePose: VehiclePose,
        current: CameraPosition,
        perspective: Int,
    ): Float = when (perspective) {
        GoogleMap.CameraPerspective.TOP_DOWN_NORTH_UP -> 0f
        else -> vehiclePose.bearingDegrees ?: current.bearing
    }

    /**
     * コンパス操作時のカメラ bearing を返す。
     *
     * TOP_DOWN_NORTH_UP では 0 度へ戻し、TILTED では現在の heading を維持する。
     *
     * @param current 現在のカメラ位置
     * @param perspective 切り替え後の camera perspective
     * @return 次に設定する camera bearing
     */
    private fun compassBearingDegrees(
        current: CameraPosition,
        perspective: Int,
    ): Float = when (perspective) {
        GoogleMap.CameraPerspective.TOP_DOWN_NORTH_UP -> 0f
        else -> current.bearing
    }

    /**
     * 指定 latitude / zoom での 1dp あたり地上距離を返す。
     *
     * @param latitude 緯度
     * @param zoom GoogleMap の zoom
     * @return 1dp が表す地上距離（m）
     */
    private fun metersPerDp(latitude: Double, zoom: Float): Double {
        val latitudeRadians = Math.toRadians(latitude)
        return cos(latitudeRadians) *
            EARTH_CIRCUMFERENCE_METERS /
            (WORLD_TILE_SIZE_DP * 2.0.pow(zoom.toDouble()))
    }

    /**
     * 車両 pose の座標を GoogleMap の座標型へ変換する。
     *
     * @return GoogleMap に渡す座標
     */
    private fun RoutePoint.toLatLng(): LatLng = LatLng(latitude, longitude)

    private companion object {

        /** density が未通知の間に使う既定値。 */
        const val DEFAULT_DENSITY = 1f

        /** 3D 表示時のカメラ tilt。 */
        const val VEHICLE_TILTED_CAMERA_DEGREES = 45f

        /** 画面中心の縦位置を 0〜1 で表した値。 */
        const val SCREEN_CENTER_Y_FRACTION = 0.5

        /** 3D 追従時に自車を置きたい画面上の縦位置。 */
        const val VEHICLE_SCREEN_ANCHOR_Y_FRACTION = 0.65

        /** follow 中の gesture 後に追従維持を許容する viewport 高さ比。 */
        const val FOLLOW_GESTURE_TARGET_TOLERANCE_FRACTION = 0.12

        /** Web Mercator zoom 0 の tile size。 */
        const val WORLD_TILE_SIZE_DP = 256.0

        /** Web Mercator の地表解像度計算に使う地球円周（m）。 */
        const val EARTH_CIRCUMFERENCE_METERS = 40_030_228.884
    }
}
