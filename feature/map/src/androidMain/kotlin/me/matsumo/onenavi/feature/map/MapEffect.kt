package me.matsumo.onenavi.feature.map

import androidx.compose.runtime.Composable
import com.google.android.gms.maps.GoogleMap
import me.matsumo.onenavi.core.navigation.newguidance.model.RoutePreviewState
import me.matsumo.onenavi.feature.map.components.MapMarker
import me.matsumo.onenavi.feature.map.components.MapPolyline
import me.matsumo.onenavi.feature.map.state.MapScreenState

@Composable
internal fun MapEffect(
    screenState: MapScreenState,
    routePreviewState: RoutePreviewState,
    googleMap: GoogleMap,
) {
    when (screenState) {
        is MapScreenState.Browsing -> Unit

        is MapScreenState.PlaceDetails -> {
            PlaceDetailsEffect(
                screenState = screenState,
                googleMap = googleMap,
            )
        }

        is MapScreenState.SearchResultsList -> TODO()
        is MapScreenState.RoutePreview -> {
            RoutePreviewEffect(
                screenState = screenState,
                routePreviewState = routePreviewState,
                googleMap = googleMap,
            )
        }
        is MapScreenState.Navigating -> TODO()
        is MapScreenState.Arrived -> TODO()
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
private fun RoutePreviewEffect(
    screenState: MapScreenState.RoutePreview,
    routePreviewState: RoutePreviewState,
    googleMap: GoogleMap,
) {
    for (waypoint in screenState.waypoints.drop(1)) {
        MapMarker(
            googleMap = googleMap,
            latitude = waypoint.latitude,
            longitude = waypoint.longitude,
        )
    }

    if (routePreviewState is RoutePreviewState.Ready) {
        for (route in routePreviewState.routes) {
            MapPolyline(
                googleMap = googleMap,
                points = route.geometry,
            )
        }
    }
}
