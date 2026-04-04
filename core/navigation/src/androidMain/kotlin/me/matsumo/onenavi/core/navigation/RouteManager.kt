package me.matsumo.onenavi.core.navigation

import com.mapbox.navigation.base.ExperimentalPreviewMapboxNavigationAPI
import com.mapbox.navigation.base.route.NavigationRoute
import com.mapbox.navigation.core.MapboxNavigation
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

    private val _selectedRouteIndex = MutableStateFlow(0)

    /** 現在選択中のルートインデックス。 */
    val selectedRouteIndex: StateFlow<Int> = _selectedRouteIndex.asStateFlow()

    private val _alternativesMetadata = MutableStateFlow<List<AlternativeRouteMetadata>>(emptyList())

    /** 代替ルートのメタデータ（重複部分の非表示等に使用）。 */
    val alternativesMetadata: StateFlow<List<AlternativeRouteMetadata>> = _alternativesMetadata.asStateFlow()

    private var lastRouteIds: List<String> = emptyList()

    private val routesObserver = object : com.mapbox.navigation.core.directions.session.RoutesObserver {
        override fun onRoutesChanged(result: com.mapbox.navigation.core.directions.session.RoutesUpdatedResult) {
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
        _selectedRouteIndex.value = 0
        mapboxNavigation?.setNavigationRoutes(navigationRoutes)
    }

    /**
     * 選択ルートを先頭にした並び順を返す。
     * Mapbox Navigation SDK は先頭のルートをプライマリとして描画する。
     */
    fun reorderedRoutes(): List<NavigationRoute> {
        val current = _routes.value
        val primaryIndex = _selectedRouteIndex.value
        if (primaryIndex !in current.indices) return current
        return buildList {
            add(current[primaryIndex])
            current.forEachIndexed { index, route ->
                if (index != primaryIndex) add(route)
            }
        }
    }

    /**
     * 選択ルートを切り替える。
     */
    fun selectRoute(index: Int) {
        val current = _routes.value
        if (index !in current.indices) return
        _selectedRouteIndex.value = index
        mapboxNavigation?.setNavigationRoutes(reorderedRoutes())
    }

    /**
     * ルートをクリアする。
     */
    fun clearRoutes() {
        _selectedRouteIndex.value = 0
        lastRouteIds = emptyList()
        mapboxNavigation?.setNavigationRoutes(emptyList())
    }
}
