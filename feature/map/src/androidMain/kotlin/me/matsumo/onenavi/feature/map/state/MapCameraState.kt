package me.matsumo.onenavi.feature.map.state

import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.core.animation.doOnEnd
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import me.matsumo.onenavi.core.model.RoutePoint
import me.matsumo.onenavi.feature.map.camera.VanWijkZoomPath
import me.matsumo.onenavi.feature.map.camera.WebMercatorProjection
import me.matsumo.onenavi.feature.map.state.MapCameraState.Companion.CAMERA_DECELERATE_FACTOR
import me.matsumo.onenavi.feature.map.state.MapCameraState.Companion.CAMERA_PAN_DURATION_MS
import me.matsumo.onenavi.feature.map.state.MapCameraState.Companion.CAMERA_ROUTE_OVERVIEW_ZOOM_DECELERATE_FACTOR
import me.matsumo.onenavi.feature.map.state.MapCameraState.Companion.CAMERA_ZOOM_DURATION_MS
import me.matsumo.onenavi.feature.map.state.MapCameraState.Companion.MAX_FLY_TO_DURATION_MS
import me.matsumo.onenavi.feature.map.state.MapCameraState.Companion.MIN_FLY_TO_DURATION_MS
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

/**
 * GoogleMap 用のカメラ状態 holder を Compose 上で保持する。
 *
 * @return remember 済みの [MapCameraState]
 */
@Composable
internal fun rememberMapCameraState(): MapCameraState {
    val density = LocalDensity.current.density
    val state = remember { MapCameraState() }

    SideEffect {
        state.updateDensity(density)
    }

    return state
}

/**
 * GoogleMap のカメラ操作と UI 表示用カメラ状態を仲介する state holder。
 */
@Stable
internal class MapCameraState internal constructor() {

    private var googleMap: GoogleMap? = null
    private var cameraAnimator: ValueAnimator? = null
    private var mapViewWidthPx: Int = 0
    private var mapViewHeightPx: Int = 0
    private var density: Float = DEFAULT_DENSITY
    private var lastVehiclePose: VehiclePose? = null
    private var isGuidanceCameraActive: Boolean = false
    private var isUserGestureInProgress: Boolean = false
    private var wasFollowingBeforeUserGesture: Boolean = false
    private var userGestureStartCameraPosition: CameraPosition? = null

    var cameraState by mutableStateOf(HomeMapCameraState())
        private set

    /**
     * 操作対象の GoogleMap を接続し、カメラ移動 listener を登録する。
     *
     * @param googleMap 接続する GoogleMap
     */
    fun attachMap(googleMap: GoogleMap) {
        this.googleMap = googleMap

        googleMap.setOnCameraMoveStartedListener { reason ->
            val isGesture = reason == GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE
            if (isGesture) {
                cameraAnimator?.cancel()
                isUserGestureInProgress = true
                wasFollowingBeforeUserGesture = cameraState.isFollowingMyLocation
                userGestureStartCameraPosition = googleMap.cameraPosition
            }
        }
        googleMap.setOnCameraMoveListener {
            updateCameraPosition(googleMap.cameraPosition)
        }
        googleMap.setOnCameraIdleListener {
            updateCameraPosition(googleMap.cameraPosition)
            finishUserGestureIfNeeded(googleMap.cameraPosition)
        }
    }

    /**
     * 地図ビューの実ピクセルサイズを記録する。
     *
     * @param widthPx 地図ビューの幅（px）
     * @param heightPx 地図ビューの高さ（px）
     */
    fun updateViewportSize(widthPx: Int, heightPx: Int) {
        mapViewWidthPx = widthPx
        mapViewHeightPx = heightPx
    }

    /** 画面密度を記録する。GoogleMap の zoom は dp 基準なので fly-to で px → dp 換算に使う。 */
    fun updateDensity(density: Float) {
        this.density = density
    }

    /**
     * GoogleMap の描画 padding を更新する。
     *
     * @param top 上端 padding（px）
     * @param bottom 下端 padding（px）
     * @param start 左端 padding（px）
     * @param end 右端 padding（px）
     */
    fun updatePadding(top: Int, bottom: Int, start: Int, end: Int) {
        googleMap?.setPadding(start, top, end, bottom)
    }

    /**
     * 案内中カメラとして扱うかを更新する。
     *
     * 案内中だけ 3D 追従時の自車表示位置を画面手前側へ寄せる。通常時は padding を考慮した
     * GoogleMap の camera target 中心に自車位置を置く。
     *
     * @param isActive 案内中カメラとして扱う場合 true
     */
    fun setGuidanceCameraActive(isActive: Boolean) {
        isGuidanceCameraActive = isActive
    }

