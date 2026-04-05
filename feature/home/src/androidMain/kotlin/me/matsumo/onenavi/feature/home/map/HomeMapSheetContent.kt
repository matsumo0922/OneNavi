package me.matsumo.onenavi.feature.home.map

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.ImmutableList
import me.matsumo.onenavi.core.model.SearchResultItem
import me.matsumo.onenavi.feature.home.map.components.bottomsheet.HomeMapRouteResultSheet
import me.matsumo.onenavi.feature.home.map.components.bottomsheet.HomeMapSearchResultSheet
import me.matsumo.onenavi.feature.home.map.components.bottomsheet.HomeMapSelectedResultSheet
import me.matsumo.onenavi.feature.home.map.state.HomeMapScreenState

private val SHEET_DRAG_HANDLE_HEIGHT = 48.dp

@Composable
internal fun HomeMapSheetContent(
    screenState: HomeMapScreenState,
    searchResults: ImmutableList<SearchResultItem>,
    selectedResult: SearchResultItem?,
    routeResults: ImmutableList<RouteResult>,
    selectedRouteIndex: Int,
    onNavigationStarted: () -> Unit,
    onRouteSelected: (Int) -> Unit,
    onSearchResultSelected: (SearchResultItem) -> Unit,
    onRouteSearchClicked: () -> Unit,
    modifier: Modifier = Modifier,
    onPeekHeightChanged: (Dp) -> Unit,
) {
    val density = LocalDensity.current

    when (screenState) {
        is HomeMapScreenState.RoutePreview -> {
            HomeMapRouteResultSheet(
                modifier = modifier,
                routeResults = routeResults,
                selectedRouteIndex = selectedRouteIndex,
                onNavigationClicked = { onNavigationStarted() },
                onRouteResultSelected = onRouteSelected,
            )
        }
        is HomeMapScreenState.SearchResultsList -> {
            HomeMapSearchResultSheet(
                modifier = modifier,
                searchResults = searchResults,
                onSearchResultSelected = onSearchResultSelected,
            )
        }
        is HomeMapScreenState.PlaceDetails -> {
            selectedResult?.let { result ->
                HomeMapSelectedResultSheet(
                    modifier = modifier,
                    selectedResult = result,
                    onRouteSearchClicked = onRouteSearchClicked,
                    onPeekHeightMeasured = { heightPx ->
                        val measuredHeight = with(density) { heightPx.toDp() } + SHEET_DRAG_HANDLE_HEIGHT + 16.dp
                        onPeekHeightChanged(measuredHeight)
                    },
                )
            }
        }
        else -> { /* Sheet 非表示状態 */ }
    }
}
