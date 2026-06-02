package me.matsumo.onenavi.feature.map.state

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
    private var topPaddingPx: Int = 0
    private var bottomPaddingPx: Int = 0
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
     * 地図の描画 padding を更新する。
     *
     * 自車アンカーは「padding を除いた可視領域の下端から一定 dp 上」で決めるため、
     * 上下 padding を保持しておく。
     *
     * @param topPx 上端 padding（px）
     * @param bottomPx 下端 padding（px）
     */
    fun updatePadding(topPx: Int, bottomPx: Int) {
        topPaddingPx = topPx
        bottomPaddingPx = bottomPx
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
    ): CameraPosition {
        val cameraBearingDegrees = vehicleBearingDegrees(
            vehiclePose = vehiclePose,
            current = current,
            perspective = perspective,
        )

        return CameraPosition.Builder()
            .target(
                vehicleCameraTarget(
                    vehiclePose = vehiclePose,
                    zoom = zoom,
                    perspective = perspective,
                    cameraBearingDegrees = cameraBearingDegrees,
                ),
            )
            .zoom(zoom)
            .bearing(cameraBearingDegrees)
            .tilt(vehicleTiltDegrees(perspective))
            .build()
    }

    /**
     * 自車を camera target 中心（= padding を除いた可視領域の中心）へ置くカメラ位置を作る。
     *
     * 下端アンカーは呼び出し側が `scrollBy` で screen 空間で正確に与えるため、ここでは forward
     * offset を付けない素の中心追従位置を返す。
     *
     * @param vehiclePose frame 時点の自車 pose
     * @param current 現在のカメラ位置
     * @param zoom 設定する zoom 値
     * @param perspective 設定する camera perspective
     * @return 自車を中心に置いたカメラ位置
     */
    fun centeredVehicleCameraPosition(
        vehiclePose: VehiclePose,
        current: CameraPosition,
        zoom: Float,
        perspective: Int,
    ): CameraPosition = CameraPosition.Builder()
        .target(vehiclePose.location.toLatLng())
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
     * 自車を可視領域の下端付近へ固定するため、案内中追従時は camera bearing 方向（画面上方向）の
     * 少し先を camera target にする。
     *
     * camera target は GoogleMap が padding を除いた可視領域の中心へ置くので、その中心から
     * 「下端の [ANCHOR_MARGIN_FROM_BOTTOM_DP] 上」までの差分だけ target を前方へずらすと、
     * 自車が画面下部の一定位置へ来る。アンカーは下端基準なので、上部パネル展開で上 padding が
     * 増えても自車位置は動かない。
     *
     * @param vehiclePose frame 時点の自車 pose
     * @param zoom camera target 算出に使う zoom 値
     * @param perspective target 算出に使う camera perspective
     * @param cameraBearingDegrees 適用する camera bearing（target を前方へずらす方向）
     * @return GoogleMap に渡す camera target
     */
    fun vehicleCameraTarget(
        vehiclePose: VehiclePose,
        zoom: Float,
        perspective: Int,
        cameraBearingDegrees: Float,
    ): LatLng {
        val isFollowPerspective = perspective == MapCameraPerspective.TILTED ||
            perspective == MapCameraPerspective.TOP_DOWN_NORTH_UP
        val isAnchored = isGuidanceCameraActive && isFollowPerspective
        if (!isAnchored) {
            return vehiclePose.location.toLatLng()
        }

        val visibleHeightDp = visibleViewportHeightDp()
        val forwardOffsetDp = (visibleHeightDp / 2.0 - VEHICLE_ANCHOR_MARGIN_FROM_BOTTOM_DP).coerceAtLeast(0.0)
        if (forwardOffsetDp <= 0.0) {
            return vehiclePose.location.toLatLng()
        }

        val forwardMeters = forwardOffsetDp *
            metersPerDp(latitude = vehiclePose.location.latitude, zoom = zoom)

        return MapGeodesy.destinationLatLng(
            origin = vehiclePose.location,
            bearingDegrees = cameraBearingDegrees,
            distanceMeters = forwardMeters,
        )
    }

    /**
     * padding を除いた可視領域の高さ（dp）を返す。算出できない場合は 0。
     *
     * @return 可視領域の高さ（dp）
     */
    private fun visibleViewportHeightDp(): Double {
        val visibleHeightPx = viewportHeightPx - topPaddingPx - bottomPaddingPx
        if (visibleHeightPx <= 0) {
            return 0.0
        }

        return visibleHeightPx.toDouble() / density
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
        val cameraBearingDegrees = vehicleBearingDegrees(
            vehiclePose = vehiclePose,
            current = cameraPosition,
            perspective = perspective,
        )
        val expectedTarget = vehicleCameraTarget(
            vehiclePose = vehiclePose,
            zoom = cameraPosition.zoom,
            perspective = perspective,
            cameraBearingDegrees = cameraBearingDegrees,
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
        MapCameraPerspective.TILTED -> VEHICLE_TILTED_CAMERA_DEGREES
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
        MapCameraPerspective.TOP_DOWN_NORTH_UP -> 0f
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
        MapCameraPerspective.TOP_DOWN_NORTH_UP -> 0f
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

        /** follow 中の gesture 後に追従維持を許容する viewport 高さ比。 */
        const val FOLLOW_GESTURE_TARGET_TOLERANCE_FRACTION = 0.12

        /** Web Mercator zoom 0 の tile size。 */
        const val WORLD_TILE_SIZE_DP = 256.0

        /** Web Mercator の地表解像度計算に使う地球円周（m）。 */
        const val EARTH_CIRCUMFERENCE_METERS = 40_030_228.884
    }
}

/**
 * 案内中追従時に自車を可視領域の下端から何 dp 上へ固定するか。
 * forward offset の算出（[VehicleCameraPositionFactory]）と projection 補正
 * （[MapCameraState]）の両方で同じ値を使う。
 */
internal const val VEHICLE_ANCHOR_MARGIN_FROM_BOTTOM_DP = 16.0
