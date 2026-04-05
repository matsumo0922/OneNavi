package me.matsumo.onenavi.feature.home.map.state

import androidx.compose.runtime.Immutable

/**
 * HomeMap 画面上のオーバーレイ状態。
 * screenState と直交して管理される。
 */
@Immutable
sealed interface HomeMapOverlayState {

    /** オーバーレイなし */
    data object None : HomeMapOverlayState

    /** waypoint 検索画面を表示中 */
    @Immutable
    data class WaypointSearch(
        val index: Int,
        val initialQuery: String?,
    ) : HomeMapOverlayState
}
