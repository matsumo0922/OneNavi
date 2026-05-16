package me.matsumo.onenavi.feature.home.map.components.topappbar

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SearchBarValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSearchBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.flow.distinctUntilChanged
import me.matsumo.onenavi.core.model.SearchHistory
import me.matsumo.onenavi.core.model.SearchSuggestionItem
import me.matsumo.onenavi.core.resource.Res
import me.matsumo.onenavi.core.resource.home_waypoint_search_placeholder
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeMapWaypointSearchScreen(
    isVisible: Boolean,
    initialQuery: String?,
    suggestions: ImmutableList<SearchSuggestionItem>,
    histories: ImmutableList<SearchHistory>,
    onSuggestionSelected: (SearchSuggestionItem) -> Unit,
    onHistorySelected: (SearchHistory) -> Unit,
    onRemoveHistory: (String) -> Unit,
    onQueryChanged: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        modifier = modifier,
        visible = isVisible,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        HomeMapWaypointSearchContent(
            initialQuery = initialQuery,
            suggestions = suggestions,
            histories = histories,
            onSuggestionSelected = onSuggestionSelected,
            onHistorySelected = onHistorySelected,
            onRemoveHistory = onRemoveHistory,
            onQueryChanged = onQueryChanged,
            onDismiss = onDismiss,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeMapWaypointSearchContent(
    initialQuery: String?,
    suggestions: ImmutableList<SearchSuggestionItem>,
    histories: ImmutableList<SearchHistory>,
    onSuggestionSelected: (SearchSuggestionItem) -> Unit,
    onHistorySelected: (SearchHistory) -> Unit,
    onRemoveHistory: (String) -> Unit,
    onQueryChanged: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val textFieldState = rememberTextFieldState(
        initialText = initialQuery.orEmpty(),
    )
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(textFieldState) {
        snapshotFlow { textFieldState.text.toString() }
            .distinctUntilChanged()
            .collect { query ->
                onQueryChanged(query)
            }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        HomeMapWaypointSearchInputField(
            modifier = Modifier
                .statusBarsPadding()
                .fillMaxWidth(),
            textFieldState = textFieldState,
            onSearch = { keyboardController?.hide() },
            onBackClicked = onDismiss,
        )

        val query = textFieldState.text.toString()

        if (query.isBlank()) {
            HomeMapSearchHistoryList(
                modifier = Modifier.weight(1f),
                histories = histories,
                onHistorySelected = onHistorySelected,
                onRemoveHistory = onRemoveHistory,
            )
        } else {
            HomeMapSearchSuggestionList(
                modifier = Modifier.weight(1f),
                suggestions = suggestions,
                onSuggestionSelected = onSuggestionSelected,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeMapWaypointSearchInputField(
    textFieldState: TextFieldState,
    onSearch: () -> Unit,
    onBackClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SearchBarDefaults.InputField(
        modifier = modifier,
        searchBarState = rememberSearchBarState(initialValue = SearchBarValue.Expanded),
        textFieldState = textFieldState,
        onSearch = { onSearch() },
        placeholder = {
            Text(
                text = stringResource(Res.string.home_waypoint_search_placeholder),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        },
        leadingIcon = {
            IconButton(onClick = onBackClicked) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null,
                )
            }
        },
        trailingIcon = {
            if (textFieldState.text.isNotEmpty()) {
                IconButton(
                    onClick = { textFieldState.clearText() },
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = null,
                    )
                }
            }
        },
    )
}
