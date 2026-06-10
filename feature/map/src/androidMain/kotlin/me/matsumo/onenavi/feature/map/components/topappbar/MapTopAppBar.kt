package me.matsumo.onenavi.feature.map.components.topappbar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import me.matsumo.onenavi.core.model.SearchHistory
import me.matsumo.onenavi.core.model.SearchResultItem
import me.matsumo.onenavi.core.model.SearchSuggestionItem
import me.matsumo.onenavi.core.resource.Res
import me.matsumo.onenavi.core.resource.home_search_bar_placeholder
import me.matsumo.onenavi.core.resource.setting_title
import me.matsumo.onenavi.core.ui.theme.LocalSupportsPlatformDialogWindow
import me.matsumo.onenavi.feature.map.state.MapCameraState
import me.matsumo.onenavi.feature.map.state.MapUiEvent
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
internal fun MapTopAppBar(
    cameraState: MapCameraState,
    query: String?,
    suggestions: ImmutableList<SearchSuggestionItem>,
    histories: ImmutableList<SearchHistory>,
    selectedResult: SearchResultItem?,
    showSettingAction: Boolean,
    destinationSearchRequestId: Long?,
    onDestinationSearchRequestConsumed: (Long) -> Unit,
    onUiEvent: (MapUiEvent) -> Unit,
    onSettingClicked: () -> Unit,
    onTopAppBarHeightChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val searchBarState = rememberSearchBarState()
    val textFieldState = rememberTextFieldState()
    val destinationSearchFocusRequester = remember { FocusRequester() }
    val softwareKeyboardController = LocalSoftwareKeyboardController.current
    val supportsPlatformDialogWindow = LocalSupportsPlatformDialogWindow.current

    var showSearchResult by rememberSaveable { mutableStateOf(false) }
    var canFocus by remember { mutableStateOf(false) }

    fun onBackClicked() {
        showSearchResult = false
        onUiEvent(MapUiEvent.OnPlaceDetailsDismissed)
    }

    fun onSearchAction(query: String) {
        onUiEvent(
            MapUiEvent.OnSearch(
                query = query,
                latitude = cameraState.myLocationLatitude,
                longitude = cameraState.myLocationLongitude,
            ),
        )
    }

    suspend fun collapseSearchAfterItemSelected() {
        showSearchResult = true
        searchBarState.animateToCollapsed()
    }

    fun requestSearchCollapseAfterItemSelected() {
        scope.launch {
            collapseSearchAfterItemSelected()
        }
    }

    fun onHistorySelected(history: SearchHistory) {
        textFieldState.setTextAndPlaceCursorAtEnd(history.name)
        onUiEvent(MapUiEvent.OnHistorySelected(history))
        requestSearchCollapseAfterItemSelected()
    }

    fun onSuggestionSelected(suggestion: SearchSuggestionItem) {
        textFieldState.setTextAndPlaceCursorAtEnd(suggestion.name)
        onUiEvent(MapUiEvent.OnSuggestionSelected(suggestion))
        requestSearchCollapseAfterItemSelected()
    }

    LaunchedEffect(Unit) {
        canFocus = true
    }

    LaunchedEffect(selectedResult, query) {
        when {
            selectedResult != null -> {
                textFieldState.setTextAndPlaceCursorAtEnd(selectedResult.name)
                showSearchResult = true
            }

            query == null -> {
                textFieldState.clearText()
                showSearchResult = false
            }
        }
    }

    LaunchedEffect(textFieldState) {
        snapshotFlow { textFieldState.text.toString() }
            .distinctUntilChanged()
            .collect { query ->
                onUiEvent(MapUiEvent.OnQueryChanged(query))
            }
    }

    LaunchedEffect(destinationSearchRequestId) {
        val requestId = destinationSearchRequestId ?: return@LaunchedEffect

        onDestinationSearchRequestConsumed(requestId)
        canFocus = true
        showSearchResult = false
        searchBarState.animateToExpanded()
        awaitDestinationSearchInputPlacement()
        runCatching {
            destinationSearchFocusRequester.requestFocus()
        }
        softwareKeyboardController?.show()
    }

    Box(
        modifier = modifier,
    ) {
        val isSearchBarCollapsed = searchBarState.currentValue == SearchBarValue.Collapsed
        val isSearchBarTargetCollapsed = searchBarState.targetValue == SearchBarValue.Collapsed
        val shouldShowSettingAction = showSettingAction && isSearchBarCollapsed && isSearchBarTargetCollapsed

        Column(
            modifier = Modifier.fillMaxWidth(),
        ) {
            AppBarWithSearch(
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned {
                        onTopAppBarHeightChanged(it.size.height)
                    },
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
                        focusRequester = destinationSearchFocusRequester,
                        showSearchResult = showSearchResult,
                        showSettingAction = shouldShowSettingAction,
                        onSearch = ::onSearchAction,
                        onBackClicked = ::onBackClicked,
                        onSettingClicked = onSettingClicked,
                    )
                },
                shadowElevation = 4.dp,
                colors = SearchBarDefaults.appBarWithSearchColors(
                    appBarContainerColor = Color.Transparent,
                    searchBarColors = SearchBarDefaults.colors(MaterialTheme.colorScheme.surfaceContainerLow),
                ),
                contentPadding = PaddingValues(top = 4.dp),
                windowInsets = WindowInsets(0),
            )

            if (supportsPlatformDialogWindow) {
                ExpandedFullScreenSearchBar(
                    state = searchBarState,
                    inputField = {
                        HomeMapSearchInputField(
                            searchBarState = searchBarState,
                            textFieldState = textFieldState,
                            focusRequester = destinationSearchFocusRequester,
                            showSearchResult = showSearchResult,
                            showSettingAction = false,
                            onSearch = ::onSearchAction,
                            onBackClicked = ::onBackClicked,
                            onSettingClicked = onSettingClicked,
                        )
                    },
                    colors = SearchBarDefaults.colors(MaterialTheme.colorScheme.surfaceContainerLow),
                ) {
                    MapTopAppBarSearchContent(
                        query = textFieldState.text.toString(),
                        suggestions = suggestions,
                        histories = histories,
                        onHistorySelected = ::onHistorySelected,
                        onRemoveHistory = { onUiEvent(MapUiEvent.OnRemoveHistory(it)) },
                        onSuggestionSelected = ::onSuggestionSelected,
                    )
                }
            }
        }

        if (!supportsPlatformDialogWindow) {
            MapTopAppBarEmbeddedSearchOverlay(
                modifier = Modifier.fillMaxSize(),
                isVisible = searchBarState.isExpandedOrExpanding(),
                searchBarState = searchBarState,
                textFieldState = textFieldState,
                focusRequester = destinationSearchFocusRequester,
                query = textFieldState.text.toString(),
                suggestions = suggestions,
                histories = histories,
                showSearchResult = showSearchResult,
                onSearch = ::onSearchAction,
                onBackClicked = ::onBackClicked,
                onSettingClicked = onSettingClicked,
                onHistorySelected = ::onHistorySelected,
                onRemoveHistory = { onUiEvent(MapUiEvent.OnRemoveHistory(it)) },
                onSuggestionSelected = ::onSuggestionSelected,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MapTopAppBarEmbeddedSearchOverlay(
    isVisible: Boolean,
    searchBarState: SearchBarState,
    textFieldState: TextFieldState,
    focusRequester: FocusRequester,
    query: String,
    suggestions: ImmutableList<SearchSuggestionItem>,
    histories: ImmutableList<SearchHistory>,
    showSearchResult: Boolean,
    onSearch: (String) -> Unit,
    onBackClicked: () -> Unit,
    onSettingClicked: () -> Unit,
    onHistorySelected: (SearchHistory) -> Unit,
    onRemoveHistory: (String) -> Unit,
    onSuggestionSelected: (SearchSuggestionItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!isVisible) {
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        HomeMapSearchInputField(
            modifier = Modifier.fillMaxWidth(),
            searchBarState = searchBarState,
            textFieldState = textFieldState,
            focusRequester = focusRequester,
            showSearchResult = showSearchResult,
            showSettingAction = false,
            onSearch = onSearch,
            onBackClicked = onBackClicked,
            onSettingClicked = onSettingClicked,
        )

        MapTopAppBarSearchContent(
            modifier = Modifier.weight(1f),
            query = query,
            suggestions = suggestions,
            histories = histories,
            onHistorySelected = onHistorySelected,
            onRemoveHistory = onRemoveHistory,
            onSuggestionSelected = onSuggestionSelected,
        )
    }
}

@Composable
private fun MapTopAppBarSearchContent(
    query: String,
    suggestions: ImmutableList<SearchSuggestionItem>,
    histories: ImmutableList<SearchHistory>,
    onHistorySelected: (SearchHistory) -> Unit,
    onRemoveHistory: (String) -> Unit,
    onSuggestionSelected: (SearchSuggestionItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (query.isBlank()) {
        HomeMapSearchHistoryList(
            modifier = modifier,
            histories = histories,
            onHistorySelected = onHistorySelected,
            onRemoveHistory = onRemoveHistory,
        )
    } else {
        HomeMapSearchSuggestionList(
            modifier = modifier,
            suggestions = suggestions,
            onSuggestionSelected = onSuggestionSelected,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
private fun SearchBarState.isExpandedOrExpanding(): Boolean {
    return currentValue == SearchBarValue.Expanded || targetValue == SearchBarValue.Expanded
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeMapSearchInputField(
    searchBarState: SearchBarState,
    textFieldState: TextFieldState,
    focusRequester: FocusRequester,
    showSearchResult: Boolean,
    showSettingAction: Boolean,
    onSearch: (String) -> Unit,
    onBackClicked: () -> Unit,
    onSettingClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()

    SearchBarDefaults.InputField(
        modifier = modifier.focusRequester(focusRequester),
        searchBarState = searchBarState,
        textFieldState = textFieldState,
        onSearch = {
            onSearch.invoke(textFieldState.text.toString())

            scope.launch {
                searchBarState.animateToCollapsed()
            }
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
            HomeMapSearchTrailingIcon(
                textFieldState = textFieldState,
                searchBarState = searchBarState,
                showSettingAction = showSettingAction,
                onBackClicked = onBackClicked,
                onSettingClicked = onSettingClicked,
            )
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeMapSearchTrailingIcon(
    textFieldState: TextFieldState,
    searchBarState: SearchBarState,
    showSettingAction: Boolean,
    onBackClicked: () -> Unit,
    onSettingClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
    ) {
        if (textFieldState.text.isNotEmpty()) {
            IconButton(
                onClick = {
                    textFieldState.clearText()

                    if (searchBarState.currentValue == SearchBarValue.Collapsed) {
                        onBackClicked.invoke()
                    }
                },
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = null,
                )
            }
        }

        if (showSettingAction) {
            IconButton(
                onClick = onSettingClicked,
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = stringResource(Res.string.setting_title),
                )
            }
        }
    }
}

@Composable
internal fun HomeMapSearchSuggestionList(
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
                colors = ListItemDefaults.colors(Color.Transparent),
            )

            HorizontalDivider()
        }
    }
}

@Composable
internal fun HomeMapSearchHistoryList(
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
                colors = ListItemDefaults.colors(Color.Transparent),
            )

            HorizontalDivider()
        }
    }
}

private suspend fun awaitDestinationSearchInputPlacement() {
    withFrameNanos { frameTimeNanos -> frameTimeNanos }
}
