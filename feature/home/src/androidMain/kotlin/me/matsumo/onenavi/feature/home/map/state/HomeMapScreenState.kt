package me.matsumo.onenavi.feature.home.map.state

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import kotlinx.collections.immutable.ImmutableList
import me.matsumo.onenavi.core.model.RouteWaypoint
import me.matsumo.onenavi.core.model.SearchResultItem
import me.matsumo.onenavi.feature.home.map.RouteResult

/**
 * HomeMap 画面の排他的な画面状態。
 * combine 導出により、複数の raw state から自動計算される。
 */
sealed interface HomeMapScreenState {

    /** 地図ブラウジング中。検索バーとコントロールが表示される。 */
    @Stable
    data object Browsing : HomeMapScreenState

    /** テキスト検索結果の一覧を表示中。 */
    @Immutable
    data class SearchResultsList(
        val query: String,
        val results: ImmutableList<SearchResultItem>,
        // TODO: テキスト検索用の loading flag が未実装のため現状デッドコード。将来検索中表示を追加する際に活用
        val isLoading: Boolean = false,
    ) : HomeMapScreenState

    /** 選択された地点の詳細を表示中。 */
    @Immutable
    data class PlaceDetails(
        val place: SearchResultItem,
        val isLoading: Boolean = false,
    ) : HomeMapScreenState

    /** ルートプレビュー中。ルート一覧と概要が表示される。 */
    @Immutable
    data class RoutePreview(
        val waypoints: ImmutableList<RouteWaypoint>,
        val routes: ImmutableList<RouteResult>,
        val selectedRouteIndex: Int,
        val topBarMode: RoutePreviewTopBarMode,
        val isLoading: Boolean = false,
    ) : HomeMapScreenState

    /** ターンバイターンナビゲーション中。 */
    @Stable
    data object Navigating : HomeMapScreenState

    /** 目的地到着。将来 UI を追加可能な設計。 */
    @Immutable
    data class Arrived(
        val destination: RouteWaypoint,
    ) : HomeMapScreenState
}
