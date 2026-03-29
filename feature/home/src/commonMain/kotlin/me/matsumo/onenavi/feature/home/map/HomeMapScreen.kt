package me.matsumo.onenavi.feature.home.map

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.collections.immutable.ImmutableList
import me.matsumo.onenavi.core.model.SearchHistory
import me.matsumo.onenavi.core.model.SearchResultItem
import me.matsumo.onenavi.core.model.SearchSuggestionItem

@Composable
internal expect fun HomeMapScreen(
    mapBoxToken: String,
    suggestions: ImmutableList<SearchSuggestionItem>,
    histories: ImmutableList<SearchHistory>,
    selectedResult: SearchResultItem?,
    isSearching: Boolean,
    onQueryChanged: (String) -> Unit,
    onSuggestionSelected: (SearchSuggestionItem) -> Unit,
    onHistorySelected: (SearchHistory) -> Unit,
    onRemoveHistory: (String) -> Unit,
    onDismissResult: () -> Unit,
    modifier: Modifier = Modifier,
)
