package me.matsumo.onenavi.core.navigation

import com.mapbox.geojson.Point
import com.mapbox.maps.EdgeInsets
import com.mapbox.maps.MapView
import com.mapbox.maps.plugin.animation.camera
import com.mapbox.navigation.base.ExperimentalPreviewMapboxNavigationAPI
import com.mapbox.navigation.base.route.NavigationRoute
import com.mapbox.navigation.core.MapboxNavigation
import com.mapbox.navigation.core.lifecycle.MapboxNavigationApp
import com.mapbox.navigation.core.lifecycle.MapboxNavigationObserver
import com.mapbox.navigation.core.trip.session.LocationMatcherResult
import com.mapbox.navigation.core.trip.session.LocationObserver
import com.mapbox.navigation.ui.maps.camera.NavigationCamera
import com.mapbox.navigation.ui.maps.camera.data.MapboxNavigationViewportDataSource
import com.mapbox.navigation.ui.maps.camera.lifecycle.NavigationBasicGesturesHandler
import com.mapbox.navigation.ui.maps.camera.state.NavigationCameraState
import com.mapbox.navigation.ui.maps.camera.transition.NavigationCameraTransitionOptions
import com.mapbox.navigation.ui.maps.location.NavigationLocationProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * カメラ制御と位置情報管理を担当するクラス。
 * NavigationCamera / ViewportDataSource / NavigationLocationProvider のライフサイクルを管理し、
 * Following / Overview / Idle モードの切り替えを提供する。
 */
@OptIn(ExperimentalPreviewMapboxNavigationAPI::class)
class CameraManager {

    private var mapboxNavigation: MapboxNavigation? = null

    var navigationCamera: NavigationCamera? = null
        private set

    var viewportDataSource: MapboxNavigationViewportDataSource? = null
        private set

    val navigationLocationProvider = NavigationLocationProvider()

    private val _cameraState = MutableStateFlow(NavigationCameraState.IDLE)

    /** 現在のカメラ状態。 */
    val cameraState: StateFlow<NavigationCameraState> = _cameraState.asStateFlow()

    private val _isFollowing3D = MutableStateFlow(true)

    /** Following 3D モードかどうか。false なら Following 2D（北固定）。 */
    val isFollowing3D: StateFlow<Boolean> = _isFollowing3D.asStateFlow()

    private val _currentLocation = MutableStateFlow<Point?>(null)

    /** 最新の enhanced location。 */
    val currentLocation: StateFlow<Point?> = _currentLocation.asStateFlow()

    private val locationObserver = object : LocationObserver {
        override fun onNewRawLocation(rawLocation: com.mapbox.common.location.Location) {
            // raw location は使わない、enhanced location を優先
        }

        override fun onNewLocationMatcherResult(locationMatcherResult: LocationMatcherResult) {
            val enhancedLocation = locationMatcherResult.enhancedLocation
            navigationLocationProvider.changePosition(
                location = enhancedLocation,
                keyPoints = locationMatcherResult.keyPoints,
            )
            viewportDataSource?.onLocationChanged(enhancedLocation)
            viewportDataSource?.evaluate()

            _currentLocation.value = Point.fromLngLat(
                enhancedLocation.longitude,
                enhancedLocation.latitude,
            )
        }
    }

    private val navigationObserver = object : MapboxNavigationObserver {
        override fun onAttached(mapboxNavigation: MapboxNavigation) {
            this@CameraManager.mapboxNavigation = mapboxNavigation
            mapboxNavigation.registerLocationObserver(locationObserver)
        }

        override fun onDetached(mapboxNavigation: MapboxNavigation) {
            mapboxNavigation.unregisterLocationObserver(locationObserver)
            this@CameraManager.mapboxNavigation = null
        }
    }

    fun register() {
        MapboxNavigationApp.registerObserver(navigationObserver)
    }

    fun unregister() {
        MapboxNavigationApp.unregisterObserver(navigationObserver)
    }

