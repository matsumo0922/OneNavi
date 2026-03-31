package me.matsumo.onenavi.feature.home.map

import androidx.compose.runtime.Immutable
import me.matsumo.onenavi.core.model.SearchHistory
import me.matsumo.onenavi.core.model.SearchResultItem
import me.matsumo.onenavi.core.model.SearchSuggestionItem

@Immutable
sealed interface HomeMapViewEvent {
    data class OnQueryChanged(
        val query: String,
    ) : HomeMapViewEvent

    data class OnSearch(
        val query: String,
        val latitude: Double?,
        val longitude: Double?,
    ) : HomeMapViewEvent

    data class OnSearchResultSelected(
        val result: SearchResultItem,
    ) : HomeMapViewEvent

    data class OnSuggestionSelected(
        val suggestion: SearchSuggestionItem,
    ) : HomeMapViewEvent

    data class OnHistorySelected(
        val history: SearchHistory,
    ) : HomeMapViewEvent

    data class OnRemoveHistory(
        val historyId: String,
    ) : HomeMapViewEvent

    data object OnRouteSearch: HomeMapViewEvent

    data class OnRouteSelected(
        val index: Int,
    ) : HomeMapViewEvent

    data object OnDismissRoutes : HomeMapViewEvent

    data object OnDismissSearchResult : HomeMapViewEvent
}
