package me.matsumo.onenavi.core.model

import androidx.compose.runtime.Immutable

/**
 * ルート上の地点を表す sealed interface。
 * 現在地と任意の地点を型で区別する。
 */
@Immutable
sealed interface RouteWaypoint {
    val latitude: Double
    val longitude: Double

    /** 現在地を出発地として使用する場合の地点 */
    @Immutable
    data class CurrentLocation(
        override val latitude: Double,
        override val longitude: Double,
    ) : RouteWaypoint

    /** 検索結果や手動入力で指定された任意の地点 */
    @Immutable
    data class Place(
        val name: String,
        override val latitude: Double,
        override val longitude: Double,
    ) : RouteWaypoint
}
