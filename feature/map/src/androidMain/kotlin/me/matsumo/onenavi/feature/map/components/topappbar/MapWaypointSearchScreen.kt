package me.matsumo.onenavi.feature.map.components.topappbar

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
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationEventHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.flow.distinctUntilChanged
import me.matsumo.onenavi.core.model.SearchHistory
import me.matsumo.onenavi.core.model.SearchSuggestionItem
import me.matsumo.onenavi.core.resource.Res
import me.matsumo.onenavi.core.resource.home_search_bar_placeholder
import me.matsumo.onenavi.feature.map.state.MapUiEvent
import org.jetbrains.compose.resources.stringResource

/**
 * waypoint 差し替え用の全画面地点検索オーバーレイ。
 * 画面遷移ではなく [me.matsumo.onenavi.feature.map.state.MapUiState.overlayState] で表示制御され、
 * 展開済みの SearchBar を模した全画面 Surface を最前面に重ねる。下層のシート等は維持される。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MapWaypointSearchScreen(
    isVisible: Boolean,
    initialQuery: String?,
    suggestions: ImmutableList<SearchSuggestionItem>,
    histories: ImmutableList<SearchHistory>,
    onUiEvent: (MapUiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 表示中だけハンドラを登録することで、下層 (ルートプレビューのトップバー等) より後に登録され
    // back キーをこのオーバーレイが最優先で処理できるようにする。
    if (isVisible) {
        val navigationState = rememberNavigationEventState(NavigationEventInfo.None)
        NavigationEventHandler(navigationState) {
            onUiEvent(MapUiEvent.OnWaypointSearchDismissed)
        }
    }

    AnimatedVisibility(
        modifier = modifier,
        visible = isVisible,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        MapWaypointSearchContent(
            initialQuery = initialQuery,
            suggestions = suggestions,
            histories = histories,
            onUiEvent = onUiEvent,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MapWaypointSearchContent(
    initialQuery: String?,
    suggestions: ImmutableList<SearchSuggestionItem>,
    histories: ImmutableList<SearchHistory>,
    onUiEvent: (MapUiEvent) -> Unit,
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
                onUiEvent(MapUiEvent.OnQueryChanged(query))
            }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        MapWaypointSearchInputField(
            modifier = Modifier
                .statusBarsPadding()
                .fillMaxWidth(),
            textFieldState = textFieldState,
            onSearch = { keyboardController?.hide() },
            onBackClicked = { onUiEvent(MapUiEvent.OnWaypointSearchDismissed) },
        )

        val query = textFieldState.text.toString()

        if (query.isBlank()) {
            HomeMapSearchHistoryList(
                modifier = Modifier.weight(1f),
                histories = histories,
                onHistorySelected = { onUiEvent(MapUiEvent.OnHistorySelected(it)) },
                onRemoveHistory = { onUiEvent(MapUiEvent.OnRemoveHistory(it)) },
            )
        } else {
            HomeMapSearchSuggestionList(
                modifier = Modifier.weight(1f),
                suggestions = suggestions,
                onSuggestionSelected = { onUiEvent(MapUiEvent.OnSuggestionSelected(it)) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MapWaypointSearchInputField(
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
                text = stringResource(Res.string.home_search_bar_placeholder),
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
