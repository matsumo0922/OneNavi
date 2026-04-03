package me.matsumo.onenavi.core.datasource

import com.mapbox.navigation.base.route.NavigationRoute
import java.util.concurrent.ConcurrentHashMap

/**
 * Android 固有の [NavigationRoute] を routeId で一時保持するストア。
 * shared model には routeId のみを渡し、実体は Android 側で解決する。
 */
class NavigationRouteStore {

    private val routes = ConcurrentHashMap<String, NavigationRoute>()

    fun replace(routeMap: Map<String, NavigationRoute>) {
        routes.clear()
        routes.putAll(routeMap)
    }

    fun get(routeId: String): NavigationRoute? {
        return routes[routeId]
    }

    fun clear() {
        routes.clear()
    }
}
