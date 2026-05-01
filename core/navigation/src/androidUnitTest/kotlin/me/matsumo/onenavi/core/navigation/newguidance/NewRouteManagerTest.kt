package me.matsumo.onenavi.core.navigation.newguidance

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.test.runTest
import me.matsumo.onenavi.core.datasource.RouteDataSource
import me.matsumo.onenavi.core.model.RouteItem
import me.matsumo.onenavi.core.model.RoutePoint
import me.matsumo.onenavi.core.model.RouteResult
import me.matsumo.onenavi.core.model.RouteWaypoint
import me.matsumo.onenavi.core.navigation.newguidance.model.RoutePreviewState
import me.matsumo.onenavi.core.navigation.newguidance.model.RoutesApiWaypoint
import me.matsumo.onenavi.core.repository.RouteRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [NewRouteManager] の状態遷移テスト。
 *
 * `RouteDataSource` と `RoutesApiClient` をそれぞれ fake に差し替え、refine 含む全フローを
 * 同期実行する (実 HTTP は呼ばない)。
 */
class NewRouteManagerTest {

    private val fakeDataSource = FakeRouteDataSource()
    private val fakeRoutesApiClient = StubRoutesApiClient()
    private val refiner = ExtNavRouteRefiner(routesApiClient = fakeRoutesApiClient)
    private val manager = NewRouteManager(
        routeRepository = RouteRepository(routeDataSource = fakeDataSource),
        extNavRouteRefiner = refiner,
    )

    private val origin = RoutePoint(latitude = 35.0, longitude = 139.0)
    private val destination = RoutePoint(latitude = 35.5, longitude = 139.5)
    private val originWaypoint = RouteWaypoint.CurrentLocation(
        latitude = origin.latitude,
        longitude = origin.longitude,
    )
    private val destinationWaypoint = RouteWaypoint.Place(
        name = "destination",
        latitude = destination.latitude,
        longitude = destination.longitude,
    )
    private val defaultWaypoints = listOf(originWaypoint, destinationWaypoint)

    @Test
    fun `初期状態は Idle`() {
        assertEquals(RoutePreviewState.Idle, manager.state.value)
    }

    @Test
    fun `searchRoutes 成功で Ready になる`() = runTest {
        fakeDataSource.nextResult = Result.success(
            listOf(buildRouteResult(buildLinearPolyline(pointCount = 5))),
        )

        manager.searchRoutes(waypoints = defaultWaypoints)

        val state = assertIs<RoutePreviewState.Ready>(manager.state.value)
        assertEquals(1, state.routes.size)
        assertEquals(0, state.selectedIndex)
        assertEquals(origin, state.routes[0].origin)
        assertEquals(destination, state.routes[0].destination)
    }

    @Test
    fun `intermediate waypoint は repository に渡される`() = runTest {
        fakeDataSource.nextResult = Result.success(
            listOf(buildRouteResult(buildLinearPolyline(pointCount = 5))),
        )
        val intermediate = RouteWaypoint.Place(
            name = "via",
            latitude = 35.25,
            longitude = 139.25,
        )

        manager.searchRoutes(
            waypoints = listOf(originWaypoint, intermediate, destinationWaypoint),
        )

        assertEquals(
            listOf(intermediate.latitude to intermediate.longitude),
            fakeDataSource.lastIntermediateWaypoints,
        )
    }

