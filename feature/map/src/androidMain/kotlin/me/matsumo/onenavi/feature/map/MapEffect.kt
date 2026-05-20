package me.matsumo.onenavi.feature.map

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.google.android.gms.maps.GoogleMap
import kotlinx.collections.immutable.persistentListOf
import me.matsumo.onenavi.core.model.RouteDetail
import me.matsumo.onenavi.core.navigation.newguidance.model.GuidanceState
import me.matsumo.onenavi.core.navigation.newguidance.model.RoutePreviewState
import me.matsumo.onenavi.feature.map.components.MapMarker
import me.matsumo.onenavi.feature.map.components.MapNumberedMarker
import me.matsumo.onenavi.feature.map.components.MapPolyline
import me.matsumo.onenavi.feature.map.components.MapPolylineStyle
import me.matsumo.onenavi.feature.map.components.MapVehiclePuck
import me.matsumo.onenavi.feature.map.components.callout.MapRoutePreviewCallOutMarkerEffect
import me.matsumo.onenavi.feature.map.state.MapScreenState
import me.matsumo.onenavi.feature.map.state.VehicleLocationState

@Composable
internal fun MapEffect(
    screenState: MapScreenState,
    routePreviewState: RoutePreviewState,
    guidanceState: GuidanceState,
    vehicleLocationState: VehicleLocationState?,
    googleMap: GoogleMap,
    topAppBarHeightPx: Int,
    bottomSheetPeekHeight: Dp,
    onRouteSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (screenState) {
        is MapScreenState.Browsing -> Unit

        is MapScreenState.PlaceDetails -> {
            PlaceDetailsEffect(
                screenState = screenState,
                googleMap = googleMap,
            )
        }

        is MapScreenState.SearchResultsList -> {
            SearchResultsListEffect(
                screenState = screenState,
                googleMap = googleMap,
            )
        }

        is MapScreenState.RoutePreview -> {
            RoutePreviewEffect(
                modifier = modifier,
                screenState = screenState,
                routePreviewState = routePreviewState,
                googleMap = googleMap,
                topAppBarHeightPx = topAppBarHeightPx,
                bottomSheetPeekHeight = bottomSheetPeekHeight,
                onRouteSelected = onRouteSelected,
            )
        }
        is MapScreenState.Navigating -> {
            NavigationEffect(
                guidanceState = guidanceState,
                googleMap = googleMap,
            )
        }
        is MapScreenState.Arrived -> Unit
    }

    if (vehicleLocationState != null) {
        MapVehiclePuck(
            googleMap = googleMap,
            vehicleLocationState = vehicleLocationState,
            zIndex = VEHICLE_PUCK_Z_INDEX,
        )
    }
}

@Composable
private fun PlaceDetailsEffect(
    screenState: MapScreenState.PlaceDetails,
    googleMap: GoogleMap,
) {
    MapMarker(
        googleMap = googleMap,
        latitude = screenState.place.latitude,
        longitude = screenState.place.longitude,
        title = screenState.place.name,
    )
}

@Composable
private fun SearchResultsListEffect(
    screenState: MapScreenState.SearchResultsList,
    googleMap: GoogleMap,
) {
    screenState.results.forEachIndexed { index, result ->
        MapNumberedMarker(
            googleMap = googleMap,
            latitude = result.latitude,
            longitude = result.longitude,
            number = index + 1,
            title = result.name,
            zIndex = SEARCH_RESULT_MARKER_Z_INDEX + index,
        )
    }
}

@Composable
private fun RoutePreviewEffect(
    screenState: MapScreenState.RoutePreview,
    routePreviewState: RoutePreviewState,
    googleMap: GoogleMap,
    topAppBarHeightPx: Int,
    bottomSheetPeekHeight: Dp,
    onRouteSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    for (waypoint in screenState.waypoints.drop(1)) {
        MapMarker(
            googleMap = googleMap,
            latitude = waypoint.latitude,
            longitude = waypoint.longitude,
        )
    }

    if (routePreviewState is RoutePreviewState.Ready) {
        for ((routeIndex, route) in routePreviewState.routes.withIndex()) {
            RoutePolylineEffect(
                googleMap = googleMap,
                route = route,
                isSelected = routeIndex == routePreviewState.selectedIndex,
            )
        }
    }

    MapRoutePreviewCallOutMarkerEffect(
        modifier = modifier,
        googleMap = googleMap,
        routePreviewState = routePreviewState as? RoutePreviewState.Ready,
        topAppBarHeightPx = topAppBarHeightPx,
        bottomSheetPeekHeight = bottomSheetPeekHeight,
        onRouteSelected = onRouteSelected,
    )
}

@Composable
private fun NavigationEffect(
    guidanceState: GuidanceState,
    googleMap: GoogleMap,
) {
    val guiding = guidanceState as? GuidanceState.Guiding ?: return

    RoutePolylineEffect(
        googleMap = googleMap,
        route = guiding.route,
        isSelected = true,
    )
}

@Composable
private fun RoutePolylineEffect(
    googleMap: GoogleMap,
    route: RouteDetail,
    isSelected: Boolean,
) {
    MapPolyline(
        googleMap = googleMap,
        points = route.geometry,
        style = if (isSelected) MapPolylineStyle.Selected else MapPolylineStyle.Unselected,
        roadClassSegments = if (isSelected) route.roadClassSegments else persistentListOf(),
        congestionSegments = if (isSelected) route.congestionSegments else persistentListOf(),
    )
}

private const val SEARCH_RESULT_MARKER_Z_INDEX = 11_000f
private const val VEHICLE_PUCK_Z_INDEX = 12_000f
