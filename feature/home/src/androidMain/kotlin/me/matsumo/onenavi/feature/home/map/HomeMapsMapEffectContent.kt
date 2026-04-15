package me.matsumo.onenavi.feature.home.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.PolylineOptions
import kotlinx.collections.immutable.ImmutableList
import me.matsumo.onenavi.core.model.RoutePoint
import me.matsumo.onenavi.core.model.RouteWaypoint
import me.matsumo.onenavi.core.navigation.CameraManager
import me.matsumo.onenavi.core.navigation.RouteManager
import me.matsumo.onenavi.feature.home.map.state.HomeMapScreenState

private const val PRIMARY_ROUTE_WIDTH = 14f
private const val SECONDARY_ROUTE_WIDTH = 9f
private const val GOOGLE_BLUE = 0xFF1A73E8.toInt()
private const val GOOGLE_ROUTE_GRAY = 0xFF78909C.toInt()

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
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentRouteResults = rememberUpdatedState(routeResults)
    val currentSelectedRouteIndex = rememberUpdatedState(selectedRouteIndex)
    val currentOnRouteSelected = rememberUpdatedState(onRouteSelected)
    val currentOnMapLandmarkSelected = rememberUpdatedState(onMapLandmarkSelected)

    var googleMap by remember { mutableStateOf<GoogleMap?>(null) }
    val mapView = rememberMapViewWithLifecycle(lifecycleOwner)

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = {
            mapView.apply {
                getMapAsync { map ->
                    googleMap = map
                    viewportState.attachMap(map)
                    cameraManager.setupCamera(map)
                    onMapReady(map)
                    configureMap(
                        map = map,
                        hasLocationPermission = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.ACCESS_FINE_LOCATION,
                        ) == PackageManager.PERMISSION_GRANTED,
                        onMapLandmarkSelected = { name, point ->
                            currentOnMapLandmarkSelected.value(name, point.latitude, point.longitude)
                        },
                        onRoutePolylineSelected = { index ->
                            if (index != currentSelectedRouteIndex.value) {
                                currentOnRouteSelected.value(index)
                            }
                        },
                    )
                    renderMapState(
                        map = map,
                        screenState = screenState,
                        routeResults = currentRouteResults.value,
                        selectedRouteIndex = currentSelectedRouteIndex.value,
                    )
                }
            }
        },
        update = {
            googleMap?.let { map ->
                renderMapState(
                    map = map,
                    screenState = screenState,
                    routeResults = routeResults,
                    selectedRouteIndex = selectedRouteIndex,
                )
            }
        },
    )

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

@SuppressLint("MissingPermission")
private fun configureMap(
    map: GoogleMap,
    hasLocationPermission: Boolean,
    onMapLandmarkSelected: (String?, LatLng) -> Unit,
    onRoutePolylineSelected: (Int) -> Unit,
) {
    map.uiSettings.isCompassEnabled = false
    map.uiSettings.isMapToolbarEnabled = false
    map.uiSettings.isMyLocationButtonEnabled = false
    map.uiSettings.isZoomControlsEnabled = false
    if (hasLocationPermission) {
        map.isMyLocationEnabled = true
    }

    map.setOnMapLongClickListener { latLng ->
        onMapLandmarkSelected(null, latLng)
    }
    map.setOnPoiClickListener { poi ->
        onMapLandmarkSelected(poi.name, poi.latLng)
    }
    map.setOnPolylineClickListener { polyline ->
        (polyline.tag as? Int)?.let(onRoutePolylineSelected)
    }
}

private fun renderMapState(
    map: GoogleMap,
    screenState: HomeMapScreenState,
    routeResults: ImmutableList<RouteResult>,
    selectedRouteIndex: Int,
) {
    map.clear()
    renderRoutes(map, routeResults, selectedRouteIndex)
    renderMarkers(map, screenState)
}

private fun renderRoutes(
    map: GoogleMap,
    routeResults: ImmutableList<RouteResult>,
    selectedRouteIndex: Int,
) {
    routeResults.forEachIndexed { index, routeResult ->
        if (index == selectedRouteIndex) return@forEachIndexed
        map.addPolyline(
            PolylineOptions()
                .addAll(routeResult.item.geometry.map { it.toLatLng() })
                .color(GOOGLE_ROUTE_GRAY)
                .width(SECONDARY_ROUTE_WIDTH)
                .clickable(true)
                .zIndex(1f),
        ).tag = index
    }

    routeResults.getOrNull(selectedRouteIndex)?.let { routeResult ->
        map.addPolyline(
            PolylineOptions()
                .addAll(routeResult.item.geometry.map { it.toLatLng() })
                .color(GOOGLE_BLUE)
                .width(PRIMARY_ROUTE_WIDTH)
                .clickable(true)
                .zIndex(2f),
        ).tag = selectedRouteIndex
    }
}

private fun renderMarkers(
    map: GoogleMap,
    screenState: HomeMapScreenState,
) {
    when (screenState) {
        is HomeMapScreenState.Browsing -> Unit
        is HomeMapScreenState.SearchResultsList -> {
            screenState.results.forEachIndexed { index, result ->
                map.addMarker(
                    MarkerOptions()
                        .position(LatLng(result.latitude, result.longitude))
                        .title(result.name)
                        .snippet((index + 1).toString())
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)),
                )
            }
        }
        is HomeMapScreenState.PlaceDetails -> {
            map.addMarker(
                MarkerOptions()
                    .position(LatLng(screenState.place.latitude, screenState.place.longitude))
                    .title(screenState.place.name)
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)),
            )
        }
        is HomeMapScreenState.RoutePreview -> {
            screenState.waypoints.lastOrNull()?.let { waypoint ->
                val point = waypoint.toRoutePoint()
                map.addMarker(
                    MarkerOptions()
                        .position(point.toLatLng())
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)),
                )
            }
            screenState.waypoints.drop(1).dropLast(1).forEachIndexed { index, waypoint ->
                val point = waypoint.toRoutePoint()
                map.addMarker(
                    MarkerOptions()
                        .position(point.toLatLng())
                        .title("K${index + 1}"),
                )
            }
        }
        is HomeMapScreenState.Navigating,
        is HomeMapScreenState.Arrived,
        -> Unit
    }
}

@Composable
private fun rememberMapViewWithLifecycle(lifecycleOwner: LifecycleOwner): MapView {
    val context = LocalContext.current
    val mapView = remember {
        MapView(context).apply {
            onCreate(Bundle())
        }
    }

    DisposableEffect(lifecycleOwner, mapView) {
        val observer = object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = mapView.onStart()
            override fun onResume(owner: LifecycleOwner) = mapView.onResume()
            override fun onPause(owner: LifecycleOwner) = mapView.onPause()
            override fun onStop(owner: LifecycleOwner) = mapView.onStop()
            override fun onDestroy(owner: LifecycleOwner) = mapView.onDestroy()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    return mapView
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