    /**
     * 自車位置の追従を開始する。
     *
     * @param vehicleLocationState 追従開始時に寄せる自車位置。未取得の場合は次の pose 更新を待つ
     */
    fun followVehicleLocation(vehicleLocationState: VehicleLocationState?) {
        cameraAnimator?.cancel()
        cameraState = cameraState.copy(isFollowingMyLocation = true)
        if (vehicleLocationState != null) {
            flyCameraToVehiclePose(vehicleLocationState.toVehiclePose())
        }
    }

    /**
     * 画面表示用 pose を反映する。
     *
     * 追従中で明示的なカメラアニメーションが走っていない場合は、同じ pose へカメラ中心も追従させる。
     *
     * @param vehiclePose frame 時点の自車 pose
     */
    fun updateVehiclePose(vehiclePose: VehiclePose) {
        lastVehiclePose = vehiclePose
        cameraState = cameraState.copy(
            myLocationLatitude = vehiclePose.location.latitude,
            myLocationLongitude = vehiclePose.location.longitude,
        )

        if (cameraState.isFollowingMyLocation && !isCameraTransitionInProgress() && !isUserGestureInProgress) {
            val current = googleMap?.cameraPosition ?: return
            moveVehicleCamera(vehicleCameraPosition(vehiclePose = vehiclePose, current = current))
        }
    }

    /**
     * コンパス button の perspective を 3D heading-up と 2D north-up で切り替える。
     *
     * クリック後は自車追従へ復帰する。自車 pose が取得済みならその heading を使い、未取得なら
     * 現在の camera target のまま perspective だけを切り替える。切り替えは [flyCameraTo] と同じ
     * 減衰補間で tilt / bearing をアニメーションさせる。
     */
    fun toggleCompassPerspective() {
        val nextPerspective = if (cameraState.perspective == GoogleMap.CameraPerspective.TILTED) {
            GoogleMap.CameraPerspective.TOP_DOWN_NORTH_UP
        } else {
            GoogleMap.CameraPerspective.TILTED
        }

        animateCompassPerspective(nextPerspective)
    }

    /**
     * カメラを 1 段階拡大する。
     */
    fun zoomIn() {
        changeZoom((cameraState.zoom + 1f).coerceIn(MIN_ZOOM, MAX_ZOOM))
    }

    /**
     * カメラを 1 段階縮小する。
     */
    fun zoomOut() {
        changeZoom((cameraState.zoom - 1f).coerceIn(MIN_ZOOM, MAX_ZOOM))
    }

    /**
     * 指定位置へカメラを移動する。
     *
     * @param latitude 移動先緯度
     * @param longitude 移動先経度
     * @param zoom 移動後ズーム値
     */
    fun moveTo(latitude: Double, longitude: Double, zoom: Float = cameraState.zoom) {
        flyCameraTo(
            target = CameraPosition.Builder()
                .target(LatLng(latitude, longitude))
                .zoom(zoom)
                .bearing(0f)
                .tilt(0f)
                .build(),
        )
    }

