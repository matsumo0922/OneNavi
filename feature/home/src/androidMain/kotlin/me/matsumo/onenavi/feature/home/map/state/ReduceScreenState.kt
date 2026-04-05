package me.matsumo.onenavi.feature.home.map.state

import kotlinx.collections.immutable.ImmutableList
import me.matsumo.onenavi.core.model.NavigationState
import me.matsumo.onenavi.core.model.RouteWaypoint
import me.matsumo.onenavi.core.model.SearchResultItem
import me.matsumo.onenavi.feature.home.map.RouteResult

/**
 * 複数の raw state から HomeMapScreenState を導出する pure function。
 *
 * 優先順位: Arrival > ActiveGuidance > RoutePreview > SearchResultsList > PlaceDetails > Browsing
 */
internal fun reduceScreenState(
    searchResults: ImmutableList<SearchResultItem>,
    selectedResult: SearchResultItem?,
    routeResults: ImmutableList<RouteResult>,
    waypoints: ImmutableList<RouteWaypoint>,
    selectedRouteIndex: Int,
    topBarMode: RoutePreviewTopBarMode,
    lastSearchQuery: String,
    navigationState: NavigationState,
    isRouteSearching: Boolean,
): HomeMapScreenState = when {
    // TODO: 将来 Arrived UI を実装する際はこの分岐を有効化する
    // navigationState is NavigationState.Arrival -> {
    //     val destination = waypoints.lastOrNull()
    //     if (destination != null) {
    //         HomeMapScreenState.Arrived(destination = destination)
    //     } else {
    //         HomeMapScreenState.Browsing
    //     }
    // }
    navigationState is NavigationState.ActiveGuidance -> {
        HomeMapScreenState.Navigating
    }
    routeResults.isNotEmpty() && waypoints.isNotEmpty() -> {
        HomeMapScreenState.RoutePreview(
            waypoints = waypoints,
            routes = routeResults,
            selectedRouteIndex = selectedRouteIndex,
            topBarMode = topBarMode,
            isLoading = isRouteSearching,
        )
    }
    searchResults.isNotEmpty() -> {
        HomeMapScreenState.SearchResultsList(
            query = lastSearchQuery,
            results = searchResults,
        )
    }
    selectedResult != null -> {
        HomeMapScreenState.PlaceDetails(
            place = selectedResult,
            isLoading = isRouteSearching,
        )
    }
    else -> HomeMapScreenState.Browsing
}
