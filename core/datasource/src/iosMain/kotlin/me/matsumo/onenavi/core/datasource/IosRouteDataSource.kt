package me.matsumo.onenavi.core.datasource

import me.matsumo.onenavi.core.model.RouteItem

/**
 * iOS 向けルート検索データソースのスタブ。
 * 現時点では未実装。
 */
class IosRouteDataSource : RouteDataSource {
    override suspend fun searchRoutes(
        originLatitude: Double,
        originLongitude: Double,
        destinationLatitude: Double,
        destinationLongitude: Double,
    ): Result<List<RouteItem>> {
        return Result.failure(UnsupportedOperationException("Route search is not available on iOS yet"))
    }
}
