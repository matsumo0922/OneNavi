package me.matsumo.onenavi.feature.home.map.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AppBarWithSearch
import androidx.compose.material3.ExpandedFullScreenSearchBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.launch
import me.matsumo.onenavi.core.resource.Res
import me.matsumo.onenavi.core.resource.home_search_bar_placeholder
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeMapTopAppBar(
    showSearchResult: Boolean,
    onSearchClicked: (String) -> Unit,
    onSearchBarExpand: () -> Unit,
    onBackClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val searchBarState = rememberSearchBarState()
    val textFieldState = rememberTextFieldState()
    var lastQuery by rememberSaveable { mutableStateOf<String?>(null) }
    var canFocus by remember { mutableStateOf(false) }

    fun onSearch(query: String) {
        lastQuery = query
        onSearchClicked(query)
        scope.launch {
            searchBarState.animateToCollapsed()
        }
    }

    LaunchedEffect(Unit) {
        canFocus = true
    }

    LaunchedEffect(searchBarState.targetValue) {
        if (searchBarState.targetValue == SearchBarValue.Expanded) {
            onSearchBarExpand.invoke()
        }
    }

    if (showSearchResult) {
        LaunchedEffect(searchBarState.currentValue, searchBarState.targetValue) {
            val currentValue = searchBarState.currentValue
            val targetValue = searchBarState.targetValue

            if (currentValue == SearchBarValue.Expanded && targetValue == SearchBarValue.Expanded && lastQuery != null) {
                textFieldState.setTextAndPlaceCursorAtEnd(lastQuery!!)
            }

            if (targetValue == SearchBarValue.Collapsed) {
                textFieldState.clearText()
            }
        }
    }

    Column(
        modifier = modifier,
    ) {
        AppBarWithSearch(
            state = searchBarState,
            inputField = {
                InputField(
                    modifier = Modifier.focusProperties {
                        this.canFocus = canFocus
                    },
                    searchBarState = searchBarState,
                    textFieldState = textFieldState,
                    lastQuery = lastQuery,
                    showSearchResult = showSearchResult,
                    onSearch = ::onSearch,
                    onBackClicked = onBackClicked,
                )
            },
            colors = SearchBarDefaults.appBarWithSearchColors(
                appBarContainerColor = Color.Transparent
            ),
            windowInsets = WindowInsets()
        )

        ExpandedFullScreenSearchBar(
            state = searchBarState,
            inputField = {
                InputField(
                    searchBarState = searchBarState,
                    textFieldState = textFieldState,
                    lastQuery = lastQuery,
                    showSearchResult = showSearchResult,
                    onSearch = ::onSearch,
                    onBackClicked = onBackClicked,
                )
            },
        ) {
            // TODO: Auto complete
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InputField(
    searchBarState: SearchBarState,
    textFieldState: TextFieldState,
    lastQuery: String?,
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
                text = lastQuery.takeIf { showSearchResult } ?: stringResource(Res.string.home_search_bar_placeholder),
                color = if (showSearchResult) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
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
