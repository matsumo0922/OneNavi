package me.matsumo.onenavi.feature.map.state

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import kotlinx.collections.immutable.ImmutableList
import me.matsumo.onenavi.core.model.SearchResultItem

/**
 * 地図画面上に重ねて表示されるオーバーレイの状態。
 * 画面遷移 ([MapScreenState]) とは独立しており、オーバーレイ表示中も下層の画面・シートは維持される。
 */
@Immutable
sealed interface MapOverlayState {

    /** オーバーレイ非表示。 */
    @Stable
    data object None : MapOverlayState

    /** ナビゲーション中の waypoint 追加用地点検索オーバーレイ。 */
    @Stable
    data object AddWaypointSearch : MapOverlayState

    /**
     * ナビゲーション中の waypoint 追加検索結果オーバーレイ。
     *
     * @property query 検索クエリ。
     * @property results 検索結果一覧。
     */
    @Immutable
    data class AddWaypointSearchResults(
        val query: String,
        val results: ImmutableList<SearchResultItem>,
    ) : MapOverlayState

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
