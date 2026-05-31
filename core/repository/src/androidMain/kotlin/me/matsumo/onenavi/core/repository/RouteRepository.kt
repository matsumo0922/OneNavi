package me.matsumo.onenavi.core.repository

import me.matsumo.onenavi.core.datasource.RouteDataSource
import me.matsumo.onenavi.core.model.RouteResult

class RouteRepository(
    private val routeDataSource: RouteDataSource,
) {
    suspend fun searchRoutes(
        originLatitude: Double,
        originLongitude: Double,
        destinationLatitude: Double,
        destinationLongitude: Double,
        intermediateWaypoints: List<Pair<Double, Double>> = emptyList(),
        originDirectionDegrees: Int? = null,
    ): Result<List<RouteResult>> {
        return routeDataSource.searchRoutes(
            originLatitude = originLatitude,
            originLongitude = originLongitude,
            destinationLatitude = destinationLatitude,
            destinationLongitude = destinationLongitude,
            intermediateWaypoints = intermediateWaypoints,
            originDirectionDegrees = originDirectionDegrees,
        )
    }
}
