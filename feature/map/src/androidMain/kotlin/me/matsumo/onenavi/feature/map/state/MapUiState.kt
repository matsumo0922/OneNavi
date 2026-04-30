package me.matsumo.onenavi.feature.map.state

import androidx.compose.runtime.Stable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import me.matsumo.onenavi.core.model.SearchHistory
import me.matsumo.onenavi.core.model.SearchResultItem
import me.matsumo.onenavi.core.model.SearchSuggestionItem

@Stable
data class MapUiState(
    val query: String? = null,
    val suggestions: ImmutableList<SearchSuggestionItem> = persistentListOf(),
    val histories: ImmutableList<SearchHistory> = persistentListOf(),
    val selectedResult: SearchResultItem? = null,
)