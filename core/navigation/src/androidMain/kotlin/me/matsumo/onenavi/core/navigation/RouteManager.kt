package me.matsumo.onenavi.core.navigation

import com.mapbox.navigation.base.ExperimentalPreviewMapboxNavigationAPI
import com.mapbox.navigation.base.route.NavigationRoute
import com.mapbox.navigation.core.MapboxNavigation
import com.mapbox.navigation.core.directions.session.RoutesObserver
import com.mapbox.navigation.core.lifecycle.MapboxNavigationApp
import com.mapbox.navigation.core.lifecycle.MapboxNavigationObserver
import com.mapbox.navigation.core.routealternatives.AlternativeRouteMetadata
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ルートの保持・選択・RoutesObserver による一元受信を管理するクラス。
 * RoutesObserver を source of truth とし、ルート変更（初回設定・リルート・トラフィックリフレッシュ・代替ルート探索）を
 * 全てこのクラス経由で受信する。
 *
 * RouteLineApi / RouteLineView は MapView の Style が必要なため、UI 層に残す。
 * このクラスはデータ管理のみを担当する。
 */
@OptIn(ExperimentalPreviewMapboxNavigationAPI::class)
class RouteManager {

    private var mapboxNavigation: MapboxNavigation? = null

    private val _routes = MutableStateFlow<List<NavigationRoute>>(emptyList())

    /** 現在のルート一覧（プライマリ + 代替ルート）。 */
    val routes: StateFlow<List<NavigationRoute>> = _routes.asStateFlow()

    private val _alternativesMetadata = MutableStateFlow<List<AlternativeRouteMetadata>>(emptyList())

    private var lastRouteIds: List<String> = emptyList()

    private val routesObserver = RoutesObserver { result ->
        val newRoutes = result.navigationRoutes
        val newIds = newRoutes.map { it.id }

        if (newIds != lastRouteIds) {
            lastRouteIds = newIds
            _routes.value = newRoutes
            _alternativesMetadata.value = mapboxNavigation
                ?.getAlternativeMetadataFor(newRoutes)
                .orEmpty()
        }
    }

    private val navigationObserver = object : MapboxNavigationObserver {
        override fun onAttached(mapboxNavigation: MapboxNavigation) {
            this@RouteManager.mapboxNavigation = mapboxNavigation
            mapboxNavigation.registerRoutesObserver(routesObserver)
        }

        override fun onDetached(mapboxNavigation: MapboxNavigation) {
            mapboxNavigation.unregisterRoutesObserver(routesObserver)
            this@RouteManager.mapboxNavigation = null
        }
    }

    fun register() {
        MapboxNavigationApp.registerObserver(navigationObserver)
    }

    fun unregister() {
        MapboxNavigationApp.unregisterObserver(navigationObserver)
    }

    /**
     * ルートを Navigation SDK に登録する。
     * RoutesObserver 経由で StateFlow が自動更新される。
     */
    fun setRoutes(navigationRoutes: List<NavigationRoute>) {
        mapboxNavigation?.setNavigationRoutes(navigationRoutes)
    }

    /**
     * 指定ルートを先頭にした並び順を返す。
     * Mapbox Navigation SDK は先頭のルートをプライマリとして描画する。
     */
    private fun reorderedRoutes(primaryRouteId: String): List<NavigationRoute>? {
        val current = _routes.value
        val primaryRoute = current.firstOrNull { it.id == primaryRouteId } ?: return null

        return buildList {
            add(primaryRoute)
            current.forEach { route ->
                if (route.id != primaryRouteId) add(route)
            }
        }
    }

    /**
     * 選択ルートを切り替える。
     */
    fun selectRoute(routeId: String) {
        if (_routes.value.firstOrNull()?.id == routeId) return

        reorderedRoutes(routeId)?.let { routes ->
            mapboxNavigation?.setNavigationRoutes(routes)
        }
    }

    /**
     * ルートをクリアする。
     */
    fun clearRoutes() {
        lastRouteIds = emptyList()
        _routes.value = emptyList()
        _alternativesMetadata.value = emptyList()
        mapboxNavigation?.setNavigationRoutes(emptyList())
    }
}
