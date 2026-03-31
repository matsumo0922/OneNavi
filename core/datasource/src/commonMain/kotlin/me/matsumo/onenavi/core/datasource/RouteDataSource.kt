package me.matsumo.onenavi.core.datasource

import me.matsumo.onenavi.core.model.RouteItem

interface RouteDataSource {
    suspend fun searchRoutes(
        originLatitude: Double,
        originLongitude: Double,
        destinationLatitude: Double,
        destinationLongitude: Double,
    ): Result<List<RouteItem>>
}
