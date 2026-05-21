package me.matsumo.onenavi.feature.map.state

import android.animation.TimeInterpolator
import android.view.animation.DecelerateInterpolator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import me.matsumo.onenavi.core.model.RoutePoint
import me.matsumo.onenavi.feature.map.state.MapCameraState.Companion.CAMERA_ROUTE_OVERVIEW_ZOOM_DECELERATE_FACTOR

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
    private val cameraAnimator = MapCameraAnimator(
        mapProvider = { googleMap },
        viewportWidthDpProvider = { mapViewWidthPx.toDouble() / density },
    )
    private val vehicleCameraPositionFactory = VehicleCameraPositionFactory()
    private val gestureController = MapCameraGestureController()
    private var mapViewWidthPx: Int = 0
    private var density: Float = DEFAULT_DENSITY
    private var lastVehiclePose: VehiclePose? = null
    private var guidanceManeuverCameraFocus: GuidanceManeuverCameraFocus? = null
    private var handledGuidanceManeuverFocusIndex: Int? = null

    var cameraState by mutableStateOf(MapCameraSnapshot())
        private set

    /** 最後に受け取った自車位置の緯度。未取得の場合は初期緯度を返す。 */
    val myLocationLatitude: Double
        get() = lastVehiclePose?.location?.latitude ?: DEFAULT_LATITUDE

    /** 最後に受け取った自車位置の経度。未取得の場合は初期経度を返す。 */
    val myLocationLongitude: Double
        get() = lastVehiclePose?.location?.longitude ?: DEFAULT_LONGITUDE

    /**
     * 操作対象の GoogleMap を接続し、カメラ移動 listener を登録する。
     *
     * @param googleMap 接続する GoogleMap
     */
    fun attachMap(googleMap: GoogleMap) {
        this.googleMap = googleMap

        googleMap.setOnCameraMoveStartedListener { reason ->
            val isGesture = gestureController.onCameraMoveStarted(
                reason = reason,
                isFollowingMyLocation = cameraState.isFollowingMyLocation,
                cameraPosition = googleMap.cameraPosition,
            )
            if (isGesture) {
                cameraAnimator.cancel()
                cancelGuidanceManeuverFocusByGesture()
            }
        }
        googleMap.setOnCameraMoveListener {
            updateCameraPosition(googleMap.cameraPosition)
        }
        googleMap.setOnCameraIdleListener {
            updateCameraPosition(googleMap.cameraPosition)
            gestureController.finishIfNeeded(
                cameraPosition = googleMap.cameraPosition,
                isCameraTargetAwayFromVehicle = ::isCameraTargetAwayFromVehicle,
            )?.let { shouldKeepFollowing ->
                cameraState = cameraState.copy(isFollowingMyLocation = shouldKeepFollowing)
            }
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
        vehicleCameraPositionFactory.updateViewportHeight(heightPx)
    }

    /** 画面密度を記録する。GoogleMap の zoom は dp 基準なので fly-to で px → dp 換算に使う。 */
    fun updateDensity(density: Float) {
        this.density = density
        vehicleCameraPositionFactory.updateDensity(density)
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
        vehicleCameraPositionFactory.setGuidanceCameraActive(isActive)
    }

    /**
     * 案内地点フォーカスの対象 GP を更新する。
     *
     * @param guidancePointIndex 次の案内地点 index。案内対象が無い場合は null
     * @param restoreCamera active focus を解除する場合、フォーカス前の camera state へ戻すか。
     *   false の場合は次のフォーカス開始時に復元情報を引き継げるよう active focus を維持する
     */
    fun updateGuidanceManeuverFocusTarget(
        guidancePointIndex: Int?,
        restoreCamera: Boolean = true,
    ) {
        val activeFocus = guidanceManeuverCameraFocus

        if (activeFocus != null && activeFocus.guidancePointIndex != guidancePointIndex) {
            if (restoreCamera) {
                finishGuidanceManeuverFocus(restoreCamera = true)
            }
        }

        if (guidancePointIndex == null) {
            handledGuidanceManeuverFocusIndex = null
            return
        }

        if (handledGuidanceManeuverFocusIndex != null && handledGuidanceManeuverFocusIndex != guidancePointIndex) {
            handledGuidanceManeuverFocusIndex = null
        }
    }

    /**
     * 案内地点フォーカスを必要なら開始する。
     *
     * 1 つの案内地点につき開始は 1 回だけ行う。
     *
     * @param guidancePointIndex フォーカス対象の案内地点 index
     */
    fun startGuidanceManeuverFocusIfNeeded(guidancePointIndex: Int) {
        if (handledGuidanceManeuverFocusIndex == guidancePointIndex) return

        val activeFocus = guidanceManeuverCameraFocus
        if (activeFocus?.guidancePointIndex == guidancePointIndex) return

        if (activeFocus != null) {
            finishGuidanceManeuverFocus(restoreCamera = false)
        }

        handledGuidanceManeuverFocusIndex = guidancePointIndex
        startGuidanceManeuverFocus(
            guidancePointIndex = guidancePointIndex,
            inheritedFocus = activeFocus,
        )
    }

    /**
     * フォーカス中の案内地点を通過した場合はフォーカスを解除する。
     *
     * @param guidancePointIndex 通過判定された案内地点 index
     */
    fun finishGuidanceManeuverFocusIfPassed(guidancePointIndex: Int) {
        val activeFocus = guidanceManeuverCameraFocus ?: return
        if (activeFocus.guidancePointIndex == guidancePointIndex) {
            finishGuidanceManeuverFocus(restoreCamera = true)
        }
    }

    /**
     * 一時的な route mismatch でフォーカスを解除する。
     *
     * 同じ案内地点へ戻った直後に再フォーカスしないよう、対象 GP は処理済みとして保持する。
     *
     * @param guidancePointIndex route mismatch が起きた案内地点 index
     */
    fun finishGuidanceManeuverFocusForRouteMismatch(guidancePointIndex: Int) {
        val activeFocus = guidanceManeuverCameraFocus ?: return
        if (activeFocus.guidancePointIndex == guidancePointIndex) {
            handledGuidanceManeuverFocusIndex = guidancePointIndex
            finishGuidanceManeuverFocus(restoreCamera = true)
        }
    }

    /**
     * 案内地点フォーカスを解除する。
     */
    fun clearGuidanceManeuverFocus() {
        finishGuidanceManeuverFocus(restoreCamera = true)
        handledGuidanceManeuverFocusIndex = null
    }

    /**
     * 自車位置の追従を開始する。
     *
     * @param vehicleLocationState 追従開始時に寄せる自車位置。未取得の場合は次の pose 更新を待つ
     */
    fun followVehicleLocation(vehicleLocationState: VehicleLocationState?) {
        cameraAnimator.cancel()
        cameraState = cameraState.copy(isFollowingMyLocation = true)
        if (vehicleLocationState != null) {
            flyCameraToVehiclePose(vehicleLocationState.toVehiclePose())
        }
    }

    /**
     * 案内開始時のカメラを 3D 追従状態へ初期化する。
     *
     * 現在の perspective / zoom に関わらず、3D 表示・既定ズームへアニメーションする。自車位置が
     * 取得済みなら案内中用の画面手前側 target へ寄せ、未取得なら現在の camera target のまま
     * 3D と既定ズームだけを先に反映する。
     *
     * @param vehicleLocationState 案内開始時点の自車位置。未取得の場合は次の pose 更新を待つ
     */
    fun startGuidanceCamera(vehicleLocationState: VehicleLocationState?) {
        val map = googleMap
        cameraAnimator.cancel()
        map?.stopAnimation()
        gestureController.clear()
        guidanceManeuverCameraFocus = null
        handledGuidanceManeuverFocusIndex = null
        vehicleCameraPositionFactory.setGuidanceCameraActive(true)
        cameraState = cameraState.copy(
            perspective = GoogleMap.CameraPerspective.TILTED,
            isFollowingMyLocation = true,
        )

        val current = map?.cameraPosition
        val vehiclePose = vehicleLocationState?.toVehiclePose() ?: lastVehiclePose
        val target = if (current != null && vehiclePose != null) {
            vehicleCameraPosition(
                vehiclePose = vehiclePose,
                current = current,
                zoom = DEFAULT_CAMERA_ZOOM,
                perspective = GoogleMap.CameraPerspective.TILTED,
            )
        } else if (current != null) {
            CameraPosition.Builder()
                .target(current.target)
                .zoom(DEFAULT_CAMERA_ZOOM)
                .bearing(current.bearing)
                .tilt(vehicleCameraPositionFactory.vehicleTiltDegrees(GoogleMap.CameraPerspective.TILTED))
                .build()
        } else {
            null
        }

        if (target == null) return
        val activeMap = map ?: return

        flyCameraTo(
            target = target,
            keepFollowingMyLocation = true,
            moveCamera = { _, cameraPosition -> moveVehicleCamera(cameraPosition) },
            onFinished = {
                updateCameraPosition(activeMap.cameraPosition)
                cameraState = cameraState.copy(
                    perspective = GoogleMap.CameraPerspective.TILTED,
                    isFollowingMyLocation = true,
                )
            },
        )
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

        if (cameraState.isFollowingMyLocation && !isCameraTransitionInProgress() && !gestureController.isGestureInProgress) {
            val current = googleMap?.cameraPosition ?: return
            val focus = guidanceManeuverCameraFocus

            val cameraPosition = if (focus != null) {
                guidanceManeuverFocusCameraPosition(current)
            } else {
                vehicleCameraPosition(
                    vehiclePose = vehiclePose,
                    current = current,
                )
            }

            moveVehicleCamera(cameraPosition)
        }
    }

    /**
     * 案内地点接近時の真上・拡大フォーカスを開始する。
     *
     * @param guidancePointIndex フォーカス対象の案内地点 index
     * @param inheritedFocus 前の案内地点フォーカスから引き継ぐ復元情報
     */
    private fun startGuidanceManeuverFocus(
        guidancePointIndex: Int,
        inheritedFocus: GuidanceManeuverCameraFocus? = null,
    ) {
        val map = googleMap ?: return
        val current = map.cameraPosition

        guidanceManeuverCameraFocus = GuidanceManeuverCameraFocus(
            guidancePointIndex = guidancePointIndex,
            restoreCameraPosition = inheritedFocus?.restoreCameraPosition ?: current,
            restorePerspective = inheritedFocus?.restorePerspective ?: cameraState.perspective,
            restoreZoom = inheritedFocus?.restoreZoom ?: cameraState.zoom,
            restoreFollowingMyLocation = inheritedFocus?.restoreFollowingMyLocation
                ?: cameraState.isFollowingMyLocation,
        )

        cameraAnimator.cancel()
        map.stopAnimation()
        gestureController.clear()
        cameraState = cameraState.copy(
            zoom = GUIDANCE_MANEUVER_FOCUS_ZOOM,
            perspective = GoogleMap.CameraPerspective.TOP_DOWN_NORTH_UP,
            isFollowingMyLocation = true,
        )

        flyCameraTo(
            target = guidanceManeuverFocusCameraPosition(current),
            targetProvider = { guidanceManeuverFocusCameraPosition(map.cameraPosition) },
            keepFollowingMyLocation = true,
            moveCamera = { _, cameraPosition -> moveVehicleCamera(cameraPosition) },
            onFinished = {
                updateCameraPosition(map.cameraPosition)
                cameraState = cameraState.copy(
                    perspective = GoogleMap.CameraPerspective.TOP_DOWN_NORTH_UP,
                    isFollowingMyLocation = true,
                )
            },
        )
    }

    /**
     * 案内地点フォーカスを終了する。
     *
     * @param restoreCamera true の場合、フォーカス開始前の camera state へ戻す
     */
    private fun finishGuidanceManeuverFocus(restoreCamera: Boolean) {
        val focus = guidanceManeuverCameraFocus ?: return
        guidanceManeuverCameraFocus = null

        if (restoreCamera) {
            restoreGuidanceManeuverCamera(focus)
        }
    }

    /**
     * ユーザー gesture によって案内地点フォーカスを終了する。
     */
    private fun cancelGuidanceManeuverFocusByGesture() {
        val focus = guidanceManeuverCameraFocus ?: return

        handledGuidanceManeuverFocusIndex = focus.guidancePointIndex
        guidanceManeuverCameraFocus = null
        gestureController.clear()
        cameraState = cameraState.copy(isFollowingMyLocation = false)
    }

    /**
     * 案内地点フォーカス開始前の camera state へ戻す。
     *
     * @param focus 復元に使うフォーカス状態
     */
    private fun restoreGuidanceManeuverCamera(focus: GuidanceManeuverCameraFocus) {
        val map = googleMap ?: return

        cameraAnimator.cancel()
        map.stopAnimation()
        gestureController.clear()
        cameraState = cameraState.copy(
            zoom = focus.restoreZoom,
            perspective = focus.restorePerspective,
            isFollowingMyLocation = focus.restoreFollowingMyLocation,
        )

        flyCameraTo(
            target = restoredGuidanceManeuverCameraPosition(focus),
            targetProvider = { restoredGuidanceManeuverCameraPosition(focus) },
            keepFollowingMyLocation = focus.restoreFollowingMyLocation,
            moveCamera = { _, cameraPosition -> moveVehicleCamera(cameraPosition) },
            onFinished = {
                updateCameraPosition(map.cameraPosition)
                cameraState = cameraState.copy(
                    perspective = focus.restorePerspective,
                    isFollowingMyLocation = focus.restoreFollowingMyLocation,
                )
            },
        )
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
        durationMs: Long? = ROUTE_OVERVIEW_ANIMATION_DURATION_MS,
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
        gestureController.clear()
        cameraState = cameraState.copy(isFollowingMyLocation = true)

        cameraAnimator.animateFollowZoomTo(
            targetZoom = targetZoom,
            onFrame = { zoom ->
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
            },
            onFinished = {
                cameraState = cameraState.copy(isFollowingMyLocation = true)
            },
        )
    }

    /**
     * 自前 animator が動作中かを返す。
     *
     * @return カメラ遷移中で自車追従の即時 moveCamera を止めるべきなら true
     */
    private fun isCameraTransitionInProgress(): Boolean = cameraAnimator.isRunning

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

        cameraAnimator.cancel()
        gestureController.clear()
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
     * @param perspective 設定する camera perspective
     * @return perspective と zoom を反映したカメラ位置
     */
    private fun vehicleCameraPosition(
        vehiclePose: VehiclePose,
        current: CameraPosition,
        zoom: Float = cameraState.zoom,
        perspective: Int = cameraState.perspective,
    ): CameraPosition = vehicleCameraPositionFactory.vehicleCameraPosition(
        vehiclePose = vehiclePose,
        current = current,
        zoom = zoom,
        perspective = perspective,
    )

    /**
     * 案内地点フォーカス中の heading-up 真上カメラ位置を作る。
     *
     * @param current 現在のカメラ位置
     * @return 真上・拡大表示のカメラ位置
     */
    private fun guidanceManeuverFocusCameraPosition(current: CameraPosition): CameraPosition {
        val vehiclePose = lastVehiclePose
        if (vehiclePose != null) {
            return CameraPosition.Builder()
                .target(LatLng(vehiclePose.location.latitude, vehiclePose.location.longitude))
                .zoom(GUIDANCE_MANEUVER_FOCUS_ZOOM)
                .bearing(vehiclePose.bearingDegrees ?: current.bearing)
                .tilt(0f)
                .build()
        }

        return CameraPosition.Builder()
            .target(current.target)
            .zoom(GUIDANCE_MANEUVER_FOCUS_ZOOM)
            .bearing(0f)
            .tilt(0f)
            .build()
    }

    /**
     * 案内地点フォーカス解除時の復元カメラ位置を作る。
     *
     * @param focus 復元に使うフォーカス状態
     * @return 復元先のカメラ位置
     */
    private fun restoredGuidanceManeuverCameraPosition(focus: GuidanceManeuverCameraFocus): CameraPosition {
        val current = googleMap?.cameraPosition ?: focus.restoreCameraPosition
        val vehiclePose = lastVehiclePose

        if (focus.restoreFollowingMyLocation && vehiclePose != null) {
            return vehicleCameraPosition(
                vehiclePose = vehiclePose,
                current = current,
                zoom = focus.restoreZoom,
                perspective = focus.restorePerspective,
            )
        }

        return focus.restoreCameraPosition
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
    ): CameraPosition = vehicleCameraPositionFactory.compassCameraPosition(
        current = current,
        perspective = perspective,
    )

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

        return vehicleCameraPositionFactory.isCameraTargetAwayFromVehicle(
            cameraPosition = cameraPosition,
            vehiclePose = vehiclePose,
            perspective = cameraState.perspective,
        )
    }

    /**
     * カメラを [target] へ van Wijk–Nuij "Smooth and efficient zooming and panning" の経路で移動させる。
     * 始点と終点が遠ければ途中で一旦ズームアウトしてから寄り直す弧を描き、近ければ弧が消えてただのイージングになる。
     * bearing / tilt は経路とは別チャンネルで線形補間する。地図ビューのサイズが未確定（レイアウト前）なら
     * 単純補間の [animateCameraTo] にフォールバックする。
     *
     * @param target 移動先 camera position
     * @param targetProvider frame ごとに移動先を更新する provider。null なら [target] を固定終点として使う
     * @param durationMs アニメーション時間（ms）。null なら経路長から自動算出する
     * @param zoomEasing 指定すると、ズーム（= log ビューポート幅）を弧長から切り離し、生のアニメ進捗に
     *   このイージングをかけて log 空間で補間する。引き量が大きいときに終端の減速をはっきり効かせるための逃げ道。
     *   null なら従来どおりズームも弧長パラメータから導出する（中心パンと完全に結合）。
     */
    private fun flyCameraTo(
        target: CameraPosition,
        targetProvider: (() -> CameraPosition)? = null,
        durationMs: Long? = null,
        zoomEasing: TimeInterpolator? = null,
        keepFollowingMyLocation: Boolean = false,
        moveCamera: (GoogleMap, CameraPosition) -> Unit = { map, cameraPosition ->
            map.moveCamera(CameraUpdateFactory.newCameraPosition(cameraPosition))
        },
        onStarted: () -> Unit = {},
        onFinished: () -> Unit = {},
    ) {
        if (googleMap == null) return

        if (!keepFollowingMyLocation) {
            cameraState = cameraState.copy(isFollowingMyLocation = false)
        }

        cameraAnimator.flyTo(
            target = target,
            targetProvider = targetProvider,
            durationMs = durationMs,
            zoomEasing = zoomEasing,
            moveCamera = moveCamera,
            onFallback = {
                animateCameraTo(
                    target = target,
                    targetProvider = targetProvider,
                    panDurationMs = durationMs,
                    zoomDurationMs = durationMs,
                    keepFollowingMyLocation = keepFollowingMyLocation,
                    moveCamera = moveCamera,
                    onStarted = onStarted,
                    onFinished = onFinished,
                )
            },
            onStarted = onStarted,
            onFinished = onFinished,
        )
    }

    /**
     * カメラを [target] へアニメーション移動させる。pan（位置・向き・傾き）と zoom は
     * それぞれ独立した duration を持ち、短い方が先に到達して止まる（fraction を頭打ちにする）。
     * animator 本体は両者の長い方の長さで線形に回し、各チャンネルへ [DecelerateInterpolator] を個別に適用する。
     * 現在は [flyCameraTo] が使えない（地図ビューのサイズが未確定など）ときの単純補間フォールバックとして残してある。
     *
     * @param target 移動先 camera position
     * @param targetProvider frame ごとに移動先を更新する provider。null なら [target] を固定終点として使う
     * @param panDurationMs pan の所要時間（ms）。null なら既定値
     * @param zoomDurationMs zoom の所要時間（ms）。null ならズーム差から自動算出
     */
    private fun animateCameraTo(
        target: CameraPosition,
        targetProvider: (() -> CameraPosition)? = null,
        panDurationMs: Long? = null,
        zoomDurationMs: Long? = null,
        keepFollowingMyLocation: Boolean = false,
        moveCamera: (GoogleMap, CameraPosition) -> Unit = { map, cameraPosition ->
            map.moveCamera(CameraUpdateFactory.newCameraPosition(cameraPosition))
        },
        onStarted: () -> Unit = {},
        onFinished: () -> Unit = {},
    ) {
        if (googleMap == null) return

        if (!keepFollowingMyLocation) {
            cameraState = cameraState.copy(isFollowingMyLocation = false)
        }

        cameraAnimator.animateTo(
            target = target,
            targetProvider = targetProvider,
            panDurationMs = panDurationMs,
            zoomDurationMs = zoomDurationMs,
            moveCamera = moveCamera,
            onStarted = onStarted,
            onFinished = onFinished,
        )
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

    companion object {
        /** GoogleMap に許容する最小ズーム値。 */
        private const val MIN_ZOOM = 2f

        /** GoogleMap に許容する最大ズーム値。 */
        private const val MAX_ZOOM = 21f

        /** ルート全体表示時に画面端へ確保する既定 padding（px）。 */
        private const val ROUTE_OVERVIEW_PADDING_PX = 64

        /** ルート全体表示時の既定 animation 時間（ms）。 */
        private const val ROUTE_OVERVIEW_ANIMATION_DURATION_MS = 1_500L

        /**
         * [showRouteOverview] の引きアニメで、ズームレベル（= log ビューポート幅）へ直接かける減速の強さ。
         * 引き量が大きく弧長への減速だと終端の減速が知覚されにくいので強めにする。
         */
        private const val CAMERA_ROUTE_OVERVIEW_ZOOM_DECELERATE_FACTOR = 5f

        /** density が未通知の間に使う既定値。 */
        private const val DEFAULT_DENSITY = 1f

        /** コンパス button による 3D heading-up / 2D north-up 切り替え animation 時間（ms）。 */
        private const val COMPASS_PERSPECTIVE_ANIMATION_DURATION_MS = 500L

        /** 案内地点フォーカス中の zoom。 */
        private const val GUIDANCE_MANEUVER_FOCUS_ZOOM = 18f
    }
}

/**
 * 案内地点接近時の一時フォーカス状態。
 *
 * @param guidancePointIndex フォーカス対象の案内地点 index
 * @param restoreCameraPosition フォーカス解除時に戻すカメラ位置
 * @param restorePerspective フォーカス解除時に戻す perspective
 * @param restoreZoom フォーカス解除時に戻す zoom
 * @param restoreFollowingMyLocation フォーカス解除時に戻す自車追従状態
 */
@Immutable
private data class GuidanceManeuverCameraFocus(
    val guidancePointIndex: Int,
    val restoreCameraPosition: CameraPosition,
    val restorePerspective: Int,
    val restoreZoom: Float,
    val restoreFollowingMyLocation: Boolean,
)

/**
 * 地図カメラの現在状態。
 *
 * @param zoom 現在のズーム値
 * @param bearing 現在のカメラの向き
 * @param perspective GoogleMap.CameraPerspective
 * @param isFollowingMyLocation マップカメラが現在自分の位置に追従しているかどうか
 */
@Stable
internal data class MapCameraSnapshot(
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
