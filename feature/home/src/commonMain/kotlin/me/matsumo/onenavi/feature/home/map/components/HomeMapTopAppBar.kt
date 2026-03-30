package me.matsumo.onenavi.feature.home.map.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AppBarWithSearch
import androidx.compose.material3.ExpandedFullScreenSearchBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SearchBarState
import androidx.compose.material3.SearchBarValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSearchBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationEventHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import me.matsumo.onenavi.core.model.SearchHistory
import me.matsumo.onenavi.core.model.SearchSuggestionItem
import me.matsumo.onenavi.core.resource.Res
import me.matsumo.onenavi.core.resource.home_search_bar_placeholder
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
internal fun HomeMapTopAppBar(
    showSearchResult: Boolean,
    suggestions: ImmutableList<SearchSuggestionItem>,
    histories: ImmutableList<SearchHistory>,
    onQueryChanged: (String) -> Unit,
    onSuggestionSelected: (SearchSuggestionItem) -> Unit,
    onHistorySelected: (SearchHistory) -> Unit,
    onRemoveHistory: (String) -> Unit,
    onBackClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val navigationState = rememberNavigationEventState(NavigationEventInfo.None)
    val scope = rememberCoroutineScope()
    val searchBarState = rememberSearchBarState()
    val textFieldState = rememberTextFieldState()
    var canFocus by remember { mutableStateOf(false) }

    fun onSearch(query: String) {
        textFieldState.setTextAndPlaceCursorAtEnd(query)
        onQueryChanged(query)
    }

    NavigationEventHandler(navigationState, isBackEnabled = showSearchResult) {
        textFieldState.clearText()
        onBackClicked.invoke()
    }

    LaunchedEffect(Unit) {
        canFocus = true
    }

    LaunchedEffect(textFieldState) {
        snapshotFlow { textFieldState.text.toString() }
            .distinctUntilChanged()
            .collect { query ->
                onQueryChanged(query)
            }
    }

    Column(
        modifier = modifier,
    ) {
        AppBarWithSearch(
            modifier = Modifier.fillMaxWidth(),
            state = searchBarState,
            inputField = {
                HomeMapSearchInputField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusProperties {
                            this.canFocus = canFocus
                        },
                    searchBarState = searchBarState,
                    textFieldState = textFieldState,
                    showSearchResult = showSearchResult,
                    onSearch = ::onSearch,
                    onBackClicked = onBackClicked,
                )
            },
            colors = SearchBarDefaults.appBarWithSearchColors(
                appBarContainerColor = Color.Transparent,
            ),
            windowInsets = WindowInsets(0),
        )

        ExpandedFullScreenSearchBar(
            state = searchBarState,
            inputField = {
                HomeMapSearchInputField(
                    searchBarState = searchBarState,
                    textFieldState = textFieldState,
                    showSearchResult = showSearchResult,
                    onSearch = ::onSearch,
                    onBackClicked = onBackClicked,
                )
            },
        ) {
            val query = textFieldState.text.toString()

            if (query.isBlank()) {
                HomeMapSearchHistoryList(
                    histories = histories,
                    onHistorySelected = { history ->
                        textFieldState.setTextAndPlaceCursorAtEnd(history.name)
                        onHistorySelected(history)
                        scope.launch {
                            searchBarState.animateToCollapsed()
                        }
                    },
                    onRemoveHistory = onRemoveHistory,
                )
            } else {
                HomeMapSearchSuggestionList(
                    suggestions = suggestions,
                    onSuggestionSelected = { suggestion ->
                        textFieldState.setTextAndPlaceCursorAtEnd(suggestion.name)
                        onSuggestionSelected(suggestion)
                        scope.launch {
                            searchBarState.animateToCollapsed()
                        }
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeMapSearchInputField(
    searchBarState: SearchBarState,
    textFieldState: TextFieldState,
    showSearchResult: Boolean,
    onSearch: (String) -> Unit,
    onBackClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()

    SearchBarDefaults.InputField(
        modifier = modifier,
        searchBarState = searchBarState,
        textFieldState = textFieldState,
        onSearch = {
            onSearch.invoke(textFieldState.text.toString())
        },
        placeholder = {
            Text(
                modifier = Modifier.fillMaxWidth(),
                text = stringResource(Res.string.home_search_bar_placeholder),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        },
        leadingIcon = {
            val action: suspend () -> Unit
            val icon: ImageVector

            when {
                showSearchResult -> {
                    action = { onBackClicked() }
                    icon = Icons.AutoMirrored.Filled.ArrowBack
                }

                searchBarState.currentValue == SearchBarValue.Expanded -> {
                    action = { searchBarState.animateToCollapsed() }
                    icon = Icons.AutoMirrored.Filled.ArrowBack
                }

                else -> {
                    action = { searchBarState.animateToExpanded() }
                    icon = Icons.Default.Search
                }
            }

            IconButton(
                onClick = {
                    scope.launch {
                        action()
                    }
                },
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                )
            }
        },
        trailingIcon = {
            // for space
        },
    )
}

@Composable
private fun HomeMapSearchSuggestionList(
    suggestions: ImmutableList<SearchSuggestionItem>,
    onSuggestionSelected: (SearchSuggestionItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
    ) {
        itemsIndexed(
            items = suggestions,
            key = { index, item -> "${item.id}_$index" },
        ) { _, suggestion ->
            ListItem(
                modifier = Modifier.clickable {
                    onSuggestionSelected(suggestion)
                },
                leadingContent = {
                    Icon(
                        imageVector = Icons.Default.LocationOn,
                        contentDescription = null,
                    )
                },
                headlineContent = {
                    Text(
                        text = suggestion.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                supportingContent = suggestion.address?.let { address ->
                    {
                        Text(
                            text = address,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                colors = ListItemDefaults.colors(Color.Transparent)
            )

            HorizontalDivider()
        }
    }
}

@Composable
private fun HomeMapSearchHistoryList(
    histories: ImmutableList<SearchHistory>,
    onHistorySelected: (SearchHistory) -> Unit,
    onRemoveHistory: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
    ) {
        items(
            items = histories,
            key = { it.id },
        ) { history ->
            ListItem(
                modifier = Modifier.clickable {
                    onHistorySelected(history)
                },
                leadingContent = {
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = null,
                    )
                },
                headlineContent = {
                    Text(
                        text = history.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                supportingContent = history.address?.let { address ->
                    {
                        Text(
                            text = address,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                trailingContent = {
                    IconButton(
                        onClick = { onRemoveHistory(history.id) },
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = null,
                        )
                    }
                },
                colors = ListItemDefaults.colors(Color.Transparent)
            )

            HorizontalDivider()
        }
    }
}
