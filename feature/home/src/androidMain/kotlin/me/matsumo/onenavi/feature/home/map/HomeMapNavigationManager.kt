package me.matsumo.onenavi.feature.home.map

import android.location.Location
import com.mapbox.maps.MapView
import com.mapbox.maps.plugin.animation.camera
import com.mapbox.navigation.base.ExperimentalPreviewMapboxNavigationAPI
import com.mapbox.navigation.base.route.NavigationRoute
import com.mapbox.navigation.core.MapboxNavigation
import com.mapbox.navigation.core.lifecycle.MapboxNavigationApp
import com.mapbox.navigation.core.trip.session.LocationMatcherResult
import com.mapbox.navigation.core.trip.session.LocationObserver
import com.mapbox.navigation.core.trip.session.RoutesObserver
import com.mapbox.navigation.ui.maps.camera.NavigationCamera
import com.mapbox.navigation.ui.maps.camera.data.MapboxNavigationViewportDataSource
import com.mapbox.navigation.ui.maps.camera.lifecycle.NavigationBasicGesturesHandler
import com.mapbox.navigation.ui.maps.camera.state.NavigationCameraState
import com.mapbox.navigation.ui.maps.location.NavigationLocationProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Navigation SDK の Observer 管理を集約するクラス。
 * ViewModel と MapView の間に立ち、ルート管理・Camera・Location を一元化する。
 */
@OptIn(ExperimentalPreviewMapboxNavigationAPI::class)
class HomeMapNavigationManager {

    private var mapboxNavigation: MapboxNavigation? = null

    // --- Route 管理 ---

    private val _routes = MutableStateFlow<List<NavigationRoute>>(emptyList())

    /** Navigation SDK に登録されているルート一覧。RoutesObserver から自動更新される。 */
    val routes: StateFlow<List<NavigationRoute>> = _routes.asStateFlow()

    private val _selectedRouteIndex = MutableStateFlow(0)

    /** 現在選択中のルートインデックス。 */
    val selectedRouteIndex: StateFlow<Int> = _selectedRouteIndex.asStateFlow()

    // --- Camera ---

    /** NavigationCamera。MapView セットアップ後に初期化される。 */
    var navigationCamera: NavigationCamera? = null
        private set

    /** ViewportDataSource。ルート・位置に基づくカメラ位置を計算する。 */
    var viewportDataSource: MapboxNavigationViewportDataSource? = null
        private set

    // --- Location ---

    /** Maps SDK の location puck と Navigation SDK の enhanced location を橋渡しする。 */
    val navigationLocationProvider = NavigationLocationProvider()

    private val _enhancedLocation = MutableStateFlow<Location?>(null)

    /** Navigation SDK の enhanced (map-matched) location。 */
    val enhancedLocation: StateFlow<Location?> = _enhancedLocation.asStateFlow()

    // --- Observers ---

    private val routesObserver = RoutesObserver { result ->
        _routes.value = result.navigationRoutes

        // ViewportDataSource にルート情報を通知
        if (result.navigationRoutes.isNotEmpty()) {
            viewportDataSource?.onRouteChanged(result.navigationRoutes.first())
            viewportDataSource?.evaluate()
        } else {
            viewportDataSource?.clearRouteData()
            viewportDataSource?.evaluate()
        }
    }

    private val locationObserver = object : LocationObserver {
        override fun onNewRawLocation(rawLocation: Location) {
            // raw location は使わない
        }

        override fun onNewLocationMatcherResult(locationMatcherResult: LocationMatcherResult) {
            navigationLocationProvider.changePosition(
                locationMatcherResult.enhancedLocation,
                locationMatcherResult.keyPoints,
            )
            _enhancedLocation.value = locationMatcherResult.enhancedLocation

            // ViewportDataSource に位置更新を通知
            viewportDataSource?.onLocationChanged(locationMatcherResult.enhancedLocation)
            viewportDataSource?.evaluate()
        }
    }

    // --- Lifecycle ---

    /**
     * Navigation SDK に接続し、Observer を登録する。
     */
    fun onAttach() {
        val navigation = MapboxNavigationApp.current() ?: return
        mapboxNavigation = navigation
        navigation.registerRoutesObserver(routesObserver)
        navigation.registerLocationObserver(locationObserver)
    }

    /**
     * Navigation SDK から Observer を解除する。
     */
    fun onDetach() {
        mapboxNavigation?.unregisterRoutesObserver(routesObserver)
        mapboxNavigation?.unregisterLocationObserver(locationObserver)
        navigationCamera?.unregisterNavigationCameraStateChangeObserver { }
        mapboxNavigation = null
    }

    // --- Camera セットアップ ---

    /**
     * MapView が利用可能になった時点で Camera を初期化する。
     */
    fun setupCamera(mapView: MapView) {
        val mapboxMap = mapView.mapboxMap
        val dataSource = MapboxNavigationViewportDataSource(mapboxMap)
        viewportDataSource = dataSource

        val camera = NavigationCamera(
            mapboxMap,
            mapView.camera,
            dataSource,
        )
        navigationCamera = camera

        // ユーザーのジェスチャー操作で Idle に自動遷移
        camera.registerNavigationCameraStateChangeObserver { }
        mapView.camera.addCameraAnimationsLifecycleListener(
            NavigationBasicGesturesHandler(camera),
        )
    }

    /**
     * カメラを Following モードに遷移する。
     */
    fun requestCameraFollowing() {
        navigationCamera?.requestNavigationCameraToFollowing()
    }

    /**
     * カメラを Overview モードに遷移する（ルート全体表示）。
     */
    fun requestCameraOverview() {
        navigationCamera?.requestNavigationCameraToOverview()
    }

    /**
     * カメラを Idle モードに遷移する（ユーザー操作）。
     */
    fun requestCameraIdle() {
        navigationCamera?.requestNavigationCameraToIdle()
    }

    /**
     * 現在のカメラ状態を取得する。
     */
    fun getCameraState(): NavigationCameraState {
        return navigationCamera?.state ?: NavigationCameraState.IDLE
    }

    // --- Route 操作 ---

    /**
     * ルートを Navigation SDK に登録する。
     */
    fun setRoutes(navigationRoutes: List<NavigationRoute>) {
        _selectedRouteIndex.value = 0
        mapboxNavigation?.setNavigationRoutes(navigationRoutes)
    }

    /**
     * 選択ルートを切り替える。
     */
    fun selectRoute(index: Int) {
        val current = _routes.value
        if (index !in current.indices) return
        _selectedRouteIndex.value = index
        val reordered = listOf(current[index]) + current.filterIndexed { currentIndex, _ -> currentIndex != index }
        mapboxNavigation?.setNavigationRoutes(reordered)
    }

    /**
     * ルートをクリアする。
     */
    fun clearRoutes() {
        _selectedRouteIndex.value = 0
        mapboxNavigation?.setNavigationRoutes(emptyList())
    }
}