    /**
     * MapView が利用可能になった時点で Camera を初期化する。
     */
    fun setupCamera(mapView: MapView) {
        val mapboxMap = mapView.mapboxMap
        val dataSource = MapboxNavigationViewportDataSource(mapboxMap)

        dataSource.options.followingFrameOptions.apply {
            bearingSmoothing.enabled = false
            frameGeometryAfterManeuver.enabled = false
            pitchNearManeuvers.enabled = false
        }

        viewportDataSource = dataSource

        val camera = NavigationCamera(
            mapboxMap,
            mapView.camera,
            dataSource,
        )
        navigationCamera = camera

        mapView.camera.addCameraAnimationsLifecycleListener(
            NavigationBasicGesturesHandler(camera),
        )

        camera.registerNavigationCameraStateChangeObserver { state ->
            _cameraState.value = state
        }
    }

    /**
     * Camera リソースを破棄する。
     */
    fun teardownCamera() {
        navigationCamera = null
        viewportDataSource = null
    }

    /**
     * ルート変更時に ViewportDataSource を更新する。
     */
    fun onRouteChanged(route: NavigationRoute?) {
        if (route != null) {
            viewportDataSource?.onRouteChanged(route)
        } else {
            viewportDataSource?.clearRouteData()
        }
        viewportDataSource?.evaluate()
    }

    /**
     * RouteProgress 変更時に ViewportDataSource を更新する。
     */
    fun onRouteProgressChanged(routeProgress: com.mapbox.navigation.base.trip.model.RouteProgress) {
        viewportDataSource?.onRouteProgressChanged(routeProgress)
        viewportDataSource?.evaluate()
    }

    /**
     * カメラを Following モードに遷移する。
     *
     * @param pitch3D true なら 3D パースペクティブ（45°）、false なら 2D 俯瞰（0°、北固定）
     */
    fun requestCameraFollowing(pitch3D: Boolean = _isFollowing3D.value) {
        _isFollowing3D.value = pitch3D
        viewportDataSource?.followingPitchPropertyOverride(if (pitch3D) FOLLOWING_3D_PITCH else FOLLOWING_2D_PITCH)
        viewportDataSource?.evaluate()
        navigationCamera?.requestNavigationCameraToFollowing()
    }

    /**
     * カメラを Overview モードに遷移する（ルート全体表示）。
     */
    fun requestCameraOverview(maxDurationMs: Long = DEFAULT_OVERVIEW_DURATION_MS) {
        val transitionOptions = NavigationCameraTransitionOptions.Builder()
            .maxDuration(maxDurationMs)
            .build()
        navigationCamera?.requestNavigationCameraToOverview(transitionOptions)
    }

    /**
     * カメラを Idle モードに遷移する。
     */
    fun requestCameraIdle() {
        navigationCamera?.requestNavigationCameraToIdle()
    }

    /**
     * コンパスをトグルする（3D ↔ 2D）。
     */
    fun toggleCompass() {
        requestCameraFollowing(pitch3D = !_isFollowing3D.value)
    }

    /**
     * ナビゲーション用の Following パディングを設定する。
     * 自車位置を画面手前（下部）に配置するため、上方に大きめの余白を設定する。
     */
    fun applyNavigationPadding(followingPadding: EdgeInsets, overviewPadding: EdgeInsets) {
        viewportDataSource?.followingPadding = followingPadding
        viewportDataSource?.overviewPadding = overviewPadding
        viewportDataSource?.evaluate()
    }

    /**
     * パディングをデフォルトに戻す。
     */
    fun clearNavigationPadding() {
        viewportDataSource?.followingPadding = EdgeInsets(0.0, 0.0, 0.0, 0.0)
        viewportDataSource?.overviewPadding = EdgeInsets(0.0, 0.0, 0.0, 0.0)
        viewportDataSource?.followingPitchPropertyOverride(null)
        viewportDataSource?.evaluate()
    }

    companion object {
        private const val DEFAULT_OVERVIEW_DURATION_MS = 500L
        private const val FOLLOWING_3D_PITCH = 45.0
        private const val FOLLOWING_2D_PITCH = 0.0
    }
}
