package me.matsumo.onenavi.feature.home.map

import com.mapbox.navigation.base.ExperimentalPreviewMapboxNavigationAPI
import com.mapbox.navigation.base.route.NavigationRoute
import com.mapbox.navigation.core.MapboxNavigation
import com.mapbox.navigation.core.lifecycle.MapboxNavigationApp
import com.mapbox.navigation.core.trip.session.RouteProgressObserver
import com.mapbox.navigation.core.trip.session.RoutesObserver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Navigation SDK の Observer 管理を集約するクラス。
 * ViewModel と MapView の間に立ち、ルート管理・Observer 登録/解除を一元化する。
 */
@OptIn(ExperimentalPreviewMapboxNavigationAPI::class)
class HomeMapNavigationManager {

    private var mapboxNavigation: MapboxNavigation? = null

    private val _routes = MutableStateFlow<List<NavigationRoute>>(emptyList())

    /** Navigation SDK に登録されているルート一覧。RoutesObserver から自動更新される。 */
    val routes: StateFlow<List<NavigationRoute>> = _routes.asStateFlow()

    private val _selectedRouteIndex = MutableStateFlow(0)

    /** 現在選択中のルートインデックス。 */
    val selectedRouteIndex: StateFlow<Int> = _selectedRouteIndex.asStateFlow()

    private val routesObserver = RoutesObserver { result ->
        _routes.value = result.navigationRoutes
    }

    /**
     * Navigation SDK に接続し、Observer を登録する。
     * Composable の DisposableEffect や ViewModel の初期化から呼ぶ。
     */
    fun onAttach() {
        val navigation = MapboxNavigationApp.current() ?: return
        mapboxNavigation = navigation
        navigation.registerRoutesObserver(routesObserver)
    }

    /**
     * Navigation SDK から Observer を解除する。
     * Composable の onDispose や ViewModel の onCleared から呼ぶ。
     */
    fun onDetach() {
        mapboxNavigation?.unregisterRoutesObserver(routesObserver)
        mapboxNavigation = null
    }

    /**
     * ルートを Navigation SDK に登録する。
     * RoutesObserver が自動発火し、[routes] StateFlow が更新される。
     */
    fun setRoutes(navigationRoutes: List<NavigationRoute>) {
        _selectedRouteIndex.value = 0
        mapboxNavigation?.setNavigationRoutes(navigationRoutes)
    }

    /**
     * 選択ルートを切り替える。
     * 選択ルートを先頭に並び替えて SDK に再登録することで、
     * RoutesObserver 経由で route line / callout が自動更新される。
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
