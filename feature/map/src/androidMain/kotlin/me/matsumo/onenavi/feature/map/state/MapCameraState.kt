package me.matsumo.onenavi.feature.map.state

import android.animation.TimeInterpolator
import android.view.animation.DecelerateInterpolator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import me.matsumo.onenavi.core.model.RoutePoint
import me.matsumo.onenavi.feature.map.state.MapCameraState.Companion.CAMERA_ROUTE_OVERVIEW_ZOOM_DECELERATE_FACTOR
import me.matsumo.onenavi.feature.map.state.MapCameraState.Companion.Saver

/**
 * GoogleMap 用のカメラ状態 holder を Compose 上で保持する。
 *
 * @return remember 済みの [MapCameraState]
 */
@Composable
internal fun rememberMapCameraState(): MapCameraState {
    val density = LocalDensity.current.density
    val state = rememberSaveable(saver = MapCameraState.Saver) { MapCameraState() }

    SideEffect {
        state.updateDensity(density)
    }

    return state
}

/**
 * GoogleMap のカメラ操作と UI 表示用カメラ状態を仲介する state holder。
 */
@Stable
internal class MapCameraState internal constructor(
    initialRestoreState: MapCameraRestoreState? = null,
) {

    private var googleMap: GoogleMap? = null
    private val cameraAnimator = MapCameraAnimator(
        mapProvider = { googleMap },
        viewportWidthDpProvider = { mapViewWidthPx.toDouble() / density },
    )
    private val vehicleCameraPositionFactory = VehicleCameraPositionFactory()
    private val gestureController = MapCameraGestureController()
    private var mapViewWidthPx: Int = 0
    private var mapViewHeightPx: Int = 0
    private var rawTopPaddingPx: Int = 0
    private var rawBottomPaddingPx: Int = 0
    private var startPaddingPx: Int = 0
    private var endPaddingPx: Int = 0
    private var hasCameraPadding: Boolean = false
    private var guidanceAnchorFraction: Float? = null
    private var isGuidanceCameraActive: Boolean = initialRestoreState?.isGuidanceCameraActive ?: false
    private var density: Float = DEFAULT_DENSITY
    private var lastVehiclePose: VehiclePose? = null
    private var guidanceManeuverCameraFocus: GuidanceManeuverCameraFocus? = null
    private var handledGuidanceManeuverFocusIndex: Int? = null
    private var pendingRestoreState: MapCameraRestoreState? = initialRestoreState
    private var pendingManualBrowsingRestore: Boolean = initialRestoreState?.isFollowingMyLocation == false

    var cameraState by mutableStateOf(
        if (initialRestoreState == null) {
            MapCameraSnapshot()
        } else {
            MapCameraSnapshot(
                zoom = initialRestoreState.zoom,
                perspective = initialRestoreState.perspective,
                isFollowingMyLocation = initialRestoreState.isFollowingMyLocation,
            )
        },
    )
        private set

    /** 最後に受け取った自車位置の緯度。未取得の場合は初期緯度を返す。 */
    val myLocationLatitude: Double
        get() = lastVehiclePose?.location?.latitude ?: MapCameraDefaults.DEFAULT_LATITUDE

    /** 最後に受け取った自車位置の経度。未取得の場合は初期経度を返す。 */
    val myLocationLongitude: Double
        get() = lastVehiclePose?.location?.longitude ?: MapCameraDefaults.DEFAULT_LONGITUDE

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

        applyCameraPadding()
        applyPendingRestoreIfReady()
    }

    /**
     * 接続後の GoogleMap へ、保存しておいた復元カメラ位置を一度だけ適用する。
     *
     * 画面回転・画面遷移・プロセス死で MapView が作り直されると初期 target（既定座標）から始まるため、
     * 復元位置へ即座に寄せて既定座標のチラ見えを防ぐ。案内中復元では下端アンカー用 padding が必要なため、
     * padding 初回更新までは適用を保留する。bearing は保存しないので 0 から始め、追従再開時に自車 heading
     * へ更新される。
     */
    private fun applyPendingRestoreIfReady() {
        val map = googleMap ?: return
        val restore = pendingRestoreState ?: return
        if (restore.isGuidanceCameraActive && !hasCameraPadding) return

        pendingRestoreState = null

        val cameraPosition = CameraPosition.Builder()
            .target(LatLng(restore.latitude, restore.longitude))
            .zoom(restore.zoom)
            .bearing(0f)
            .tilt(vehicleCameraPositionFactory.vehicleTiltDegrees(restore.perspective))
            .build()

        map.moveCamera(CameraUpdateFactory.newCameraPosition(cameraPosition))
    }

    /**
     * 復元された手動閲覧状態を初回 Browsing で 1 度だけ消費する。
     *
     * 復元時に追従 OFF（手動でカメラを動かしていた）だった場合のみ true を返し、以後は false を返す。
     * これにより初回の自車追従への自動復帰を抑制し、復元したカメラ位置を維持する。
     *
     * @return 初回 Browsing で自動追従を抑制すべき場合 true
     */
    fun consumeInitialBrowsingRestore(): Boolean {
        if (!pendingManualBrowsingRestore) return false
        pendingManualBrowsingRestore = false
        return true
    }

    /**
     * 現在のカメラ状態を保存用の復元データへ変換する。
     *
     * target は UI state に持たないため GoogleMap から直接読み取る。未接続の場合は target を取得できないため
     * null を返す。
     *
     * @return 保存対象の [MapCameraRestoreState]。GoogleMap 未接続時は null
     */
    fun toRestoreState(): MapCameraRestoreState? {
        val cameraPosition = googleMap?.cameraPosition ?: return null

        return MapCameraRestoreState(
            latitude = cameraPosition.target.latitude,
            longitude = cameraPosition.target.longitude,
            zoom = cameraState.zoom,
            perspective = cameraState.perspective,
            isFollowingMyLocation = cameraState.isFollowingMyLocation,
            isGuidanceCameraActive = isGuidanceCameraActive,
        )
    }

    /**
     * 地図ビューの実ピクセルサイズを記録する。
     *
     * @param widthPx 地図ビューの幅（px）
     * @param heightPx 地図ビューの高さ（px）
     */
    fun updateViewportSize(widthPx: Int, heightPx: Int) {
        if (mapViewWidthPx == widthPx && mapViewHeightPx == heightPx) return

        mapViewWidthPx = widthPx
        mapViewHeightPx = heightPx
        vehicleCameraPositionFactory.updateViewportHeight(heightPx)
        applyCameraPadding()
        moveFollowCameraIfNeeded()
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
     * @param guidanceAnchorFraction 案内中に自車を画面下端から置く割合。null の場合は Compact 用のカード基準を使う
     */
    fun updatePadding(
        top: Int,
        bottom: Int,
        start: Int,
        end: Int,
        guidanceAnchorFraction: Float? = null,
    ) {
        rawTopPaddingPx = top
        rawBottomPaddingPx = bottom
        startPaddingPx = start
        endPaddingPx = end
        hasCameraPadding = true
        this.guidanceAnchorFraction = guidanceAnchorFraction
        applyCameraPadding()
        applyPendingRestoreIfReady()
        moveFollowCameraIfNeeded()
    }

    /**
     * UI 帯レイアウトまたは viewport 制約が変わったことを通知する。
     *
     * レイアウト変更は padding の即時スナップとして扱うため、進行中の自前 animation と GoogleMap animation を
     * この経路でのみ中断する。通常の padding 更新では animation を止めない。
     */
    fun onPanelLayoutChanged() {
        val map = googleMap
        cameraAnimator.cancel()
        map?.stopAnimation()
        applyCameraPadding()
        moveFollowCameraIfNeeded()
    }

    /**
     * 案内中カメラとして扱うかを更新する。
     *
     * 案内中は自車を padding center へ置く。Compact では下部カード上端から
     * [VEHICLE_ANCHOR_MARGIN_FROM_BOTTOM_DP] 上、分割レイアウトでは画面下端から
     * [guidanceAnchorFraction] の位置に来るよう [applyCameraPadding] で上 padding を調整する。
     * それ以外は実際の obstruction padding をそのまま使い、自車を可視領域の中心へ置く。
     * モードが変わるたびに padding を再適用する。
     *
     * @param isActive 案内中カメラとして扱う場合 true
     */
    fun setGuidanceCameraActive(isActive: Boolean) {
        isGuidanceCameraActive = isActive
        applyCameraPadding()
        moveFollowCameraIfNeeded()
    }

    /**
     * 現在のモードに応じた描画 padding を GoogleMap へ適用する。
     *
     * GoogleMap は camera target を「padding を除いた可視領域の中心」に置く。分割レイアウトでは
     * MapView の実 viewport を UI 帯幅ぶん広げ、左右対称 padding で padded center と 3D 投影中心を
     * 地図領域の中心へ一致させる。案内中追従の縦方向は、その中心が Compact では下部カード基準、
     * 分割レイアウトでは [guidanceAnchorFraction] 基準に一致するよう上 padding を算出する。
     * 案内中以外の split 縦方向は上下対称 padding にして、camera target と 3D 投影中心を一致させる。
     */
    private fun applyCameraPadding() {
        val map = googleMap ?: return
        map.setPadding(startPaddingPx, resolveTopPaddingPx(), endPaddingPx, rawBottomPaddingPx)
    }

    /**
     * 適用する上 padding（px）を返す。算出は [GuidanceCameraPadding] に委譲する。
     *
     * @return GoogleMap へ渡す上 padding（px）
     */
    private fun resolveTopPaddingPx(): Int = GuidanceCameraPadding.resolveTopPaddingPx(
        isGuidanceFollowActive = isGuidanceCameraActive,
        mapViewHeightPx = mapViewHeightPx,
        rawTopPaddingPx = rawTopPaddingPx,
        rawBottomPaddingPx = rawBottomPaddingPx,
        density = density,
        anchorFractionFromBottom = guidanceAnchorFraction,
    )

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
        pendingManualBrowsingRestore = false
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
        pendingManualBrowsingRestore = false
        val map = googleMap
        cameraAnimator.cancel()
        map?.stopAnimation()
        gestureController.clear()
        guidanceManeuverCameraFocus = null
        handledGuidanceManeuverFocusIndex = null
        isGuidanceCameraActive = true
        applyCameraPadding()
        cameraState = cameraState.copy(
            perspective = MapCameraPerspective.TILTED,
            isFollowingMyLocation = true,
        )

        val activeMap = map ?: return
        val startCameraPosition = activeMap.cameraPosition
        val vehiclePose = vehicleLocationState?.toVehiclePose() ?: lastVehiclePose
        val target = guidanceStartCameraPosition(
            vehiclePose = vehiclePose,
            current = startCameraPosition,
        )

        flyCameraTo(
            target = target,
            targetProvider = {
                guidanceStartCameraPosition(
                    vehiclePose = lastVehiclePose ?: vehiclePose,
                    current = startCameraPosition,
                )
            },
            keepFollowingMyLocation = true,
            moveCamera = { _, cameraPosition -> moveVehicleCamera(cameraPosition) },
            onFinished = {
                updateCameraPosition(activeMap.cameraPosition)
                cameraState = cameraState.copy(
                    perspective = MapCameraPerspective.TILTED,
                    isFollowingMyLocation = true,
                )
                moveFollowCameraIfNeeded()
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
        moveFollowCameraIfNeeded()
    }

    /**
     * 追従中で明示的なカメラアニメーションが走っていない場合に、最新 pose（または案内地点
     * フォーカス）へカメラ中心を即時追従させる。pose 更新時と padding 更新時の両方から呼ぶ。
     */
    private fun moveFollowCameraIfNeeded() {
        val vehiclePose = lastVehiclePose ?: return
        val isCameraBusy = isCameraTransitionInProgress() || gestureController.isGestureInProgress
        if (!cameraState.isFollowingMyLocation || isCameraBusy) {
            return
        }

        val current = googleMap?.cameraPosition ?: return
        val focus = guidanceManeuverCameraFocus
        val followCameraPosition = if (focus != null) {
            guidanceManeuverFocusCameraPosition(current)
        } else {
            centeredVehicleCameraPosition(vehiclePose = vehiclePose, current = current)
        }

        moveVehicleCamera(followCameraPosition)
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
            perspective = MapCameraPerspective.TOP_DOWN_NORTH_UP,
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
                    perspective = MapCameraPerspective.TOP_DOWN_NORTH_UP,
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
        val nextPerspective = if (cameraState.perspective == MapCameraPerspective.TILTED) {
            MapCameraPerspective.TOP_DOWN_NORTH_UP
        } else {
            MapCameraPerspective.TILTED
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
        pendingManualBrowsingRestore = false
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
     * 呼び出し時点で自車追従や案内地点フォーカスが有効な場合は、それらを解除してから全体表示する。
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

        pendingManualBrowsingRestore = false
        cameraAnimator.cancel()
        map.stopAnimation()
        gestureController.clear()
        guidanceManeuverCameraFocus = null
        handledGuidanceManeuverFocusIndex = null
        isGuidanceCameraActive = false
        applyCameraPadding()
        cameraState = cameraState.copy(isFollowingMyLocation = false)

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
                    centeredVehicleCameraPosition(
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
     * コンパス button の perspective 変更を減衰補間でアニメーションする。
     *
     * camera target は毎 frame の最新自車位置に固定し（位置の pan 補間をしない）、tilt と bearing だけを
     * 開始値から目標値へ補間する。位置を補間する [flyCameraTo] では、アニメ開始時の自車位置から終点へ
     * pan する経路の途中ではカメラが旧位置に留まり、走行で前進した自車（puck）が前方へずれる。この
     * ずれは tilt が大きいほど foreshortening で画面上に拡大されるため、開始時に tilt が高い 3D→2D で
     * 特に自車が中央へ跳ねて見えた。位置を毎 frame 固定することでこれを解消する。
     *
     * @param perspective 切り替え後の [MapCameraPerspective]
     */
    private fun animateCompassPerspective(perspective: Int) {
        val map = googleMap ?: return
        val start = map.cameraPosition
        val startTilt = start.tilt
        val startBearing = start.bearing
        val targetTilt = vehicleCameraPositionFactory.vehicleTiltDegrees(perspective)

        cameraAnimator.cancel()
        gestureController.clear()
        map.stopAnimation()
        cameraState = cameraState.copy(
            perspective = perspective,
            isFollowingMyLocation = true,
        )

        cameraAnimator.animatePerspective(
            durationMs = COMPASS_PERSPECTIVE_ANIMATION_DURATION_MS,
            onFrame = { fraction ->
                moveVehicleCamera(
                    compassPerspectiveFrameCameraPosition(
                        map = map,
                        perspective = perspective,
                        startTilt = startTilt,
                        startBearing = startBearing,
                        targetTilt = targetTilt,
                        fraction = fraction,
                    ),
                )
            },
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
     * コンパス perspective 切り替えアニメーションの frame ごとのカメラ位置を作る。
     *
     * 位置は最新の自車位置に固定し、tilt と bearing だけを開始値から目標値へ [fraction] で補間する。
     * 目標 bearing は 2D north-up では 0、3D heading-up では最新の自車進行方向（未取得なら現在の
     * bearing）。自車 pose が無い場合は現在の camera target を保つ。
     *
     * @param map 対象の GoogleMap
     * @param perspective 切り替え後の [MapCameraPerspective]
     * @param startTilt 補間開始時の tilt
     * @param startBearing 補間開始時の bearing
     * @param targetTilt 補間目標の tilt
     * @param fraction 減衰補間後の進捗（0..1）
     * @return frame に適用するカメラ位置
     */
    private fun compassPerspectiveFrameCameraPosition(
        map: GoogleMap,
        perspective: Int,
        startTilt: Float,
        startBearing: Float,
        targetTilt: Float,
        fraction: Float,
    ): CameraPosition {
        val current = map.cameraPosition
        val vehiclePose = lastVehiclePose
        val targetBearing = compassPerspectiveTargetBearing(
            perspective = perspective,
            vehiclePose = vehiclePose,
            current = current,
        )
        val center = vehiclePose?.let { pose ->
            LatLng(pose.location.latitude, pose.location.longitude)
        } ?: current.target

        return CameraPosition.Builder()
            .target(center)
            .zoom(cameraState.zoom)
            .bearing(MapInterpolation.lerpAngleDegrees(startBearing, targetBearing, fraction))
            .tilt(MapInterpolation.lerp(startTilt, targetTilt, fraction))
            .build()
    }

    /**
     * コンパス perspective 切り替えの目標 bearing を返す。
     *
     * 2D north-up では 0 度、3D heading-up では最新の自車進行方向（未取得なら現在の bearing）。
     *
     * @param perspective 切り替え後の [MapCameraPerspective]
     * @param vehiclePose 最新の自車 pose
     * @param current 現在のカメラ位置
     * @return 補間目標の bearing
     */
    private fun compassPerspectiveTargetBearing(
        perspective: Int,
        vehiclePose: VehiclePose?,
        current: CameraPosition,
    ): Float = when (perspective) {
        MapCameraPerspective.TOP_DOWN_NORTH_UP -> 0f
        else -> vehiclePose?.bearingDegrees ?: current.bearing
    }

    /**
     * 追従開始時の自車位置へ既存の fly-to 経路で寄せる。
     *
     * @param vehiclePose 寄せ先の自車 pose
     */
    private fun flyCameraToVehiclePose(vehiclePose: VehiclePose) {
        val current = googleMap?.cameraPosition ?: return

        flyCameraTo(
            target = centeredVehicleCameraPosition(vehiclePose = vehiclePose, current = current),
            keepFollowingMyLocation = true,
            moveCamera = { _, cameraPosition -> moveVehicleCamera(cameraPosition) },
            onFinished = {
                moveFollowCameraIfNeeded()
            },
        )
    }

    /**
     * 自車を camera target へ置く追従カメラ位置を作る。画面上の表示位置は GoogleMap の padding で与える。
     *
     * @param vehiclePose frame 時点の自車 pose
     * @param current 現在のカメラ位置
     * @param zoom 設定する zoom 値
     * @param perspective 設定する camera perspective
     * @return 自車を中心に置いたカメラ位置
     */
    private fun centeredVehicleCameraPosition(
        vehiclePose: VehiclePose,
        current: CameraPosition,
        zoom: Float = cameraState.zoom,
        perspective: Int = cameraState.perspective,
    ): CameraPosition = vehicleCameraPositionFactory.centeredVehicleCameraPosition(
        vehiclePose = vehiclePose,
        current = current,
        zoom = zoom,
        perspective = perspective,
    )

    /**
     * 案内開始 animation のカメラ位置を作る。
     *
     * @param vehiclePose 自車 pose。未取得なら現在の target / bearing を維持する
     * @param current 現在のカメラ位置
     * @return 案内開始 animation の移動先カメラ位置
     */
    private fun guidanceStartCameraPosition(
        vehiclePose: VehiclePose?,
        current: CameraPosition,
    ): CameraPosition {
        if (vehiclePose != null) {
            return centeredVehicleCameraPosition(
                vehiclePose = vehiclePose,
                current = current,
                zoom = MapCameraDefaults.DEFAULT_ZOOM,
                perspective = MapCameraPerspective.TILTED,
            )
        }

        return CameraPosition.Builder()
            .target(current.target)
            .zoom(MapCameraDefaults.DEFAULT_ZOOM)
            .bearing(current.bearing)
            .tilt(vehicleCameraPositionFactory.vehicleTiltDegrees(MapCameraPerspective.TILTED))
            .build()
    }

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
            return centeredVehicleCameraPosition(
                vehiclePose = vehiclePose,
                current = current,
                zoom = focus.restoreZoom,
                perspective = focus.restorePerspective,
            )
        }

        return focus.restoreCameraPosition
    }

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

        /** 旧 [Saver] 復元データの要素数。 */
        private const val LEGACY_RESTORE_FIELD_COUNT = 5

        /** [Saver] が保存する復元データの要素数。 */
        private const val RESTORE_FIELD_COUNT = 6

        /**
         * 画面回転・画面遷移・プロセス死を跨いで [MapCameraState] を復元する [Saver]。
         *
         * target は UI state に持たないため保存時に GoogleMap から読み取る。GoogleMap 未接続で target を
         * 取得できない場合は空リストを保存し、復元時は既定状態で開始する。
         */
        val Saver: Saver<MapCameraState, Any> = listSaver(
            save = { state ->
                val restore = state.toRestoreState() ?: return@listSaver emptyList()
                listOf(
                    restore.latitude,
                    restore.longitude,
                    restore.zoom,
                    restore.perspective,
                    restore.isFollowingMyLocation,
                    restore.isGuidanceCameraActive,
                )
            },
            restore = { saved ->
                MapCameraState(initialRestoreState = restoreStateFromSavedFields(saved))
            },
        )

        /**
         * [Saver] の保存リストから復元データを作る。
         *
         * @param saved 保存済みフィールド
         * @return 復元できる場合は [MapCameraRestoreState]、フィールド数が不正な場合は null
         */
        internal fun restoreStateFromSavedFields(
            saved: List<Any?>,
        ): MapCameraRestoreState? {
            val fields = saved.takeIf { fields ->
                when (fields.size) {
                    RESTORE_FIELD_COUNT,
                    LEGACY_RESTORE_FIELD_COUNT,
                    -> true

                    else -> false
                }
            } ?: return null

            return MapCameraRestoreState(
                latitude = fields[0] as Double,
                longitude = fields[1] as Double,
                zoom = fields[2] as Float,
                perspective = fields[3] as Int,
                isFollowingMyLocation = fields[4] as Boolean,
                isGuidanceCameraActive = fields.getOrNull(5) as? Boolean ?: false,
            )
        }
    }
}

/**
 * 画面回転・画面遷移・プロセス死を跨いでカメラを復元するための保存データ。
 *
 * @param latitude 復元するカメラ target の緯度
 * @param longitude 復元するカメラ target の経度
 * @param zoom 復元するズーム値
 * @param perspective 復元する [MapCameraPerspective]
 * @param isFollowingMyLocation 復元時点で自車追従中だったか
 * @param isGuidanceCameraActive 復元時点で案内用の下端アンカーを使っていたか
 */
@Immutable
internal data class MapCameraRestoreState(
    val latitude: Double,
    val longitude: Double,
    val zoom: Float,
    val perspective: Int,
    val isFollowingMyLocation: Boolean,
    val isGuidanceCameraActive: Boolean,
)

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
 * @param perspective [MapCameraPerspective]
 * @param isFollowingMyLocation マップカメラが現在自分の位置に追従しているかどうか
 */
@Stable
internal data class MapCameraSnapshot(
    val zoom: Float = MapCameraDefaults.DEFAULT_ZOOM,
    val bearing: Double = 0.0,
    val perspective: Int = MapCameraPerspective.TILTED,
    val isFollowingMyLocation: Boolean = false,
)

/**
 * 地図カメラの初期表示値。
 */
internal object MapCameraDefaults {

    /** 初期カメラ target の緯度。 */
    const val DEFAULT_LATITUDE = 35.681236

    /** 初期カメラ target の経度。 */
    const val DEFAULT_LONGITUDE = 139.767125

    /** 初期カメラ zoom。 */
    const val DEFAULT_ZOOM = 17f
}
