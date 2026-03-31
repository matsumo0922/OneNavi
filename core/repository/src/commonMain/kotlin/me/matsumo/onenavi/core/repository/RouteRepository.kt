package me.matsumo.onenavi.core.repository

import me.matsumo.onenavi.core.datasource.RouteDataSource
import me.matsumo.onenavi.core.model.RouteItem

class RouteRepository(
    private val routeDataSource: RouteDataSource,
) {
    suspend fun searchRoutes(
        originLatitude: Double,
        originLongitude: Double,
        destinationLatitude: Double,
        destinationLongitude: Double,
    ): Result<List<RouteItem>> {
        return routeDataSource.searchRoutes(
            originLatitude = originLatitude,
            originLongitude = originLongitude,
            destinationLatitude = destinationLatitude,
            destinationLongitude = destinationLongitude,
        )
    }
}
