package me.matsumo.onenavi.core.navigation.newguidance

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import me.matsumo.onenavi.core.datasource.RouteDataSource
import me.matsumo.onenavi.core.model.RouteResult
import me.matsumo.onenavi.core.navigation.newguidance.model.GuidanceState
import me.matsumo.onenavi.core.navigation.newguidance.model.RoutesApiWaypoint
import me.matsumo.onenavi.core.repository.RouteRepository
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [NewGuidanceManager] の smoke test。
 *
 * Navigator / RoadSnappedLocationProvider は Navigation SDK が abstract class として配布する
 * ため、mockk なしではフルカバーが難しい。spec/24 §12.2 に書いた chunk 切替・リルート・到達の
 * state 遷移テストは実機検証 (spec/24 §12.4) に委ねる方針。
 *
 * 本テストでは Navigator を渡さなくても呼べる範囲だけを保証する:
 * - 初期状態 [GuidanceState.Idle]
 * - 未開始の `stopGuidance` が idempotent
 * - [NewGuidanceManager.release] 後にスコープが破棄されること (例外を出さないこと)
 */
class NewGuidanceManagerTest {

    private val testDispatcher = StandardTestDispatcher()

    private val manager = NewGuidanceManager(
        routeRepository = RouteRepository(routeDataSource = NoOpRouteDataSource()),
        extNavRouteRefiner = ExtNavRouteRefiner(routesApiClient = NoOpRoutesApiClient()),
        dispatcher = testDispatcher,
    )

    @AfterTest
    fun teardown() {
        manager.release()
    }

    @Test
    fun `初期状態は Idle`() {
        assertEquals(GuidanceState.Idle, manager.state.value)
    }

    @Test
    fun `Navigator なしで stopGuidance を呼んでも例外にならず Idle を維持する`() = runTest {
        manager.stopGuidance()

        assertEquals(GuidanceState.Idle, manager.state.value)
    }

    @Test
    fun `release は二重呼び出しでも例外にならない`() {
        manager.release()
        manager.release()

        // release 後は scope が cancel されているが state は Idle のまま参照可能
        assertEquals(GuidanceState.Idle, manager.state.value)
    }
}

/** 何も返さない RouteDataSource。Navigator なしの smoke test では searchRoutes は呼ばれない。 */
private class NoOpRouteDataSource : RouteDataSource {
    override suspend fun searchRoutes(
        originLatitude: Double,
        originLongitude: Double,
        destinationLatitude: Double,
        destinationLongitude: Double,
        intermediateWaypoints: List<Pair<Double, Double>>,
    ): Result<List<RouteResult>> = Result.success(emptyList())
}

/** Smoke test では呼ばれないので失敗を返すだけの RoutesApiClient。 */
private class NoOpRoutesApiClient : RoutesApiClient {
    override suspend fun computeRoute(
        chunk: List<RoutesApiWaypoint>,
        useVia: Boolean,
    ): Result<RoutesApiResponse> = Result.failure(IllegalStateException("not used in smoke test"))
}
