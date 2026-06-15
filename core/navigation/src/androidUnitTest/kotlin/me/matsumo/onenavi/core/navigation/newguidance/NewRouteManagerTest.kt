package me.matsumo.onenavi.core.navigation.newguidance

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.matsumo.onenavi.core.datasource.RouteDataSource
import me.matsumo.onenavi.core.model.RouteDetail
import me.matsumo.onenavi.core.model.RouteItem
import me.matsumo.onenavi.core.model.RoutePoint
import me.matsumo.onenavi.core.model.RouteResult
import me.matsumo.onenavi.core.model.RouteWaypoint
import me.matsumo.onenavi.core.navigation.newguidance.model.RoutePreviewState
import me.matsumo.onenavi.core.repository.RouteRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [NewRouteManager] の状態遷移テスト。`RouteDataSource` を fake に差し替えて検証する。
 */
class NewRouteManagerTest {

    private val fakeDataSource = FakeRouteDataSource()
    private val manager = NewRouteManager(
        routeRepository = RouteRepository(routeDataSource = fakeDataSource),
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
        fakeDataSource.nextResult = Result.success(listOf(buildRouteResult()))

        val result = manager.searchRoutes(waypoints = defaultWaypoints)

        assertIs<RoutePreviewState.Ready>(result)
        val state = assertIs<RoutePreviewState.Ready>(manager.state.value)
        assertEquals(1, state.routes.size)
        assertEquals(0, state.selectedIndex)
        assertEquals(origin, state.routes[0].origin)
        assertEquals(destination, state.routes[0].destination)
        assertEquals(defaultWaypoints.toImmutableList(), state.routes[0].routeWaypoints)
    }

    @Test
    fun `intermediate waypoint は repository に渡される`() = runTest {
        fakeDataSource.nextResult = Result.success(listOf(buildRouteResult()))
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
        val state = assertIs<RoutePreviewState.Ready>(manager.state.value)
        assertEquals(
            listOf(originWaypoint, intermediate, destinationWaypoint).toImmutableList(),
            state.selectedRoute.routeWaypoints,
        )
    }

