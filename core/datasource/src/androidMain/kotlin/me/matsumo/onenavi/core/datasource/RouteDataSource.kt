package me.matsumo.onenavi.core.datasource

import me.matsumo.onenavi.core.model.RouteResult

interface RouteDataSource {
    suspend fun searchRoutes(
        originLatitude: Double,
        originLongitude: Double,
        destinationLatitude: Double,
        destinationLongitude: Double,
        intermediateWaypoints: List<Pair<Double, Double>> = emptyList(),
    ): Result<List<RouteResult>>
}
