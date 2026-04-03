package me.matsumo.onenavi.feature.home.map

import androidx.compose.runtime.Immutable
import com.mapbox.navigation.base.route.NavigationRoute
import me.matsumo.onenavi.core.datasource.NavigationRouteStore
import me.matsumo.onenavi.core.model.RouteItem
import me.matsumo.onenavi.core.model.RouteResult as CoreRouteResult

/**
 * feature/home 層のルート検索結果。
 * [NavigationRoute] を型安全に保持し、`as? NavigationRoute` キャストを撲滅する。
 *
 * @param item UI 表示用のルート情報
 * @param navigationRoute Mapbox Navigation SDK のルートオブジェクト
 */
@Immutable
data class RouteResult(
    val item: RouteItem,
    val navigationRoute: NavigationRoute,
)

/**
 * core/model の RouteResult から feature 層の型安全な RouteResult に変換する。
 * [routeStore] から [NavigationRoute] を解決できない場合は null を返す。
 */
fun CoreRouteResult.toFeatureRouteResult(routeStore: NavigationRouteStore): RouteResult? {
    val navRoute = routeStore.get(id) ?: return null
    return RouteResult(
        item = item,
        navigationRoute = navRoute,
    )
}
