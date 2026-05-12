package me.matsumo.onenavi.feature.home.map

import androidx.compose.runtime.Immutable
import me.matsumo.onenavi.core.model.RouteDetail
import me.matsumo.onenavi.core.model.RouteItem
import me.matsumo.onenavi.core.model.RouteResult as CoreRouteResult

/**
 * feature/home 層のルート検索結果。
 *
 * @param item UI 表示用のルート情報
 * @param routeDetail 案内に渡すルート詳細
 */
@Immutable
data class RouteResult(
    val item: RouteItem,
    val routeDetail: RouteDetail,
)

/**
 * core/model の [CoreRouteResult] を feature 層の [RouteResult] に変換する。
 */
fun CoreRouteResult.toFeatureRouteResult(): RouteResult = RouteResult(
    item = item,
    routeDetail = detail,
)
