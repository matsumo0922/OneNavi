package me.matsumo.onenavi.feature.map.state

import androidx.compose.runtime.Immutable
import me.matsumo.onenavi.core.model.SearchHistory
import me.matsumo.onenavi.core.model.SearchSuggestionItem

@Immutable
sealed interface MapUiEvent {
    data object OnQueryCleared : MapUiEvent

    data class OnSearch(val query: String) : MapUiEvent
    data class OnSuggestionSelected(val suggestion: SearchSuggestionItem) : MapUiEvent
    data class OnHistorySelected(val history: SearchHistory) : MapUiEvent
    data class OnRemoveHistory(val id: String) : MapUiEvent
}