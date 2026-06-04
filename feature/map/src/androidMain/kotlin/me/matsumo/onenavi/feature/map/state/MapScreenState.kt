package me.matsumo.onenavi.feature.map.state

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import kotlinx.collections.immutable.ImmutableList
import me.matsumo.onenavi.core.model.RouteWaypoint
import me.matsumo.onenavi.core.model.SearchResultItem

/**
 * HomeMap 画面の排他的な画面状態。
 * combine 導出により、複数の raw state から自動計算される。
 */
@Stable
sealed interface MapScreenState {

    /** 地図ブラウジング中。検索バーとコントロールが表示される。 */
    @Stable
    data object Browsing : MapScreenState

    /**
     * テキスト検索結果の一覧を表示中。
     *
     * @property placeAction 結果から開いた地点詳細に引き継ぐ主要アクション。
     */
    @Immutable
    data class SearchResultsList(
        val query: String,
        val results: ImmutableList<SearchResultItem>,
        val placeAction: MapPlaceAction = MapPlaceAction.SearchRoute,
        val isLoading: Boolean = false,
    ) : MapScreenState

    /**
     * 選択された地点の詳細を表示中。
     *
     * @property placeAction 詳細画面の主要ボタンで実行するアクション。
     */
    @Immutable
    data class PlaceDetails(
        val place: SearchResultItem,
        val placeAction: MapPlaceAction = MapPlaceAction.SearchRoute,
        val isLoading: Boolean = false,
    ) : MapScreenState

    /**
     * ルートプレビュー中。ルート候補データの正本は [me.matsumo.onenavi.core.navigation.newguidance.NewRouteManager]
     * の StateFlow に存在する。本クラスは画面遷移情報のみ保持する。
     */
    @Immutable
    data class RoutePreview(
        val waypoints: ImmutableList<RouteWaypoint>,
        val topBarMode: RoutePreviewTopBarMode,
        val isLoading: Boolean = false,
    ) : MapScreenState

    /** ターンバイターンナビゲーション中。 */
    @Stable
    data object Navigating : MapScreenState

    /** 目的地到着。将来 UI を追加可能な設計。 */
    @Immutable
    data class Arrived(
        val destination: RouteWaypoint,
    ) : MapScreenState
}
