package me.matsumo.onenavi.core.navigation.extnav

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import me.matsumo.onenavi.core.model.RouteDetail
import me.matsumo.onenavi.core.model.RoutePoint
import me.matsumo.onenavi.core.navigation.newguidance.model.RouteMatchState

/**
 * ルート上の停止地点に対する進捗判定。
 *
 * 経由地通過や目的地到達の判定を、リルート・案内状態更新の双方で同じ基準に揃える。
 */
internal object RouteStopProgress {

    /**
     * まだ到達していない経由地だけを返す。
     *
     * @param route 判定対象のルート
     * @param currentCumulativeMeters 現在地の route geometry 累積距離
     * @param marginMeters 到達済みとみなす余裕距離
     * @return 未到達の経由地
     */
    fun remainingIntermediateWaypoints(
        route: RouteDetail,
        currentCumulativeMeters: Double,
        marginMeters: Double,
    ): ImmutableList<RoutePoint> {
        val intermediateWaypoints = route.intermediateWaypoints
        if (intermediateWaypoints.isEmpty()) return persistentListOf()

        val geometry = route.geometry
        if (geometry.isEmpty()) return intermediateWaypoints

        val cumulativeMeters = RouteGeometryMath.cumulativeMetres(geometry)
        val remainingWaypoints = mutableListOf<RoutePoint>()
        for (waypoint in intermediateWaypoints) {
            val nearestIndex = nearestGeometryIndex(
                geometry = geometry,
                point = waypoint,
            )
            val targetMeters = cumulativeMeters[nearestIndex]
            if (targetMeters > currentCumulativeMeters + marginMeters) {
                remainingWaypoints += waypoint
            }
        }
        return remainingWaypoints.toImmutableList()
    }

    /**
     * 目的地へ到達したかを返す。
     *
     * @param distanceRemainingMeters 目的地までの残距離
     * @param routeMatchState 現在位置と route の一致状態
     * @param thresholdMeters 到達とみなす残距離
     * @return 到達済みなら true
     */
    fun isDestinationReached(
        distanceRemainingMeters: Double,
        routeMatchState: RouteMatchState,
        thresholdMeters: Double,
    ): Boolean {
        if (routeMatchState == RouteMatchState.OFF_ROUTE_CONFIRMED) return false
        return distanceRemainingMeters <= thresholdMeters
    }

    /**
     * 指定点に最も近い geometry 点の index を返す。
     *
     * @param geometry route polyline
     * @param point 探索対象の地点
     * @return 最近傍の geometry index
     */
    private fun nearestGeometryIndex(
        geometry: List<RoutePoint>,
        point: RoutePoint,
    ): Int {
        var bestIndex = 0
        var bestDistanceMeters = Double.MAX_VALUE
        for (index in geometry.indices) {
            val distanceMeters = RouteGeometryMath.haversineMetres(geometry[index], point)
            if (distanceMeters < bestDistanceMeters) {
                bestDistanceMeters = distanceMeters
                bestIndex = index
            }
        }
        return bestIndex
    }
}
