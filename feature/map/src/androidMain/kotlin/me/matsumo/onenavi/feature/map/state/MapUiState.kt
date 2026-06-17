package me.matsumo.onenavi.feature.map.state

import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import me.matsumo.onenavi.core.model.RouteWaypoint
import me.matsumo.onenavi.core.model.SavedPlace
import me.matsumo.onenavi.core.model.SearchHistory
import me.matsumo.onenavi.core.model.SearchResultItem
import me.matsumo.onenavi.core.model.SearchSuggestionItem
import me.matsumo.onenavi.core.navigation.newguidance.semantic.GuideImageKey

/**
 * 地図画面全体の UI 状態。
 *
 * @property query 検索欄の現在入力値。
 * @property suggestions 検索サジェスト一覧。
 * @property histories 検索履歴一覧。
 * @property selectedResult 地点詳細で選択中の地点。
 * @property bookmarkedPlaces 地図上に表示するブックマーク一覧。
 * @property placeDetailsBookmark 表示中の地点詳細に一致するブックマーク。
 * @property routeWaypointEditResult waypoint 編集で返す選択地点。
 * @property overlayState 地図上に重ねるオーバーレイ状態。
 * @property topAppBarHeight 案内トップパネルの高さ px。
 * @property bottomSheetPeekHeight BottomSheet の peek 高さ。
 * @property navigationGuideImage 案内中バナーに表示する案内画像。無ければ null。
 * @property navigationCardHeight ナビゲーション下部 ETA カードの高さ px。
 * @property isNavigationRoutePreviewing 案内中にルート全体をプレビュー表示しているか。
 */
@Stable
data class MapUiState(
    val query: String? = null,
    val suggestions: ImmutableList<SearchSuggestionItem> = persistentListOf(),
    val histories: ImmutableList<SearchHistory> = persistentListOf(),
    val selectedResult: SearchResultItem? = null,
    val bookmarkedPlaces: ImmutableList<SavedPlace> = persistentListOf(),
    val placeDetailsBookmark: SavedPlace? = null,
    val routeWaypointEditResult: Pair<Int, RouteWaypoint.Place>? = null,
    val overlayState: MapOverlayState = MapOverlayState.None,
    val topAppBarHeight: Int = 0,
    val bottomSheetPeekHeight: Dp = 0.dp,
    val navigationGuideImage: NavigationGuideImage? = null,
    val navigationCardHeight: Int = 0,
    val isNavigationRoutePreviewing: Boolean = false,
)

/**
 * 案内中バナーに表示する案内画像。
 *
 * @property key 画像参照 key。
 * @property bitmap 表示用に decode 済みの bitmap。
 */
@Stable
data class NavigationGuideImage(
    val key: GuideImageKey,
    val bitmap: ImageBitmap,
)

/** 案内画像を表示し始める案内地点までの距離 (m)。 */
internal const val NAVIGATION_GUIDE_IMAGE_VISIBILITY_METERS: Int = 3_000