    @Test
    fun `waypoints が 2 未満なら例外`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            manager.searchRoutes(waypoints = listOf(originWaypoint))
        }
    }

    @Test
    fun `空ルートが返ったら Failed`() = runTest {
        fakeDataSource.nextResult = Result.success(emptyList())

        manager.searchRoutes(waypoints = defaultWaypoints)

        assertIs<RoutePreviewState.Failed>(manager.state.value)
    }

    @Test
    fun `DataSource が failure を返したら Failed に遷移する`() = runTest {
        val cause = IllegalStateException("offline")
        fakeDataSource.nextResult = Result.failure(cause)

        manager.searchRoutes(waypoints = defaultWaypoints)

        val state = assertIs<RoutePreviewState.Failed>(manager.state.value)
        assertSame(cause, state.error)
    }

    @Test
    fun `selectRoute で selectedIndex が更新される`() = runTest {
        fakeDataSource.nextResult = Result.success(
            listOf(
                buildRouteResult(buildLinearPolyline(pointCount = 4)),
                buildRouteResult(buildLinearPolyline(pointCount = 6)),
            ),
        )
        manager.searchRoutes(waypoints = defaultWaypoints)

        manager.selectRoute(index = 1)

        val state = assertIs<RoutePreviewState.Ready>(manager.state.value)
        assertEquals(1, state.selectedIndex)
        assertSame(state.routes[1], state.selectedRoute)
    }

    @Test
    fun `Ready 以外で selectRoute は何もしない`() {
        manager.selectRoute(index = 0)
        assertEquals(RoutePreviewState.Idle, manager.state.value)
    }

    @Test
    fun `selectRoute 範囲外で例外`() = runTest {
        fakeDataSource.nextResult = Result.success(
            listOf(buildRouteResult(buildLinearPolyline(pointCount = 3))),
        )
        manager.searchRoutes(waypoints = defaultWaypoints)

        assertFailsWith<IllegalArgumentException> {
            manager.selectRoute(index = 99)
        }
    }

    @Test
    fun `reset で Idle に戻る`() = runTest {
        fakeDataSource.nextResult = Result.success(
            listOf(buildRouteResult(buildLinearPolyline(pointCount = 3))),
        )
        manager.searchRoutes(waypoints = defaultWaypoints)
        assertTrue(manager.state.value is RoutePreviewState.Ready)

        manager.reset()

        assertEquals(RoutePreviewState.Idle, manager.state.value)
    }

    private fun buildLinearPolyline(pointCount: Int): List<RoutePoint> =
        (0 until pointCount).map { step ->
            RoutePoint(
                latitude = origin.latitude + (destination.latitude - origin.latitude) * step / (pointCount - 1),
                longitude = origin.longitude + (destination.longitude - origin.longitude) * step / (pointCount - 1),
            )
        }

    private fun buildRouteResult(polyline: List<RoutePoint>): RouteResult = RouteResult(
        item = RouteItem(
            durationSeconds = 600.0,
            distanceMeters = 5_000.0,
            geometry = polyline.toImmutableList(),
            viaRoadNames = persistentListOf(),
            hasTolls = false,
            tollFee = null,
        ),
        platformRoute = null,
    )
}

/**
 * [RouteDataSource] の fake。次回 `searchRoutes` で返す Result を [nextResult] にセットしておく。
 */
private class FakeRouteDataSource : RouteDataSource {

    var nextResult: Result<List<RouteResult>> = Result.success(emptyList())
    var lastIntermediateWaypoints: List<Pair<Double, Double>> = emptyList()
        private set

    override suspend fun searchRoutes(
        originLatitude: Double,
        originLongitude: Double,
        destinationLatitude: Double,
        destinationLongitude: Double,
        intermediateWaypoints: List<Pair<Double, Double>>,
    ): Result<List<RouteResult>> {
        lastIntermediateWaypoints = intermediateWaypoints
        return nextResult
    }
}

/**
 * [RoutesApiClient] の決定的 stub。各 chunk の polyline をそのまま返す。
 */
private class StubRoutesApiClient : RoutesApiClient {

    private var callCount = 0

    override suspend fun computeRoute(
        chunk: List<RoutesApiWaypoint>,
        useVia: Boolean,
    ): Result<RoutesApiResponse> {
        callCount += 1
        return Result.success(
            RoutesApiResponse(
                polyline = chunk.map { it.point },
                routeToken = "stub-token-$callCount",
                distanceMeters = chunk.size * 1_000,
                durationSeconds = chunk.size * 60L,
            ),
        )
    }
}
