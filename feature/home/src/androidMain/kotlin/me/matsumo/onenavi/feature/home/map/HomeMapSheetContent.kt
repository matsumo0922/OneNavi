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

private val SHEET_DRAG_HANDLE_HEIGHT = 48.dp

@Composable
internal fun HomeMapSheetContent(
    searchResults: ImmutableList<SearchResultItem>,
    selectedResult: SearchResultItem?,
    routeResults: ImmutableList<RouteResult>,
    selectedRouteIndex: Int,
    onViewEvent: (HomeMapViewEvent) -> Unit,
    modifier: Modifier = Modifier,
    onPeekHeightChanged: (Dp) -> Unit,
) {
    val density = LocalDensity.current

    if (routeResults.isNotEmpty()) {
        HomeMapRouteResultSheet(
            modifier = modifier,
            routeResults = routeResults,
            selectedRouteIndex = selectedRouteIndex,
            onNavigationClicked = { onViewEvent(HomeMapViewEvent.OnNavigationStarted) },
            onRouteResultSelected = { onViewEvent(HomeMapViewEvent.OnRouteSelected(it)) },
        )
    } else if (searchResults.isNotEmpty()) {
        HomeMapSearchResultSheet(
            modifier = modifier,
            searchResults = searchResults,
            onViewEvent = onViewEvent,
        )
    } else {
        selectedResult?.let { result ->
            HomeMapSelectedResultSheet(
                modifier = modifier,
                selectedResult = result,
                onViewEvent = onViewEvent,
                onPeekHeightMeasured = { heightPx ->
                    val measuredHeight = with(density) { heightPx.toDp() } + SHEET_DRAG_HANDLE_HEIGHT + 16.dp
                    onPeekHeightChanged(measuredHeight)
                },
            )
        }
    }
}
