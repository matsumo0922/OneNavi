package me.matsumo.onenavi.core.datasource

import me.matsumo.onenavi.core.model.RouteResult

interface RouteDataSource {
    /**
     * @param originDirectionDegrees 出発地点での進行方向 (コンパス方位角、北 = 0°・時計回り、0..359)。
     *   リルート時に進行方向沿いの経路を得るため渡す。null なら方向指定なし。
     */
    suspend fun searchRoutes(
        originLatitude: Double,
        originLongitude: Double,
        destinationLatitude: Double,
        destinationLongitude: Double,
        intermediateWaypoints: List<Pair<Double, Double>> = emptyList(),
        originDirectionDegrees: Int? = null,
    ): Result<List<RouteResult>>
}
