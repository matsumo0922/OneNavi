package me.matsumo.onenavi.feature.home.map

import androidx.compose.runtime.Immutable
import me.matsumo.onenavi.core.model.GoogleRoute
import me.matsumo.onenavi.core.model.RouteItem
import me.matsumo.onenavi.core.model.RouteResult as CoreRouteResult

/**
 * feature/home 層のルート検索結果。
 * [GoogleRoute] を型安全に保持し、`as? GoogleRoute` キャストを撲滅する。
 *
 * @param item UI 表示用のルート情報
 * @param googleRoute Google Routes API / Navigation SDK へ渡すルート情報
 */
@Immutable
data class RouteResult(
    val item: RouteItem,
    val googleRoute: GoogleRoute,
)

/**
 * core/model の RouteResult から feature 層の型安全な RouteResult に変換する。
 * [CoreRouteResult.platformRoute] が [GoogleRoute] でない場合は null を返す。
 */
fun CoreRouteResult.toFeatureRouteResult(): RouteResult? {
    val navRoute = platformRoute as? GoogleRoute ?: return null
    return RouteResult(
        item = item,
        googleRoute = navRoute,
    )
}
