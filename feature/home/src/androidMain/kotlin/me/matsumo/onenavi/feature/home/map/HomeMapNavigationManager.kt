package me.matsumo.onenavi.feature.home.map

import com.mapbox.maps.MapView
import com.mapbox.maps.plugin.animation.camera
import com.mapbox.navigation.base.ExperimentalPreviewMapboxNavigationAPI
import com.mapbox.navigation.base.route.NavigationRoute
import com.mapbox.navigation.core.MapboxNavigation
import com.mapbox.navigation.core.lifecycle.MapboxNavigationApp
import com.mapbox.navigation.core.lifecycle.MapboxNavigationObserver
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

    // --- Lifecycle ---

    private val navigationObserver = object : MapboxNavigationObserver {
        override fun onAttached(mapboxNavigation: MapboxNavigation) {
            this@HomeMapNavigationManager.mapboxNavigation = mapboxNavigation
        }

        override fun onDetached(mapboxNavigation: MapboxNavigation) {
            this@HomeMapNavigationManager.mapboxNavigation = null
        }
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
        _selectedRouteIndex.value = 0
        _routes.value = navigationRoutes
        mapboxNavigation?.setNavigationRoutes(navigationRoutes)

        // ViewportDataSource にルート情報を通知
        if (navigationRoutes.isNotEmpty()) {
            viewportDataSource?.onRouteChanged(navigationRoutes.first())
            viewportDataSource?.evaluate()
        } else {
            viewportDataSource?.clearRouteData()
            viewportDataSource?.evaluate()
        }
    }

    /**
     * 選択ルートを切り替える。
     * _routes の順序は変更せず、selectedRouteIndex のみ更新する。
     * Mapbox API には選択ルートを先頭にした並び順で渡す。
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
        _routes.value = emptyList()
        mapboxNavigation?.setNavigationRoutes(emptyList())
        viewportDataSource?.clearRouteData()
        viewportDataSource?.evaluate()
    }
}
