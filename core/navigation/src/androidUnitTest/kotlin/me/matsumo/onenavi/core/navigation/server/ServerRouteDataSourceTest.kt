package me.matsumo.onenavi.core.navigation.server

import kotlinx.coroutines.test.runTest
import me.matsumo.onenavi.core.navigation.extnav.ExtNavRouteRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * [ServerRouteDataSource] の request と registry 登録テスト。
 */
class ServerRouteDataSourceTest {

    @Test
    fun `既存候補と同じ priority を明示して server route を取得する`() = runTest {
        val apiClient = FakeGuidanceApiClient(response = routePlanResponse())
        val registry = ExtNavRouteRegistry()
        val dataSource = ServerRouteDataSource(
            apiClient = apiClient,
            registry = registry,
        )

        val routes = dataSource.searchRoutes(
            originLatitude = 35.0,
            originLongitude = 139.0,
            destinationLatitude = 35.01,
            destinationLongitude = 139.01,
            intermediateWaypoints = listOf(35.005 to 139.005),
            originDirectionDegrees = 90,
        ).getOrThrow()

        val request = assertNotNull(apiClient.lastRequest)
        val route = routes.single()
        val payload = assertNotNull(registry.get(ROUTE_PACKAGE_ID))

        assertEquals(
            listOf(
                RoutePriorityDto.RECOMMENDED,
                RoutePriorityDto.AVOID_CONGESTION,
                RoutePriorityDto.EXPRESS,
                RoutePriorityDto.FREE,
            ),
            request.requestedPriorities,
        )
        assertEquals(ROUTE_PACKAGE_ID, route.detail.id)
        assertEquals(ROUTE_PACKAGE_ID, payload.id)
        assertEquals(ROUTE_PACKAGE_ID, registry.get(route.detail.id)?.id)
    }

    private fun routePlanResponse(): RoutePlanResponseDto =
        RoutePlanResponseDto(
            candidates = listOf(
                RouteCandidateDto(
                    routePackageId = ROUTE_PACKAGE_ID,
                    priority = RoutePriorityDto.RECOMMENDED,
                    mergedFromPriorities = listOf(RoutePriorityDto.RECOMMENDED),
                    geometry = listOf(
                        RouteCoordinateDto(
                            latitude = 35.0,
                            longitude = 139.0,
                        ),
                        RouteCoordinateDto(
                            latitude = 35.01,
                            longitude = 139.01,
                        ),
                    ),
                    polyline = "encoded-polyline",
                    summary = RouteSummaryDto(
                        durationSeconds = 900,
                        lengthMetres = 12_345,
                    ),
                    guidancePoints = emptyList(),
                    maneuvers = listOf(
                        RouteManeuverDto(
                            id = "maneuver-1",
                            type = RouteManeuverTypeDto.TURN,
                            rawAction = "turn",
                            routeMeasureMetres = 100.0,
                        ),
                    ),
                ),
            ),
        )

    /**
     * 最後に受け取った request を保持する route API fake。
     */
    private class FakeGuidanceApiClient(
        private val response: RoutePlanResponseDto,
    ) : GuidanceApiClient {

        var lastRequest: RoutePlanRequestDto? = null
            private set

        override suspend fun route(request: RoutePlanRequestDto): Result<RoutePlanResponseDto> {
            lastRequest = request
            return Result.success(response)
        }
    }

    private companion object {
        /** server が候補へ付与した route package ID。 */
        const val ROUTE_PACKAGE_ID: String = "server-route-package-1"
    }
}
