package me.matsumo.onenavi.core.navigation.newguidance.model

import androidx.compose.runtime.Immutable
import me.matsumo.onenavi.core.model.RoutePoint

/**
 * Routes API v2 に渡す 1 件の waypoint。
 *
 * @param point 緯度経度 (WGS84)
 * @param heading 進行方向 (compass bearing 0-359)。null なら指定しない。spec/23 §7.4
 *                に従い polyline 局所接線方向を入れることで snap 誤判定を抑える
 */
@Immutable
data class RoutesApiWaypoint(
    val point: RoutePoint,
    val heading: Int? = null,
)
