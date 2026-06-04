package me.matsumo.onenavi.feature.map.state

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import kotlinx.collections.immutable.ImmutableList
import me.matsumo.onenavi.core.model.RouteWaypoint
import me.matsumo.onenavi.core.model.SearchResultItem
import me.matsumo.onenavi.core.navigation.newguidance.model.RoutePreviewState

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
     * ナビゲーション中の waypoint 追加候補として選択した地点の詳細オーバーレイ。
     *
     * @property place 選択した地点。
     * @property routePreviewState 選択地点を経由地にした仮ルート探索状態。
     */
    @Immutable
    data class AddWaypointSelected(
        val place: SearchResultItem,
        val routePreviewState: RoutePreviewState,
    ) : MapOverlayState

    /**
     * ナビゲーション中の waypoint 追加候補を経由する代替ルート候補オーバーレイ。
     *
     * @property place 選択した地点。
     * @property routePreviewState 選択地点を経由地にした仮ルート探索状態。
     */
    @Immutable
    data class AddWaypointAlternatives(
        val place: SearchResultItem,
        val routePreviewState: RoutePreviewState,
    ) : MapOverlayState

    /**
     * ナビゲーション中の代替ルート候補オーバーレイ。
     *
     * @property routePreviewState 現在地から目的地までの代替ルート探索状態。
     */
    @Immutable
    data class NavigationAlternatives(
        val routePreviewState: RoutePreviewState,
    ) : MapOverlayState

    /**
     * ナビゲーション中の waypoint 並び替えオーバーレイ。
     *
     * @property originWaypoint 固定表示する現在地。
     * @property waypoints 並び替え・削除対象の未通過 waypoint。最後の地点を目的地として扱う。
     * @property routePreviewState 完了時の再探索状態。
     */
    @Immutable
    data class NavigationWaypointEditor(
        val originWaypoint: RouteWaypoint.CurrentLocation,
        val waypoints: ImmutableList<RouteWaypoint.Place>,
        val routePreviewState: RoutePreviewState,
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
