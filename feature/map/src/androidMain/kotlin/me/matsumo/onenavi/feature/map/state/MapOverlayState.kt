package me.matsumo.onenavi.feature.map.state

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable

/**
 * 地図画面上に重ねて表示されるオーバーレイの状態。
 * 画面遷移 ([MapScreenState]) とは独立しており、オーバーレイ表示中も下層の画面・シートは維持される。
 */
@Immutable
sealed interface MapOverlayState {

    /** オーバーレイ非表示。 */
    @Stable
    data object None : MapOverlayState

    /**
     * waypoint 差し替え用の全画面地点検索オーバーレイ。
     * [waypointIndex] は差し替え対象の waypoint の index。
     */
    @Immutable
    data class WaypointSearch(
        val initialQuery: String?,
        val waypointIndex: Int,
    ) : MapOverlayState
}
