package me.matsumo.onenavi.core.navigation.newguidance.model

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import me.matsumo.onenavi.core.model.RoutePoint

/**
 * 1 chunk = 1 回分の Routes API 呼び出し結果。
 *
 * Routes API v2 は intermediate waypoint 25 個までのハードキャップがあるため、
 * 長距離ルートでは chunk 分割して順に呼ぶ必要がある (spec/23 §7.1)。
 * waypoints / routeToken / polyline はセットで保持する — Navigator.setDestinations
 * 呼び出し時に同時に必要になるため。
 *
 * @param waypoints この chunk を構成する waypoint 列。最初が origin、最後が destination
 * @param routeToken Routes API が発行した route_token。Navigator に渡す
 * @param polyline この chunk が描画する polyline (decode 済み)
 * @param distanceMeters この chunk の総距離
 * @param durationSeconds この chunk の所要時間 (TRAFFIC_UNAWARE 想定)
 */
@Immutable
data class RefinedChunk(
    val waypoints: ImmutableList<RoutesApiWaypoint>,
    val routeToken: String,
    val polyline: ImmutableList<RoutePoint>,
    val distanceMeters: Int,
    val durationSeconds: Long,
)
