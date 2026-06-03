package me.matsumo.onenavi.feature.map.state

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import kotlinx.collections.immutable.ImmutableList
import me.matsumo.onenavi.core.model.RouteWaypoint
import me.matsumo.onenavi.core.model.SearchHistory
import me.matsumo.onenavi.core.model.SearchResultItem
import me.matsumo.onenavi.core.model.SearchSuggestionItem

@Immutable
sealed interface MapUiEvent {
    data class OnQueryChanged(val query: String) : MapUiEvent
    data object OnQueryCleared : MapUiEvent

    data class OnSearch(
        val query: String,
        val latitude: Double?,
        val longitude: Double?,
    ) : MapUiEvent

    data class OnSuggestionSelected(val suggestion: SearchSuggestionItem) : MapUiEvent
    data class OnHistorySelected(val history: SearchHistory) : MapUiEvent
    data class OnRemoveHistory(val id: String) : MapUiEvent

    /** テキスト検索結果一覧から地点を選択する。 */
    data class OnSearchResultSelected(val item: SearchResultItem) : MapUiEvent

    /** 地図上の POI タップから地点詳細を開く。 */
    data class OnMapPointOfInterestSelected(
        val placeId: String,
        val name: String,
        val latitude: Double,
        val longitude: Double,
    ) : MapUiEvent

    /** 地図上の座標長押しから地点詳細を開く。 */
    data class OnMapLongPressed(
        val latitude: Double,
        val longitude: Double,
    ) : MapUiEvent

    data class OnRouteSearch(
        val item: SearchResultItem,
        val latitude: Double?,
        val longitude: Double?,
    ) : MapUiEvent

    data class OnRouteIndexChanged(val index: Int) : MapUiEvent

    /** 選択中のルートでナビゲーションを開始する。 */
    data object OnNavigationStart : MapUiEvent

    /** ナビゲーションを停止し、ナビゲーション画面を閉じる。 */
    data object OnNavigationStop : MapUiEvent

    /** ナビゲーション中にルート全体のプレビュー表示を要求する。 */
    data object OnNavigationRoutePreviewClicked : MapUiEvent

    /** ナビゲーション中のルート全体プレビュー表示を終了する。 */
    data object OnNavigationRoutePreviewDismissed : MapUiEvent

    /** ルートプレビューを閉じて直前の画面へ戻る。 */
    data object OnRoutePreviewDismissed : MapUiEvent

    /** 地点詳細を閉じて直前の画面へ戻る。 */
    data object OnPlaceDetailsDismissed : MapUiEvent

    /** 出発地と目的地を入れ替えてルートを再探索する。 */
    data object OnSwapWaypoints : MapUiEvent

    /** 編集後の waypoint 列でルートを再探索する。 */
    data class OnRouteWaypointsConfirmed(val waypoints: ImmutableList<RouteWaypoint>) : MapUiEvent

    /** 指定 index の waypoint を差し替えるための地点検索オーバーレイを開く。 */
    data class OnWaypointEditRequested(val index: Int) : MapUiEvent

    /** ナビゲーション中に waypoint 追加用の地点検索オーバーレイを開く。 */
    data object OnAddWaypointRequested : MapUiEvent

    /** ナビゲーション中に waypoint 追加用の複数地点検索を実行する。 */
    data class OnAddWaypointSearch(
        val query: String,
        val latitude: Double?,
        val longitude: Double?,
    ) : MapUiEvent

    /** waypoint 差し替え用の地点検索オーバーレイを閉じる。 */
    data object OnWaypointSearchDismissed : MapUiEvent

    /** waypoint 差し替え結果を消費済みにする。 */
    data object OnWaypointEditResultConsumed : MapUiEvent

    data class OnTopAppBarHeightChanged(val height: Int) : MapUiEvent
    data class OnBottomSheetPeekHeightChanged(val height: Dp) : MapUiEvent

    /** ナビゲーション下部 ETA カードの高さ（px）が変化した。カメラパディングに反映する。 */
    data class OnNavigationCardHeightChanged(val height: Int) : MapUiEvent
}
