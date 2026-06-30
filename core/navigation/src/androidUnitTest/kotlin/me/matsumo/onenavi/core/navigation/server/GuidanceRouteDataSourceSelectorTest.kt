package me.matsumo.onenavi.core.navigation.server

import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.test.runTest
import me.matsumo.onenavi.core.datasource.RouteDataSource
import me.matsumo.onenavi.core.model.RouteDetail
import me.matsumo.onenavi.core.model.RouteItem
import me.matsumo.onenavi.core.model.RoutePoint
import me.matsumo.onenavi.core.model.RouteResult
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [GuidanceRouteDataSourceSelector] の rollback 選択テスト。
 */
class GuidanceRouteDataSourceSelectorTest {

    @Test
    fun `runtime トグルが OFF なら既存 source を使う`() = runTest {
        val existingSource = FakeRouteDataSource(routeId = "existing-route")
        val serverSource = FakeRouteDataSource(routeId = "server-route")
        val selector = GuidanceRouteDataSourceSelector(
            existingSource = existingSource,
            serverSource = serverSource,
            providerConfig = GuidanceProviderConfig(),
            apiConfig = GuidanceApiConfig(baseUrl = "https://route.example.test"),
            serverRouteEnabledProvider = { false },
        )

        val routes = selector.searchRoutes(
            originLatitude = 35.0,
            originLongitude = 139.0,
            destinationLatitude = 35.01,
            destinationLongitude = 139.01,
            intermediateWaypoints = emptyList(),
            originDirectionDegrees = null,
        ).getOrThrow()

        assertEquals("existing-route", routes.single().detail.id)
        assertEquals(1, existingSource.callCount)
        assertEquals(0, serverSource.callCount)
    }

    @Test
    fun `S1 かつ base URL nonblank かつ runtime トグル ON のときだけ server source を使う`() = runTest {
        val existingSource = FakeRouteDataSource(routeId = "existing-route")
        val serverSource = FakeRouteDataSource(routeId = "server-route")
        val selector = GuidanceRouteDataSourceSelector(
            existingSource = existingSource,
            serverSource = serverSource,
            providerConfig = GuidanceProviderConfig(
                stage = GuidanceMigrationStage.S1,
                forceExistingSource = false,
            ),
            apiConfig = GuidanceApiConfig(baseUrl = "https://route.example.test"),
            serverRouteEnabledProvider = { true },
        )

        val routes = selector.searchRoutes(
            originLatitude = 35.0,
            originLongitude = 139.0,
            destinationLatitude = 35.01,
            destinationLongitude = 139.01,
            intermediateWaypoints = emptyList(),
            originDirectionDegrees = null,
        ).getOrThrow()

        assertEquals("server-route", routes.single().detail.id)
        assertEquals(0, existingSource.callCount)
        assertEquals(1, serverSource.callCount)
    }

    @Test
    fun `base URL が空なら runtime トグル ON でも既存 source を使う`() = runTest {
        val existingSource = FakeRouteDataSource(routeId = "existing-route")
        val serverSource = FakeRouteDataSource(routeId = "server-route")
        val selector = GuidanceRouteDataSourceSelector(
            existingSource = existingSource,
            serverSource = serverSource,
            providerConfig = GuidanceProviderConfig(
                stage = GuidanceMigrationStage.S1,
                forceExistingSource = false,
            ),
            apiConfig = GuidanceApiConfig(baseUrl = ""),
            serverRouteEnabledProvider = { true },
        )

        val routes = selector.searchRoutes(
            originLatitude = 35.0,
            originLongitude = 139.0,
            destinationLatitude = 35.01,
            destinationLongitude = 139.01,
            intermediateWaypoints = emptyList(),
            originDirectionDegrees = null,
        ).getOrThrow()

        assertEquals("existing-route", routes.single().detail.id)
        assertEquals(1, existingSource.callCount)
        assertEquals(0, serverSource.callCount)
    }

    @Test
    fun `forceExistingSource の kill-switch は runtime トグル ON でも既存 source を強制する`() = runTest {
        val existingSource = FakeRouteDataSource(routeId = "existing-route")
        val serverSource = FakeRouteDataSource(routeId = "server-route")
        val selector = GuidanceRouteDataSourceSelector(
            existingSource = existingSource,
            serverSource = serverSource,
            providerConfig = GuidanceProviderConfig(
                stage = GuidanceMigrationStage.S1,
                forceExistingSource = true,
            ),
            apiConfig = GuidanceApiConfig(baseUrl = "https://route.example.test"),
            serverRouteEnabledProvider = { true },
        )

        val routes = selector.searchRoutes(
            originLatitude = 35.0,
            originLongitude = 139.0,
            destinationLatitude = 35.01,
            destinationLongitude = 139.01,
            intermediateWaypoints = emptyList(),
            originDirectionDegrees = null,
        ).getOrThrow()

        assertEquals("existing-route", routes.single().detail.id)
        assertEquals(1, existingSource.callCount)
        assertEquals(0, serverSource.callCount)
    }

    /**
     * 呼び出し回数を記録する route data source fake。
     */
    private class FakeRouteDataSource(
        private val routeId: String,
    ) : RouteDataSource {

        var callCount: Int = 0
            private set

        override suspend fun searchRoutes(
            originLatitude: Double,
            originLongitude: Double,
            destinationLatitude: Double,
            destinationLongitude: Double,
            intermediateWaypoints: List<Pair<Double, Double>>,
            originDirectionDegrees: Int?,
        ): Result<List<RouteResult>> {
            callCount += 1
            return Result.success(listOf(routeResult(routeId)))
        }

        private fun routeResult(routeId: String): RouteResult {
            val origin = RoutePoint(
                latitude = 35.0,
                longitude = 139.0,
            )
            val destination = RoutePoint(
                latitude = 35.01,
                longitude = 139.01,
            )
            val geometry = persistentListOf(origin, destination)
            val item = RouteItem(
                durationSeconds = 900.0,
                distanceMeters = 12_345.0,
                geometry = geometry,
                viaRoadNames = persistentListOf(),
                hasTolls = false,
            )
            val detail = RouteDetail(
                id = routeId,
                origin = origin,
                destination = destination,
                intermediateWaypoints = persistentListOf(),
                geometry = geometry,
                distanceMeters = 12_345.0,
                durationSeconds = 900.0,
                steps = persistentListOf(),
            )

            return RouteResult(
                item = item,
                detail = detail,
            )
        }
    }
}
