package me.matsumo.onenavi.feature.home.map

import com.mapbox.maps.MapView
import com.mapbox.common.location.Location
import com.mapbox.maps.plugin.animation.camera
import com.mapbox.navigation.base.ExperimentalPreviewMapboxNavigationAPI
import com.mapbox.navigation.base.route.NavigationRoute
import com.mapbox.navigation.base.trip.model.RouteProgress
import com.mapbox.navigation.core.MapboxNavigation
import com.mapbox.navigation.core.directions.session.RoutesObserver
import com.mapbox.navigation.core.directions.session.RoutesUpdatedResult
import com.mapbox.navigation.core.lifecycle.MapboxNavigationApp
import com.mapbox.navigation.core.lifecycle.MapboxNavigationObserver
import com.mapbox.navigation.core.routealternatives.AlternativeRouteMetadata
import com.mapbox.navigation.core.trip.session.LocationMatcherResult
import com.mapbox.navigation.core.trip.session.LocationObserver
import com.mapbox.navigation.core.trip.session.RouteProgressObserver
import com.mapbox.navigation.ui.maps.camera.NavigationCamera
import com.mapbox.navigation.ui.maps.camera.data.MapboxNavigationViewportDataSource
import com.mapbox.navigation.ui.maps.camera.lifecycle.NavigationBasicGesturesHandler
import com.mapbox.navigation.ui.maps.camera.state.NavigationCameraState
import com.mapbox.navigation.ui.maps.location.NavigationLocationProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Navigation SDK の管理を集約するクラス。
 * ViewModel と MapView の間に立ち、ルート管理・Camera を一元化する。
 */
@OptIn(ExperimentalPreviewMapboxNavigationAPI::class)
class HomeMapNavigationManager {

    private var mapboxNavigation: MapboxNavigation? = null

    // --- Route 管理 ---

    private val _routes = MutableStateFlow<List<NavigationRoute>>(emptyList())

    /** 現在のルート一覧。 */
    val routes: StateFlow<List<NavigationRoute>> = _routes.asStateFlow()

    private val _alternativeRouteMetadata = MutableStateFlow<List<AlternativeRouteMetadata>>(emptyList())

    /** 現在の代替ルート metadata。route line の重なり表現に使う。 */
    val alternativeRouteMetadata: StateFlow<List<AlternativeRouteMetadata>> =
        _alternativeRouteMetadata.asStateFlow()

    private val _selectedRouteIndex = MutableStateFlow(0)

    /** 現在選択中のルートインデックス。 */
    val selectedRouteIndex: StateFlow<Int> = _selectedRouteIndex.asStateFlow()

    private val _routeProgress = MutableStateFlow<RouteProgress?>(null)

    /** 現在の RouteProgress。traveled route / vanishing route line に使う。 */
    val routeProgress: StateFlow<RouteProgress?> = _routeProgress.asStateFlow()

    private val _enhancedLocation = MutableStateFlow<Location?>(null)

    /** 現在の enhanced location。現在地起点のルート探索や bearing 参照に使う。 */
    val enhancedLocation: StateFlow<Location?> = _enhancedLocation.asStateFlow()

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

    // --- Lifecycle ---

    private val navigationObserver = object : MapboxNavigationObserver {
        override fun onAttached(mapboxNavigation: MapboxNavigation) {
            this@HomeMapNavigationManager.mapboxNavigation = mapboxNavigation
            mapboxNavigation.registerRoutesObserver(routesObserver)
            mapboxNavigation.registerLocationObserver(locationObserver)
            mapboxNavigation.registerRouteProgressObserver(routeProgressObserver)
            mapboxNavigation.startTripSessionWithPermissionCheck()

            if (_routes.value.isNotEmpty()) {
                mapboxNavigation.setNavigationRoutes(_routes.value)
            }
        }

        override fun onDetached(mapboxNavigation: MapboxNavigation) {
            mapboxNavigation.unregisterRoutesObserver(routesObserver)
            mapboxNavigation.unregisterLocationObserver(locationObserver)
            mapboxNavigation.unregisterRouteProgressObserver(routeProgressObserver)
            mapboxNavigation.stopTripSession()
            this@HomeMapNavigationManager.mapboxNavigation = null
        }
    }

    private val routesObserver = RoutesObserver { result: RoutesUpdatedResult ->
        val navigationRoutes = result.navigationRoutes
        _routes.value = navigationRoutes
        _selectedRouteIndex.value = if (navigationRoutes.isEmpty()) -1 else 0
        _alternativeRouteMetadata.value = mapboxNavigation
            ?.getAlternativeMetadataFor(navigationRoutes)
            .orEmpty()

        if (navigationRoutes.isNotEmpty()) {
            viewportDataSource?.onRouteChanged(navigationRoutes.first())
            viewportDataSource?.evaluate()
        } else {
            _routeProgress.value = null
            viewportDataSource?.clearRouteData()
            viewportDataSource?.evaluate()
        }
    }

    private val locationObserver = object : LocationObserver {
        override fun onNewRawLocation(rawLocation: Location) = Unit

        override fun onNewLocationMatcherResult(locationMatcherResult: LocationMatcherResult) {
            val enhancedLocation = locationMatcherResult.enhancedLocation
            _enhancedLocation.value = enhancedLocation
            navigationLocationProvider.changePosition(
                enhancedLocation,
                locationMatcherResult.keyPoints,
            )
            viewportDataSource?.onLocationChanged(enhancedLocation)
            viewportDataSource?.evaluate()
        }
    }

    private val routeProgressObserver = RouteProgressObserver { routeProgress ->
        _routeProgress.value = routeProgress
        viewportDataSource?.onRouteProgressChanged(routeProgress)
        viewportDataSource?.evaluate()
    }

    /**
     * Navigation SDK の Observer を登録する。
     * MapboxNavigation インスタンスが利用可能になった時点で自動コールバックされる。
     */
    fun register() {
        MapboxNavigationApp.registerObserver(navigationObserver)
    }

    /**
     * Navigation SDK の Observer を解除する。
     */
    fun unregister() {
        MapboxNavigationApp.unregisterObserver(navigationObserver)
    }

    // --- Camera セットアップ ---

    /**
     * Camera リソースを破棄する。MapView が dispose された時に呼ぶ。
     */
    fun teardownCamera() {
        navigationCamera = null
        viewportDataSource = null
    }

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
        _routes.value = navigationRoutes
        _selectedRouteIndex.value = if (navigationRoutes.isEmpty()) -1 else 0
        mapboxNavigation?.setNavigationRoutes(navigationRoutes)
    }

    /**
     * 選択ルートを切り替える。
     */
    fun selectRoute(index: Int) {
        val current = _routes.value
        if (index !in current.indices) return
        val reordered = listOf(current[index]) + current.filterIndexed { currentIndex, _ -> currentIndex != index }
        mapboxNavigation?.setNavigationRoutes(reordered)
    }

    /**
     * ルートをクリアする。
     */
    fun clearRoutes() {
        _selectedRouteIndex.value = -1
        _routes.value = emptyList()
        _alternativeRouteMetadata.value = emptyList()
        _routeProgress.value = null
        mapboxNavigation?.setNavigationRoutes(emptyList())
        viewportDataSource?.clearRouteData()
        viewportDataSource?.evaluate()
    }
}