    @Test
    fun `searchRoutePreview は routeWaypoints を保持する`() = runTest {
        fakeDataSource.nextResult = Result.success(listOf(buildRouteResult()))

        val state = manager.searchRoutePreview(
            origin = origin,
            destination = destination,
            routeWaypoints = defaultWaypoints,
        )

        val ready = assertIs<RoutePreviewState.Ready>(state)
        assertEquals(defaultWaypoints.toImmutableList(), ready.selectedRoute.routeWaypoints)
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

        val result = manager.searchRoutes(waypoints = defaultWaypoints)

        assertIs<RoutePreviewState.Failed>(result)
        val state = assertIs<RoutePreviewState.Failed>(manager.state.value)
        assertSame(cause, state.error)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `古い searchRoutes は新しい探索に追い越されたら state を更新せず null を返す`() = runTest {
        val firstResult = CompletableDeferred<Result<List<RouteResult>>>()
        val secondResult = CompletableDeferred<Result<List<RouteResult>>>()
        val latestDestination = RouteWaypoint.Place(
            name = "latest",
            latitude = 36.0,
            longitude = 140.0,
        )
        val latestWaypoints = listOf(originWaypoint, latestDestination)
        fakeDataSource.enqueueResult(firstResult)
        fakeDataSource.enqueueResult(secondResult)

        val firstSearch = async {
            manager.searchRoutes(waypoints = defaultWaypoints)
        }
        runCurrent()
        val secondSearch = async {
            manager.searchRoutes(waypoints = latestWaypoints)
        }
        runCurrent()

        secondResult.complete(Result.success(listOf(buildRouteResult())))
        val secondState = assertIs<RoutePreviewState.Ready>(secondSearch.await())
        firstResult.complete(Result.success(listOf(buildRouteResult())))

        assertNull(firstSearch.await())
        assertEquals(latestWaypoints.toImmutableList(), secondState.selectedRoute.routeWaypoints)
        val managerState = assertIs<RoutePreviewState.Ready>(manager.state.value)
        assertEquals(latestWaypoints.toImmutableList(), managerState.selectedRoute.routeWaypoints)
    }

    @Test
    fun `searchRoutes は CancellationException を Failed にせず再送出する`() = runTest {
        val cancellation = CancellationException("cancelled")
        fakeDataSource.nextResult = Result.failure(cancellation)

        val thrown = assertFailsWith<CancellationException> {
            manager.searchRoutes(waypoints = defaultWaypoints)
        }

        assertSame(cancellation, thrown)
        assertIs<RoutePreviewState.Searching>(manager.state.value)
    }

    @Test
    fun `searchRoutePreview は CancellationException を再送出する`() = runTest {
        val cancellation = CancellationException("cancelled")
        fakeDataSource.nextResult = Result.failure(cancellation)

        val thrown = assertFailsWith<CancellationException> {
            manager.searchRoutePreview(
                origin = origin,
                destination = destination,
            )
        }

        assertSame(cancellation, thrown)
    }

    @Test
    fun `selectRoute で selectedIndex が更新される`() = runTest {
        fakeDataSource.nextResult = Result.success(
            listOf(buildRouteResult(), buildRouteResult()),
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
        fakeDataSource.nextResult = Result.success(listOf(buildRouteResult()))
        manager.searchRoutes(waypoints = defaultWaypoints)

        assertFailsWith<IllegalArgumentException> {
            manager.selectRoute(index = 99)
        }
    }

    @Test
    fun `reset で Idle に戻る`() = runTest {
        fakeDataSource.nextResult = Result.success(listOf(buildRouteResult()))
        manager.searchRoutes(waypoints = defaultWaypoints)
        assertTrue(manager.state.value is RoutePreviewState.Ready)

        manager.reset()

        assertEquals(RoutePreviewState.Idle, manager.state.value)
    }

    private fun buildRouteResult(): RouteResult {
        val geometry = listOf(origin, destination).toImmutableList()
        return RouteResult(
            item = RouteItem(
                durationSeconds = 600.0,
                distanceMeters = 5_000.0,
                geometry = geometry,
                viaRoadNames = persistentListOf(),
                hasTolls = false,
                tollFee = null,
            ),
            detail = RouteDetail(
                id = "route-0",
                origin = origin,
                destination = destination,
                intermediateWaypoints = persistentListOf(),
                geometry = geometry,
                distanceMeters = 5_000.0,
                durationSeconds = 600.0,
                steps = persistentListOf(),
            ),
        )
    }
}

/**
 * [RouteDataSource] の fake。次回 `searchRoutes` で返す Result を [nextResult] にセットしておく。
 */
private class FakeRouteDataSource : RouteDataSource {

    var nextResult: Result<List<RouteResult>> = Result.success(emptyList())
    var lastIntermediateWaypoints: List<Pair<Double, Double>> = emptyList()
        private set
    var lastOriginDirectionDegrees: Int? = null
        private set
    private val queuedResults = ArrayDeque<CompletableDeferred<Result<List<RouteResult>>>>()

    fun enqueueResult(result: CompletableDeferred<Result<List<RouteResult>>>) {
        queuedResults += result
    }

    override suspend fun searchRoutes(
        originLatitude: Double,
        originLongitude: Double,
        destinationLatitude: Double,
        destinationLongitude: Double,
        intermediateWaypoints: List<Pair<Double, Double>>,
        originDirectionDegrees: Int?,
    ): Result<List<RouteResult>> {
        lastIntermediateWaypoints = intermediateWaypoints
        lastOriginDirectionDegrees = originDirectionDegrees
        val queuedResult = if (queuedResults.isEmpty()) {
            null
        } else {
            queuedResults.removeFirst()
        }
        if (queuedResult != null) {
            return queuedResult.await()
        }

        return nextResult
    }
}