    /**
     * ルート全体が画面に収まるようにカメラを移動させる。
     *
     * bounds にフィットする [CameraPosition] を一旦 `moveCamera` で算出してから元に戻し、
     * その目標位置へ [flyCameraTo] で van Wijk–Nuij 経路で寄せる。「現在地 → 引いて全体」という
     * 自然なシネマティック移動になり、`updatePadding` で設定済みの地図パディング
     * （トップバー / ボトムシート分）も尊重される。ルート全体表示は引き量が大きく、弧長へ減速を
     * かけるだけだと終端の減速が知覚されにくいため、ズームレベルへ直接かける強めの減速
     * （[CAMERA_ROUTE_OVERVIEW_ZOOM_DECELERATE_FACTOR]）を [flyCameraTo] に渡す。
     * 地図ビューのサイズが未確定の場合は単純補間（[animateCameraTo]）にフォールバックする。
     *
     * @param routePoints フィット対象の座標列（全候補ルートをまとめて渡してよい）
     * @param paddingPx 画面端とルートの間に確保する余白（px）
     * @param durationMs アニメーション時間（ms）。null なら経路長から自動算出
     */
    fun showRouteOverview(
        routePoints: List<RoutePoint>,
        paddingPx: Int = ROUTE_OVERVIEW_PADDING_PX,
        durationMs: Long? = 1500,
    ) {
        val map = googleMap ?: return
        if (routePoints.isEmpty()) return

        val bounds = LatLngBounds.builder()
            .apply { for (point in routePoints) include(LatLng(point.latitude, point.longitude)) }
            .build()

        val current = map.cameraPosition
        val target = runCatching {
            map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, paddingPx))
            map.cameraPosition
        }.getOrNull()

        map.moveCamera(CameraUpdateFactory.newCameraPosition(current))

        if (target != null) {
            flyCameraTo(
                target = target,
                durationMs = durationMs,
                zoomEasing = DecelerateInterpolator(CAMERA_ROUTE_OVERVIEW_ZOOM_DECELERATE_FACTOR),
            )
        }
    }

    /**
     * GoogleMap から通知された現在カメラ位置を UI state に反映する。
     *
     * @param cameraPosition GoogleMap の現在カメラ位置
     */
    private fun updateCameraPosition(cameraPosition: CameraPosition) {
        cameraState = cameraState.copy(
            latitude = cameraPosition.target.latitude,
            longitude = cameraPosition.target.longitude,
            bearing = cameraPosition.bearing.toDouble(),
            zoom = cameraPosition.zoom,
            isFollowingMyLocation = cameraState.isFollowingMyLocation,
        )
    }

    /**
     * 現在の中心を保ったまま指定ズームへ移動する。
     *
     * @param targetZoom 移動先ズーム値
     */
    private fun changeZoom(targetZoom: Float) {
        val map = googleMap ?: return
        val current = map.cameraPosition
        val followPose = lastVehiclePose.takeIf { cameraState.isFollowingMyLocation }

        if (followPose != null) {
            animateFollowZoomTo(targetZoom = targetZoom)
            return
        }

        val target = CameraPosition.Builder()
            .target(current.target)
            .zoom(targetZoom)
            .bearing(current.bearing)
            .tilt(current.tilt)
            .build()

        animateCameraTo(
            target = target,
        )
    }

    /**
     * follow 中の zoom button 操作を、自車追従 target を更新しながらアニメーションする。
     *
     * zoom 中も [lastVehiclePose] を毎 frame 読み直すことで、終了時に現在地へ瞬間移動しないようにする。
     *
     * @param targetZoom 移動先 zoom 値
     */
    private fun animateFollowZoomTo(targetZoom: Float) {
        val map = googleMap ?: return
        val start = map.cameraPosition
        val totalDurationMs = cameraZoomDurationMs(zoomDelta = abs(targetZoom - start.zoom))
        val easing = DecelerateInterpolator(CAMERA_DECELERATE_FACTOR)

        clearUserGesture()
        cameraState = cameraState.copy(isFollowingMyLocation = true)
        cameraAnimator?.cancel()

        cameraAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = totalDurationMs
            interpolator = LinearInterpolator()

            addUpdateListener { anim ->
                val fraction = easing.getInterpolation(anim.animatedValue as Float)
                val zoom = lerp(start.zoom, targetZoom, fraction)
                val current = map.cameraPosition
                val vehiclePose = lastVehiclePose

                val cameraPosition = if (vehiclePose == null) {
                    CameraPosition.Builder()
                        .target(current.target)
                        .zoom(zoom)
                        .bearing(current.bearing)
                        .tilt(current.tilt)
                        .build()
                } else {
                    vehicleCameraPosition(
                        vehiclePose = vehiclePose,
                        current = current,
                        zoom = zoom,
                    )
                }

                moveVehicleCamera(cameraPosition)
            }
            doOnEnd {
                if (cameraAnimator == this) {
                    cameraAnimator = null
                }
                cameraState = cameraState.copy(isFollowingMyLocation = true)
            }
            start()
        }
    }

    /**
     * user gesture 終了時に自車追従を維持するか判定する。
     *
     * zoom gesture は指の位置を中心に zoom されるため追従を解除する。rotate / tilt gesture も
     * ユーザーが見たい向きを明示した操作なので追従を解除する。pan gesture は map target が
     * 自車追従 target から離れた場合だけ追従を解除する。
     *
     * @param cameraPosition gesture 終了時点の camera position
     */
    private fun finishUserGestureIfNeeded(cameraPosition: CameraPosition) {
        if (!isUserGestureInProgress) return

        val hasZoomChanged = userGestureStartCameraPosition
            ?.let { start -> start.zoom != cameraPosition.zoom }
            ?: false
        val hasBearingChanged = userGestureStartCameraPosition
            ?.let { start ->
                angleDistanceDegrees(
                    from = start.bearing,
                    to = cameraPosition.bearing,
                ) > CAMERA_GESTURE_BEARING_TOLERANCE_DEGREES
            }
            ?: false
        val hasTiltChanged = userGestureStartCameraPosition
            ?.let { start ->
                abs(start.tilt - cameraPosition.tilt) > CAMERA_GESTURE_TILT_TOLERANCE_DEGREES
            }
            ?: false
        val shouldKeepFollowing = wasFollowingBeforeUserGesture &&
            !hasZoomChanged &&
            !hasBearingChanged &&
            !hasTiltChanged &&
            !isCameraTargetAwayFromVehicle(cameraPosition)

        cameraState = cameraState.copy(isFollowingMyLocation = shouldKeepFollowing)
        clearUserGesture()
    }

    /**
     * user gesture の一時状態を破棄する。
     */
    private fun clearUserGesture() {
        isUserGestureInProgress = false
        wasFollowingBeforeUserGesture = false
        userGestureStartCameraPosition = null
    }

    /**
     * 自前 animator が動作中かを返す。
     *
     * @return カメラ遷移中で自車追従の即時 moveCamera を止めるべきなら true
     */
    private fun isCameraTransitionInProgress(): Boolean = cameraAnimator != null

    /**
     * コンパス button の perspective 変更を fly-to と同じ減衰補間で実行する。
     *
     * @param perspective 切り替え後の [GoogleMap.CameraPerspective]
     */
    private fun animateCompassPerspective(perspective: Int) {
        val map = googleMap ?: return
        val current = map.cameraPosition
        val vehiclePose = lastVehiclePose
        val target = if (vehiclePose == null) {
            compassCameraPosition(
                current = current,
                perspective = perspective,
            )
        } else {
            vehicleCameraPosition(
                vehiclePose = vehiclePose,
                current = current,
                perspective = perspective,
            )
        }

        cameraAnimator?.cancel()
        clearUserGesture()
        map.stopAnimation()
        cameraState = cameraState.copy(
            perspective = perspective,
            isFollowingMyLocation = true,
        )

        flyCameraTo(
            target = target,
            durationMs = COMPASS_PERSPECTIVE_ANIMATION_DURATION_MS,
            keepFollowingMyLocation = true,
            moveCamera = { _, cameraPosition -> moveVehicleCamera(cameraPosition) },
            onFinished = {
                updateCameraPosition(map.cameraPosition)
                cameraState = cameraState.copy(
                    perspective = perspective,
                    isFollowingMyLocation = true,
                )
            },
        )
    }

    /**
     * 追従開始時の自車位置へ既存の fly-to 経路で寄せる。
     *
     * @param vehiclePose 寄せ先の自車 pose
     */
    private fun flyCameraToVehiclePose(vehiclePose: VehiclePose) {
        val current = googleMap?.cameraPosition ?: return

        flyCameraTo(
            target = vehicleCameraPosition(vehiclePose = vehiclePose, current = current),
            keepFollowingMyLocation = true,
            moveCamera = { _, cameraPosition -> moveVehicleCamera(cameraPosition) },
        )
    }

    /**
     * 自車追従用のカメラ位置を作る。
     *
     * @param vehiclePose frame 時点の自車 pose
     * @param current 現在のカメラ位置
     * @param zoom 設定する zoom 値
     * @return perspective と現在ズームを反映したカメラ位置
     */
    private fun vehicleCameraPosition(
        vehiclePose: VehiclePose,
        current: CameraPosition,
        zoom: Float = cameraState.zoom,
        perspective: Int = cameraState.perspective,
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
     * 自車が画面手前側に表示されるよう、3D 追従時は進行方向の少し先を camera target にする。
     *
     * @param vehiclePose frame 時点の自車 pose
     * @param zoom camera target 算出に使う zoom 値
     * @return GoogleMap に渡す camera target
     */
    private fun vehicleCameraTarget(vehiclePose: VehiclePose, zoom: Float = cameraState.zoom): LatLng {
        return vehicleCameraTarget(
            vehiclePose = vehiclePose,
            zoom = zoom,
            perspective = cameraState.perspective,
        )
    }

    /**
     * 自車が画面手前側に表示されるよう、3D 追従時は進行方向の少し先を camera target にする。
     *
     * @param vehiclePose frame 時点の自車 pose
     * @param zoom camera target 算出に使う zoom 値
     * @param perspective target 算出に使う camera perspective
     * @return GoogleMap に渡す camera target
     */
    private fun vehicleCameraTarget(
        vehiclePose: VehiclePose,
        zoom: Float,
        perspective: Int,
    ): LatLng {
        val bearingDegrees = vehiclePose.bearingDegrees
        if (!isGuidanceCameraActive || perspective != GoogleMap.CameraPerspective.TILTED || bearingDegrees == null) {
            return LatLng(
                vehiclePose.location.latitude,
                vehiclePose.location.longitude,
            )
        }

        val viewportHeightDp = mapViewHeightPx.toDouble() / density
        if (viewportHeightDp <= 0.0) {
            return LatLng(
                vehiclePose.location.latitude,
                vehiclePose.location.longitude,
            )
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
    private fun compassCameraPosition(
        current: CameraPosition,
        perspective: Int,
    ): CameraPosition = CameraPosition.Builder()
        .target(current.target)
        .zoom(current.zoom)
        .bearing(compassBearingDegrees(current = current, perspective = perspective))
        .tilt(vehicleTiltDegrees(perspective))
        .build()

    /**
     * 自車追従用のカメラ移動を即時反映する。
     *
     * @param cameraPosition 次に表示するカメラ位置
     */
    private fun moveVehicleCamera(cameraPosition: CameraPosition) {
        val map = googleMap ?: return
        map.moveCamera(CameraUpdateFactory.newCameraPosition(cameraPosition))
    }

    /**
     * camera target が自車追従時の target から離れているかを返す。
     *
     * @param cameraPosition 判定対象の camera position
     * @return 追従解除相当まで離れている場合 true
     */
    private fun isCameraTargetAwayFromVehicle(cameraPosition: CameraPosition): Boolean {
        val vehiclePose = lastVehiclePose ?: return false
        val expectedTarget = vehicleCameraTarget(vehiclePose)
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
     * 現在の perspective に応じた自車追従カメラの bearing を返す。
     *
     * @param vehiclePose frame 時点の自車 pose
     * @param current 現在のカメラ位置
     * @return 次に設定する camera bearing
     */
    private fun vehicleBearingDegrees(
        vehiclePose: VehiclePose,
        current: CameraPosition,
        perspective: Int = cameraState.perspective,
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
     * 現在の perspective に応じた自車追従カメラの tilt を返す。
     *
     * @return 次に設定する camera tilt
     */
    private fun vehicleTiltDegrees(perspective: Int = cameraState.perspective): Float = when (perspective) {
        GoogleMap.CameraPerspective.TILTED -> VEHICLE_TILTED_CAMERA_DEGREES
        else -> 0f
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
     * follow 中 gesture 後に追従維持を許容する camera target の距離閾値を返す。
     *
     * @param latitude 判定地点の緯度
     * @param zoom GoogleMap の zoom
     * @return viewport 高さに比例した距離閾値（m）
     */
    private fun followGestureTargetToleranceMeters(latitude: Double, zoom: Float): Double {
        val viewportHeightDp = mapViewHeightPx.toDouble() / density
        if (viewportHeightDp <= 0.0) return 0.0

        return viewportHeightDp *
            FOLLOW_GESTURE_TARGET_TOLERANCE_FRACTION *
            metersPerDp(latitude = latitude, zoom = zoom)
    }

    /**
     * カメラを [target] へ van Wijk–Nuij "Smooth and efficient zooming and panning" の経路で移動させる。
     * 始点と終点が遠ければ途中で一旦ズームアウトしてから寄り直す弧を描き、近ければ弧が消えてただのイージングになる。
     * bearing / tilt は経路とは別チャンネルで線形補間する。地図ビューのサイズが未確定（レイアウト前）なら
     * 単純補間の [animateCameraTo] にフォールバックする。
     *
     * @param durationMs アニメーション時間（ms）。null なら経路長から自動算出し、
     *   [MIN_FLY_TO_DURATION_MS]〜[MAX_FLY_TO_DURATION_MS] にクランプする
     * @param zoomEasing 指定すると、ズーム（= log ビューポート幅）を弧長から切り離し、生のアニメ進捗に
     *   このイージングをかけて log 空間で補間する。引き量が大きいときに終端の減速をはっきり効かせるための逃げ道。
     *   null なら従来どおりズームも弧長パラメータから導出する（中心パンと完全に結合）。
     */
    private fun flyCameraTo(
        target: CameraPosition,
        durationMs: Long? = null,
        zoomEasing: TimeInterpolator? = null,
        keepFollowingMyLocation: Boolean = false,
        moveCamera: (GoogleMap, CameraPosition) -> Unit = { map, cameraPosition ->
            map.moveCamera(CameraUpdateFactory.newCameraPosition(cameraPosition))
        },
        onStarted: () -> Unit = {},
        onFinished: () -> Unit = {},
    ) {
        val map = googleMap ?: return
        val viewportWidthDp = mapViewWidthPx.toDouble() / density
        if (viewportWidthDp <= 0.0) {
            animateCameraTo(
                target = target,
                panDurationMs = durationMs,
                zoomDurationMs = durationMs,
                keepFollowingMyLocation = keepFollowingMyLocation,
                moveCamera = moveCamera,
                onStarted = onStarted,
                onFinished = onFinished,
            )
            return
        }

        val start = map.cameraPosition
        val startViewport = flyToViewport(viewportWidthDp, start.target, start.zoom)
        val endViewport = flyToViewport(viewportWidthDp, target.target, target.zoom)

        val isSamePose = startViewport == endViewport &&
            start.bearing == target.bearing &&
            start.tilt == target.tilt
        if (isSamePose) {
            onFinished()
            return
        }

        val path = VanWijkZoomPath.of(startViewport, endViewport, rho = CAMERA_FLY_TO_RHO)
        val totalDurationMs = durationMs
            ?: (path.naturalDurationMs() * CAMERA_FLY_TO_SPEED_SCALE)
                .toLong()
                .coerceIn(MIN_FLY_TO_DURATION_MS, MAX_FLY_TO_DURATION_MS)
        val easing = DecelerateInterpolator(CAMERA_DECELERATE_FACTOR)

        if (!keepFollowingMyLocation) {
            cameraState = cameraState.copy(isFollowingMyLocation = false)
        }
        cameraAnimator?.cancel()
        onStarted()

        cameraAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = totalDurationMs
            interpolator = LinearInterpolator()

            addUpdateListener { anim ->
                val rawFraction = anim.animatedValue as Float
                val arcFraction = easing.getInterpolation(rawFraction)
                val viewport = path.at(arcFraction.toDouble())

                val widthWorldPx = if (zoomEasing == null) {
                    viewport.viewportWidthWorldPx
                } else {
                    val zoomFraction = zoomEasing.getInterpolation(rawFraction).toDouble()
                    val logStartWidth = ln(startViewport.viewportWidthWorldPx)
                    val logEndWidth = ln(endViewport.viewportWidthWorldPx)
                    exp(logStartWidth + (logEndWidth - logStartWidth) * zoomFraction)
                }
                val zoom = (ln(viewportWidthDp / widthWorldPx) / ln(2.0)).toFloat()
                    .coerceIn(MIN_ZOOM, MAX_ZOOM)

                moveCamera(
                    map,
                    CameraPosition.Builder()
                        .target(
                            LatLng(
                                WebMercatorProjection.worldYToLatitude(viewport.worldY),
                                WebMercatorProjection.worldXToLongitude(viewport.worldX),
                            ),
                        )
                        .zoom(zoom)
                        .bearing(lerpAngle(start.bearing, target.bearing, arcFraction))
                        .tilt(lerp(start.tilt, target.tilt, arcFraction))
                        .build(),
                )
            }
            doOnEnd {
                if (cameraAnimator == this) {
                    cameraAnimator = null
                }
                onFinished()
            }
            start()
        }
    }

    /** GoogleMap のカメラ位置を van Wijk–Nuij 経路用のズーム 0 ワールドピクセル座標へ変換する。 */
    private fun flyToViewport(
        viewportWidthDp: Double,
        center: LatLng,
        zoom: Float,
    ): VanWijkZoomPath.Viewport = VanWijkZoomPath.Viewport(
        worldX = WebMercatorProjection.longitudeToWorldX(center.longitude),
        worldY = WebMercatorProjection.latitudeToWorldY(center.latitude),
        viewportWidthWorldPx = viewportWidthDp / 2.0.pow(zoom.toDouble()),
    )

    /**
     * カメラを [target] へアニメーション移動させる。pan（位置・向き・傾き）と zoom は
     * それぞれ独立した duration を持ち、短い方が先に到達して止まる（fraction を頭打ちにする）。
     * animator 本体は両者の長い方の長さで線形に回し、各チャンネルへ [DecelerateInterpolator] を個別に適用する。
     * 現在は [flyCameraTo] が使えない（地図ビューのサイズが未確定など）ときの単純補間フォールバックとして残してある。
     *
     * @param panDurationMs pan の所要時間（ms）。null なら [CAMERA_PAN_DURATION_MS]
     * @param zoomDurationMs zoom の所要時間（ms）。null ならズーム差から自動算出
     */
    private fun animateCameraTo(
        target: CameraPosition,
        panDurationMs: Long? = null,
        zoomDurationMs: Long? = null,
        keepFollowingMyLocation: Boolean = false,
        moveCamera: (GoogleMap, CameraPosition) -> Unit = { map, cameraPosition ->
            map.moveCamera(CameraUpdateFactory.newCameraPosition(cameraPosition))
        },
        onStarted: () -> Unit = {},
        onFinished: () -> Unit = {},
    ) {
        val map = googleMap ?: return
        val start = map.cameraPosition

        val resolvedPanDurationMs = panDurationMs ?: CAMERA_PAN_DURATION_MS
        val resolvedZoomDurationMs = zoomDurationMs ?: cameraZoomDurationMs(zoomDelta = abs(target.zoom - start.zoom))
        val totalDurationMs = max(resolvedPanDurationMs, resolvedZoomDurationMs)
        val easing = DecelerateInterpolator(CAMERA_DECELERATE_FACTOR)

        if (!keepFollowingMyLocation) {
            cameraState = cameraState.copy(isFollowingMyLocation = false)
        }
        cameraAnimator?.cancel()
        onStarted()

        cameraAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = totalDurationMs
            interpolator = LinearInterpolator()

            addUpdateListener { anim ->
                val elapsedMs = (anim.animatedValue as Float) * totalDurationMs
                val panFraction = easing.getInterpolation((elapsedMs / resolvedPanDurationMs).coerceAtMost(1f))
                val zoomFraction = easing.getInterpolation((elapsedMs / resolvedZoomDurationMs).coerceAtMost(1f))

                val lat = lerp(start.target.latitude, target.target.latitude, panFraction)
                val lng = lerp(start.target.longitude, target.target.longitude, panFraction)
                val zoom = lerp(start.zoom, target.zoom, zoomFraction)
                val bearing = lerpAngle(start.bearing, target.bearing, panFraction)
                val tilt = lerp(start.tilt, target.tilt, panFraction)

                moveCamera(
                    map,
                    CameraPosition.Builder()
                        .target(LatLng(lat, lng))
                        .zoom(zoom)
                        .bearing(bearing)
                        .tilt(tilt)
                        .build(),
                )
            }
            doOnEnd {
                if (cameraAnimator == this) {
                    cameraAnimator = null
                }
                onFinished()
            }
            start()
        }
    }

    /**
     * tick 単位の自車位置を表示用 pose に変換する。
     *
     * @return tick が持つ位置・向きから作った [VehiclePose]
     */
    private fun VehicleLocationState.toVehiclePose(): VehiclePose = VehiclePose(
        location = location,
        bearingDegrees = bearingDegrees,
    )

    /**
     * 2 つの数値を線形補間する。
     *
     * @param from 開始値
     * @param to 終了値
     * @param fraction 補間率
     * @return 補間後の値
     */
    private fun lerp(from: Double, to: Double, fraction: Float): Double =
        from + (to - from) * fraction

    /**
     * 2 つの数値を線形補間する。
     *
     * @param from 開始値
     * @param to 終了値
     * @param fraction 補間率
     * @return 補間後の値
     */
    private fun lerp(from: Float, to: Float, fraction: Float): Float =
        from + (to - from) * fraction

    /**
     * 360 度境界をまたぐ場合も短い回転方向で方位角を補間する。
     *
     * @param from 開始角度
     * @param to 終了角度
     * @param fraction 補間率
     * @return 0〜360 度に正規化した補間後角度
     */
    private fun lerpAngle(from: Float, to: Float, fraction: Float): Float {
        val diff = ((to - from + 540f) % 360f) - 180f
        return (from + diff * fraction + 360f) % 360f
    }

    /**
     * 2 つの方位角の最短差分を絶対値で返す。
     *
     * @param from 開始角度
     * @param to 終了角度
     * @return 0〜180 度の差分
     */
    private fun angleDistanceDegrees(from: Float, to: Float): Float =
        abs(((to - from + 540f) % 360f) - 180f)

    /** ズーム差に応じて zoom 側のアニメーション時間を [CAMERA_PAN_DURATION_MS]〜[CAMERA_ZOOM_DURATION_MS] の範囲で線形に決める。 */
    private fun cameraZoomDurationMs(zoomDelta: Float): Long {
        val ratio = (zoomDelta / FULL_ZOOM_DELTA).coerceIn(0f, 1f)
        return CAMERA_PAN_DURATION_MS + ((CAMERA_ZOOM_DURATION_MS - CAMERA_PAN_DURATION_MS) * ratio).toLong()
    }

    companion object {
        /** GoogleMap に許容する最小ズーム値。 */
        private const val MIN_ZOOM = 2f

        /** GoogleMap に許容する最大ズーム値。 */
        private const val MAX_ZOOM = 21f

        /** ルート全体表示時に画面端へ確保する既定 padding（px）。 */
        private const val ROUTE_OVERVIEW_PADDING_PX = 64

        /** 通常の pan アニメーション時間（ms）。 */
        private const val CAMERA_PAN_DURATION_MS = 3000L

        /** 最大ズーム差のときに使う zoom アニメーション時間（ms）。 */
        private const val CAMERA_ZOOM_DURATION_MS = 10000L

        /** zoom アニメーション時間を最大にするズーム差。 */
        private const val FULL_ZOOM_DELTA = 10f

        /** camera animation に使う減速補間の強さ。 */
        private const val CAMERA_DECELERATE_FACTOR = 2.5f

        /**
         * [showRouteOverview] の引きアニメで、ズームレベル（= log ビューポート幅）へ直接かける減速の強さ。
         * 引き量が大きく弧長への減速だと終端の減速が知覚されにくいので、[CAMERA_DECELERATE_FACTOR] より強めにする。
         */
        private const val CAMERA_ROUTE_OVERVIEW_ZOOM_DECELERATE_FACTOR = 5f

        /** fly-to の曲率 ρ。大きいほど遠距離移動で大胆にズームアウトする（d3 / Mapbox の既定は約 1.42）。 */
        private const val CAMERA_FLY_TO_RHO = 0.6

        /** fly-to の自然な所要時間に掛ける係数。大きいほどゆっくり動く。 */
        private const val CAMERA_FLY_TO_SPEED_SCALE = 1.0

        /** fly-to の所要時間の下限（ms）。ごく短い移動でも一瞬で飛ばないように。 */
        private const val MIN_FLY_TO_DURATION_MS = 1500L

        /** fly-to の所要時間の上限（ms）。地球の裏側へ飛ぶときに何秒も待たされないように。 */
        private const val MAX_FLY_TO_DURATION_MS = 3000L

        /** density が未通知の間に使う既定値。 */
        private const val DEFAULT_DENSITY = 1f

        /** 3D 表示時のカメラ tilt。 */
        private const val VEHICLE_TILTED_CAMERA_DEGREES = 45f

        /** コンパス button による 3D heading-up / 2D north-up 切り替え animation 時間（ms）。 */
        private const val COMPASS_PERSPECTIVE_ANIMATION_DURATION_MS = 500L

        /** 画面中心の縦位置を 0〜1 で表した値。 */
        private const val SCREEN_CENTER_Y_FRACTION = 0.5

        /** 3D 追従時に自車を置きたい画面上の縦位置。 */
        private const val VEHICLE_SCREEN_ANCHOR_Y_FRACTION = 0.65

        /** follow 中の gesture 後に追従維持を許容する viewport 高さ比。 */
        private const val FOLLOW_GESTURE_TARGET_TOLERANCE_FRACTION = 0.12

        /** gesture による bearing 変更として扱う最小角度。 */
        private const val CAMERA_GESTURE_BEARING_TOLERANCE_DEGREES = 1f

        /** gesture による tilt 変更として扱う最小角度。 */
        private const val CAMERA_GESTURE_TILT_TOLERANCE_DEGREES = 1f

        /** Web Mercator zoom 0 の tile size。 */
        private const val WORLD_TILE_SIZE_DP = 256.0

        /** Web Mercator の地表解像度計算に使う地球円周（m）。 */
        private const val EARTH_CIRCUMFERENCE_METERS = 40_030_228.884
    }
}

/**
 * 地図カメラの現在状態。
 *
 * @param latitude カメラ中心の緯度
 * @param longitude カメラ中心の経度
 * @param myLocationLatitude 自分の位置の緯度
 * @param myLocationLongitude 自分の位置の経度
 * @param zoom 現在のズーム値
 * @param bearing 現在のカメラの向き
 * @param perspective GoogleMap.CameraPerspective
 * @param isFollowingMyLocation マップカメラが現在自分の位置に追従しているかどうか
 */
@Stable
data class HomeMapCameraState(
    val latitude: Double = DEFAULT_LATITUDE,
    val longitude: Double = DEFAULT_LONGITUDE,
    val myLocationLatitude: Double = DEFAULT_LATITUDE,
    val myLocationLongitude: Double = DEFAULT_LONGITUDE,
    val zoom: Float = DEFAULT_CAMERA_ZOOM,
    val bearing: Double = 0.0,
    val perspective: Int = GoogleMap.CameraPerspective.TILTED,
    val isFollowingMyLocation: Boolean = false,
)

/** 初期カメラ位置の緯度。 */
private const val DEFAULT_LATITUDE = 35.681236

/** 初期カメラ位置の経度。 */
private const val DEFAULT_LONGITUDE = 139.767125

/** 初期カメラズーム。 */
private const val DEFAULT_CAMERA_ZOOM = 17f
