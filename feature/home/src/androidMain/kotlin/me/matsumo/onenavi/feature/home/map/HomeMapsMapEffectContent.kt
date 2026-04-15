package me.matsumo.onenavi.feature.home.map

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapEffect
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import io.github.aakira.napier.Napier
import kotlinx.collections.immutable.ImmutableList
import me.matsumo.onenavi.core.model.RoutePoint
import me.matsumo.onenavi.core.model.RouteWaypoint
import me.matsumo.onenavi.core.navigation.CameraManager
import me.matsumo.onenavi.core.navigation.RouteManager
import me.matsumo.onenavi.feature.home.map.state.HomeMapScreenState

private const val TAG = "HomeMapsMap"
private const val PRIMARY_ROUTE_WIDTH = 14f
private const val SECONDARY_ROUTE_WIDTH = 9f
private const val GOOGLE_BLUE = 0xFF1A73E8
private const val GOOGLE_ROUTE_GRAY = 0xFF78909C

@Composable
internal fun HomeMapsMapEffectContent(
    viewportState: HomeMapViewportState,
    screenState: HomeMapScreenState,
    routeResults: ImmutableList<RouteResult>,
    selectedRouteIndex: Int,
    routeManager: RouteManager,
    cameraManager: CameraManager,
    onMapReady: (GoogleMap) -> Unit,
    onMapLandmarkSelected: (name: String?, latitude: Double, longitude: Double) -> Unit,
    onRouteSelected: (index: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val hasLocationPermission = remember(context) {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
    }
    val currentSelectedRouteIndex = rememberUpdatedState(selectedRouteIndex)
    val currentOnMapReady = rememberUpdatedState(onMapReady)
    val currentOnRouteSelected = rememberUpdatedState(onRouteSelected)
    val currentOnMapLandmarkSelected = rememberUpdatedState(onMapLandmarkSelected)

    var googleMap by remember { mutableStateOf<GoogleMap?>(null) }
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(
            LatLng(
                viewportState.cameraState.latitude,
                viewportState.cameraState.longitude,
            ),
            viewportState.cameraState.zoom,
        )
    }

    GoogleMap(
        modifier = modifier.fillMaxSize(),
        cameraPositionState = cameraPositionState,
        properties = MapProperties(
            isMyLocationEnabled = hasLocationPermission,
        ),
        uiSettings = MapUiSettings(
            compassEnabled = false,
            mapToolbarEnabled = false,
            myLocationButtonEnabled = false,
            zoomControlsEnabled = false,
        ),
        onMapLongClick = { latLng ->
            currentOnMapLandmarkSelected.value(null, latLng.latitude, latLng.longitude)
        },
        onPOIClick = { poi ->
            currentOnMapLandmarkSelected.value(poi.name, poi.latLng.latitude, poi.latLng.longitude)
        },
        onMapLoaded = {
            val camera = googleMap?.cameraPosition
            Napier.d(tag = TAG) {
                "Map loaded: target=${camera?.target}, zoom=${camera?.zoom}, tilt=${camera?.tilt}, bearing=${camera?.bearing}"
            }
        },
    ) {
        routeResults.forEachIndexed { index, routeResult ->
            Polyline(
                points = routeResult.item.geometry.map(RoutePoint::toLatLng),
                color = Color(if (index == selectedRouteIndex) GOOGLE_BLUE else GOOGLE_ROUTE_GRAY),
                width = if (index == selectedRouteIndex) PRIMARY_ROUTE_WIDTH else SECONDARY_ROUTE_WIDTH,
                clickable = true,
                zIndex = if (index == selectedRouteIndex) 2f else 1f,
                onClick = {
                    if (index != currentSelectedRouteIndex.value) {
                        currentOnRouteSelected.value(index)
                    }
                },
            )
        }

        when (screenState) {
            is HomeMapScreenState.Browsing -> Unit
            is HomeMapScreenState.SearchResultsList -> {
                screenState.results.forEachIndexed { index, result ->
                    Marker(
                        state = MarkerState(position = LatLng(result.latitude, result.longitude)),
                        title = result.name,
                        snippet = (index + 1).toString(),
                        icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED),
                    )
                }
            }
            is HomeMapScreenState.PlaceDetails -> {
                Marker(
                    state = MarkerState(position = LatLng(screenState.place.latitude, screenState.place.longitude)),
                    title = screenState.place.name,
                    icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED),
                )
            }
            is HomeMapScreenState.RoutePreview -> {
                screenState.waypoints.lastOrNull()?.let { waypoint ->
                    val point = waypoint.toRoutePoint()
                    Marker(
                        state = MarkerState(position = point.toLatLng()),
                        icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED),
                    )
                }
                screenState.waypoints.drop(1).dropLast(1).forEachIndexed { index, waypoint ->
                    val point = waypoint.toRoutePoint()
                    Marker(
                        state = MarkerState(position = point.toLatLng()),
                        title = "K${index + 1}",
                    )
                }
            }
            is HomeMapScreenState.Navigating,
            is HomeMapScreenState.Arrived,
            -> Unit
        }

        MapEffect(hasLocationPermission) { map ->
            googleMap = map
            map.mapType = GoogleMap.MAP_TYPE_NORMAL
            map.isBuildingsEnabled = true
            map.setOnMapLoadedCallback {
                val camera = map.cameraPosition
                Napier.d(tag = TAG) {
                    "Map loaded callback: target=${camera.target}, zoom=${camera.zoom}, tilt=${camera.tilt}, bearing=${camera.bearing}"
                }
            }

            viewportState.attachMap(map)
            cameraManager.setupCamera(map)
            currentOnMapReady.value(map)

            val currentCamera = viewportState.cameraState
            map.moveCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder()
                        .target(LatLng(currentCamera.latitude, currentCamera.longitude))
                        .zoom(currentCamera.zoom)
                        .tilt(currentCamera.tilt)
                        .bearing(currentCamera.bearing.toFloat())
                        .build(),
                ),
            )
        }
    }

    LaunchedEffect(routeResults) {
        routeManager.setRoutes(routeResults.map { it.googleRoute })
        cameraManager.onRouteChanged(routeResults.firstOrNull()?.googleRoute)
    }

    DisposableEffect(Unit) {
        onDispose {
            googleMap = null
            viewportState.detachMap()
            cameraManager.teardownCamera()
        }
    }
}

private fun RoutePoint.toLatLng(): LatLng {
    return LatLng(latitude, longitude)
}

private fun RouteWaypoint.toRoutePoint(): RoutePoint {
    return when (this) {
        is RouteWaypoint.CurrentLocation -> RoutePoint(latitude, longitude)
        is RouteWaypoint.Place -> RoutePoint(latitude, longitude)
    }
}
