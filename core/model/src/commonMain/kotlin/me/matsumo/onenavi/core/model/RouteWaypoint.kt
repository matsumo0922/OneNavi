package me.matsumo.onenavi.core.model

import androidx.compose.runtime.Immutable

/**
 * ルート上の地点を表す sealed interface。
 * 現在地と任意の地点を型で区別する。
 */
@Immutable
sealed interface RouteWaypoint {
    /** 現在地を出発地として使用する場合の地点 */
    @Immutable
    data class CurrentLocation(
        val latitude: Double,
        val longitude: Double,
    ) : RouteWaypoint

    /** 検索結果や手動入力で指定された任意の地点 */
    @Immutable
    data class Place(
        val name: String,
        val latitude: Double,
        val longitude: Double,
    ) : RouteWaypoint
}
