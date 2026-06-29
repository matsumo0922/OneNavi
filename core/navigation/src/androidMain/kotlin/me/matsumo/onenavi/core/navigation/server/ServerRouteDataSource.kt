package me.matsumo.onenavi.core.navigation.server

import me.matsumo.onenavi.core.datasource.RouteDataSource
import me.matsumo.onenavi.core.model.RouteResult
import me.matsumo.onenavi.core.navigation.extnav.ExtNavRouteRegistry

/**
 * server route API を使う [RouteDataSource] 実装。
 */
internal class ServerRouteDataSource(
    private val apiClient: GuidanceApiClient,
    private val registry: ExtNavRouteRegistry,
    private val mapper: RouteGuidanceMapper = RouteGuidanceMapper(),
) : RouteDataSource {

    override suspend fun searchRoutes(
        originLatitude: Double,
        originLongitude: Double,
        destinationLatitude: Double,
        destinationLongitude: Double,
        intermediateWaypoints: List<Pair<Double, Double>>,
        originDirectionDegrees: Int?,
    ): Result<List<RouteResult>> = runCatching {
        val origin = RouteCoordinateDto(
            latitude = originLatitude,
            longitude = originLongitude,
        )
        val destination = RouteCoordinateDto(
            latitude = destinationLatitude,
            longitude = destinationLongitude,
        )
        val via = intermediateWaypoints.map { waypoint ->
            RouteCoordinateDto(
                latitude = waypoint.first,
                longitude = waypoint.second,
            )
        }
        val request = RoutePlanRequestDto(
            origin = origin,
            destination = destination,
            via = via,
            requestedPriorities = defaultRequestedPriorities(),
        )
        val response = apiClient.route(request).getOrThrow()
        val mappings = mapper.map(
            response = response,
            origin = origin,
            destination = destination,
            intermediateWaypoints = via,
        )

        if (mappings.isEmpty()) {
            error("server route response contained no candidates")
        }

        mappings.map { mapping ->
            registry.put(mapping.payload)
            mapping.routeResult
        }
    }

    /**
     * 既存候補 UI と同じ順序の route priority を返す。
     */
    private fun defaultRequestedPriorities(): List<RoutePriorityDto> =
        listOf(
            RoutePriorityDto.RECOMMENDED,
            RoutePriorityDto.AVOID_CONGESTION,
            RoutePriorityDto.EXPRESS,
            RoutePriorityDto.FREE,
        )
}
