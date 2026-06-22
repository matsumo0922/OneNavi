package me.matsumo.onenavi.core.navigation.extnav

import me.matsumo.drive.supporter.api.core.model.Coord
import me.matsumo.drive.supporter.api.core.result.ApiFailure
import me.matsumo.drive.supporter.api.core.result.ApiResult
import me.matsumo.drive.supporter.api.roadtype.domain.CarRoadType
import me.matsumo.drive.supporter.api.roadtype.domain.RoadTypeResult
import me.matsumo.onenavi.core.model.RoadClass
import me.matsumo.onenavi.core.model.RoutePoint

/**
 * 外部API ライブラリの座標道路種別 API を OneNavi の道路種別へ射影する gateway。
 */
class ExtNavRoadTypeGateway internal constructor(
    private val backend: ExtNavRoadTypeGatewayBackend,
) {
    constructor(
        clientProvider: ExtNavClientProvider,
        authGateway: ExtNavAuthGateway,
    ) : this(
        backend = DefaultExtNavRoadTypeGatewayBackend(
            clientProvider = clientProvider,
            authGateway = authGateway,
        ),
    )

    /**
     * [point] の道路種別を取得する。判定不能な結果は null として扱う。
     */
    suspend fun fetchRoadClass(point: RoutePoint): Result<RoadClass?> {
        backend.ensureSignedIn().getOrElse { cause ->
            return Result.failure(cause)
        }

        return when (val result = backend.fetchRoadType(point.toCoord())) {
            is ApiResult.Success -> Result.success(result.value.toRoadClassOrNull())
            is ApiResult.Failure -> Result.failure(ExtNavRoadTypeApiException(result.failure))
        }
    }
}

/**
 * [ExtNavRoadTypeGateway] が利用する認証済み道路種別取得 backend。
 */
internal interface ExtNavRoadTypeGatewayBackend {
    suspend fun ensureSignedIn(): Result<Unit>

    suspend fun fetchRoadType(coord: Coord): ApiResult<RoadTypeResult>
}

/**
 * 外部API ライブラリの client provider / auth gateway を使う既定 backend。
 */
private class DefaultExtNavRoadTypeGatewayBackend(
    private val clientProvider: ExtNavClientProvider,
    private val authGateway: ExtNavAuthGateway,
) : ExtNavRoadTypeGatewayBackend {

    override suspend fun ensureSignedIn(): Result<Unit> =
        authGateway.ensureSignedIn()

    override suspend fun fetchRoadType(coord: Coord): ApiResult<RoadTypeResult> {
        val client = clientProvider.get()
        return client.roadType.fetchRoadType(coord)
    }
}

/**
 * 座標道路種別 API の失敗。
 */
class ExtNavRoadTypeApiException(
    val failure: ApiFailure,
) : Exception("road type api failed: $failure")

private fun RoutePoint.toCoord(): Coord =
    Coord.fromDegrees(latDeg = latitude, lonDeg = longitude)

private fun RoadTypeResult.toRoadClassOrNull(): RoadClass? {
    val roadType = primaryRoadType
    if (roadType == CarRoadType.Unknown) return null

    return if (roadType.isHighway) RoadClass.HIGHWAY else RoadClass.ORDINARY
}
