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

    /** 地点詳細の選択地点を現在のルート文脈へ経由地として追加する。 */
    data class OnPlaceAddWaypointClicked(val item: SearchResultItem) : MapUiEvent

    data class OnRouteIndexChanged(val index: Int) : MapUiEvent

    /** 選択中のルートでナビゲーションを開始する。 */
    data object OnNavigationStart : MapUiEvent

    /** ナビゲーションを停止し、ナビゲーション画面を閉じる。 */
    data object OnNavigationStop : MapUiEvent

    /** ナビゲーション中にルート全体のプレビュー表示を要求する。 */
    data object OnNavigationRoutePreviewClicked : MapUiEvent

    /** ナビゲーション中のルート全体プレビュー表示を終了する。 */
    data object OnNavigationRoutePreviewDismissed : MapUiEvent

    /** ナビゲーション中の waypoint 編集結果を現在の案内ルートへ反映する。 */
    data class OnNavigationWaypointEditConfirmed(
        val waypoints: ImmutableList<RouteWaypoint.Place>,
    ) : MapUiEvent

    /** ナビゲーション中に代替ルート候補の再探索を要求する。 */
    data object OnNavigationAlternativesClicked : MapUiEvent

    /** ナビゲーション中の代替ルート候補を選択して案内ルートへ反映する。 */
    data class OnNavigationAlternativeRouteSelected(val index: Int) : MapUiEvent

    /** ナビゲーション中の代替ルート候補表示を終了する。 */
    data object OnNavigationAlternativesDismissed : MapUiEvent

    /** ルートプレビューを閉じて直前の画面へ戻る。 */
    data object OnRoutePreviewDismissed : MapUiEvent

    /** 地点詳細を閉じて直前の画面へ戻る。 */
    data object OnPlaceDetailsDismissed : MapUiEvent

    /** ルート文脈上に重ねたボトムシートを閉じる。 */
    data object OnOverlaySheetDismissed : MapUiEvent

    /** 出発地と目的地を入れ替えてルートを再探索する。 */
    data object OnSwapWaypoints : MapUiEvent

    /** 編集後の waypoint 列でルートを再探索する。 */
    data class OnRouteWaypointsConfirmed(val waypoints: ImmutableList<RouteWaypoint>) : MapUiEvent

    /** 指定 index の waypoint を差し替えるための地点検索オーバーレイを開く。 */
    data class OnWaypointEditRequested(val index: Int) : MapUiEvent

    /** waypoint 差し替え用の複数地点検索を実行する。 */
    data class OnWaypointSearch(
        val query: String,
        val latitude: Double?,
        val longitude: Double?,
    ) : MapUiEvent

    /** ナビゲーション中に waypoint 追加用の地点検索オーバーレイを開く。 */
    data object OnAddWaypointRequested : MapUiEvent

    /** ナビゲーション中に waypoint 追加用の複数地点検索を実行する。 */
    data class OnAddWaypointSearch(
        val query: String,
        val latitude: Double?,
        val longitude: Double?,
    ) : MapUiEvent

    /** ナビゲーション中に waypoint 追加候補の地点を選択する。 */
    data class OnAddWaypointCandidateSelected(val item: SearchResultItem) : MapUiEvent

    /** ナビゲーション中に選択した waypoint 候補を経由地として確定する。 */
    data object OnAddWaypointConfirmed : MapUiEvent

    /** ナビゲーション中に選択した waypoint 候補を経由する代替ルート候補を表示する。 */
    data object OnAddWaypointAlternativesClicked : MapUiEvent

    /** スマホ側の目的地検索 UI 起動を要求する。 */
    data object OnPhoneDestinationSearchClicked : MapUiEvent

    /** スマホ側で目的地検索を始めるため、地図画面を検索可能な状態へ戻す。 */
    data object OnPhoneDestinationSearchRequested : MapUiEvent

    /** 共有案内状態が開始済みになったため、現在の表示面も案内画面へ同期する。 */
    data object OnSharedGuidanceStarted : MapUiEvent

    /** waypoint 差し替え用の地点検索オーバーレイを閉じる。 */
    data object OnWaypointSearchDismissed : MapUiEvent

    /** waypoint 差し替え結果を消費済みにする。 */
    data object OnWaypointEditResultConsumed : MapUiEvent

    data class OnTopAppBarHeightChanged(val height: Int) : MapUiEvent
    data class OnBottomSheetPeekHeightChanged(val height: Dp) : MapUiEvent

    /** ナビゲーション下部 ETA カードの高さ（px）が変化した。カメラパディングに反映する。 */
    data class OnNavigationCardHeightChanged(val height: Int) : MapUiEvent
}
