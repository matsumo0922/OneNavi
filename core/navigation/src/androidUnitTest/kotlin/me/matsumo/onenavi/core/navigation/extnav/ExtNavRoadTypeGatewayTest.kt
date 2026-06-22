package me.matsumo.onenavi.core.navigation.extnav

import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.test.runTest
import me.matsumo.drive.supporter.api.core.model.Coord
import me.matsumo.drive.supporter.api.core.result.ApiResult
import me.matsumo.drive.supporter.api.roadtype.domain.CarRoadType
import me.matsumo.drive.supporter.api.roadtype.domain.RoadTypeItem
import me.matsumo.drive.supporter.api.roadtype.domain.RoadTypeResult
import me.matsumo.onenavi.core.model.RoadClass
import me.matsumo.onenavi.core.model.RoutePoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [ExtNavRoadTypeGateway] の道路種別変換テスト。
 */
class ExtNavRoadTypeGatewayTest {

    @Test
    fun `高速道路の候補は HIGHWAY に変換する`() = runTest {
        val backend = FakeRoadTypeGatewayBackend().apply {
            fetchResult = ApiResult.Success(roadTypeResult(CarRoadType.Express))
        }
        val gateway = ExtNavRoadTypeGateway(backend)

        val roadClass = gateway.fetchRoadClass(SAMPLE_POINT).getOrThrow()

        assertEquals(RoadClass.HIGHWAY, roadClass)
    }

    @Test
    fun `一般道の候補は ORDINARY に変換する`() = runTest {
        val backend = FakeRoadTypeGatewayBackend().apply {
            fetchResult = ApiResult.Success(roadTypeResult(CarRoadType.Ordinary))
        }
        val gateway = ExtNavRoadTypeGateway(backend)

        val roadClass = gateway.fetchRoadClass(SAMPLE_POINT).getOrThrow()

        assertEquals(RoadClass.ORDINARY, roadClass)
    }

    @Test
    fun `候補が空なら道路種別を確定しない`() = runTest {
        val backend = FakeRoadTypeGatewayBackend().apply {
            fetchResult = ApiResult.Success(RoadTypeResult(items = persistentListOf()))
        }
        val gateway = ExtNavRoadTypeGateway(backend)

        val roadClass = gateway.fetchRoadClass(SAMPLE_POINT).getOrThrow()

        assertNull(roadClass)
    }

    @Test
    fun `Unknown の候補なら道路種別を確定しない`() = runTest {
        val backend = FakeRoadTypeGatewayBackend().apply {
            fetchResult = ApiResult.Success(roadTypeResult(CarRoadType.Unknown))
        }
        val gateway = ExtNavRoadTypeGateway(backend)

        val roadClass = gateway.fetchRoadClass(SAMPLE_POINT).getOrThrow()

        assertNull(roadClass)
    }

    private fun roadTypeResult(roadType: CarRoadType): RoadTypeResult =
        RoadTypeResult(
            items = persistentListOf(
                RoadTypeItem(
                    rawRoadType = roadType.id,
                    roadType = roadType,
                    isTunnel = false,
                ),
            ),
        )

    /**
     * テスト用の固定値。
     */
    private companion object {
        /** 道路種別 API に渡す任意の座標。 */
        val SAMPLE_POINT = RoutePoint(latitude = 35.0, longitude = 139.0)
    }
}

/**
 * [ExtNavRoadTypeGatewayBackend] の fake。
 */
private class FakeRoadTypeGatewayBackend : ExtNavRoadTypeGatewayBackend {

    var fetchResult: ApiResult<RoadTypeResult> = ApiResult.Success(
        RoadTypeResult(items = persistentListOf()),
    )

    override suspend fun ensureSignedIn(): Result<Unit> =
        Result.success(Unit)

    override suspend fun fetchRoadType(coord: Coord): ApiResult<RoadTypeResult> =
        fetchResult
}
