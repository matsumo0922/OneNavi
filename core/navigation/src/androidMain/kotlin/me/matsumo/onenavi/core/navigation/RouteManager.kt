package me.matsumo.onenavi.core.navigation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.matsumo.onenavi.core.model.GoogleRoute

/**
 * Google ルートの保持・選択を管理するクラス。
 */
class RouteManager {

    private val _routes = MutableStateFlow<List<GoogleRoute>>(emptyList())

    /** 現在のルート一覧（プライマリ + 代替ルート）。 */
    val routes: StateFlow<List<GoogleRoute>> = _routes.asStateFlow()

    fun register() {
        // no-op
    }

    fun unregister() {
        // no-op
    }

    /**
     * ルートを登録する。先頭がプライマリルート。
     */
    fun setRoutes(routes: List<GoogleRoute>) {
        _routes.value = routes
    }

    /**
     * 選択ルートを先頭へ並べ替える。
     */
    fun selectRoute(routeId: String) {
        val current = _routes.value
        if (current.firstOrNull()?.id == routeId) return

        val primaryRoute = current.firstOrNull { it.id == routeId } ?: return
        _routes.value = buildList {
            add(primaryRoute)
            current.forEach { route ->
                if (route.id != routeId) add(route)
            }
        }
    }

    /**
     * ルートをクリアする。
     */
    fun clearRoutes() {
        _routes.value = emptyList()
    }
}
