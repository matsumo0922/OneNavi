package me.matsumo.onenavi.feature.map.state

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
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

    data class OnRouteSearch(
        val item: SearchResultItem,
        val latitude: Double?,
        val longitude: Double?,
    ) : MapUiEvent

    data class OnRouteIndexChanged(val index: Int) : MapUiEvent
    data object OnNavigationStart : MapUiEvent

    data class OnTopAppBarHeightChanged(val height: Int) : MapUiEvent
    data class OnBottomSheetPeekHeightChanged(val height: Dp) : MapUiEvent
}
