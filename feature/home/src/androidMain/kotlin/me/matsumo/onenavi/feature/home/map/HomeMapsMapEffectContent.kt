package me.matsumo.onenavi.feature.home.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import androidx.appcompat.content.res.AppCompatResources
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.MapsInitializer
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.PolylineOptions
import io.github.aakira.napier.Napier
import kotlinx.collections.immutable.ImmutableList
import me.matsumo.onenavi.core.model.RoutePoint
import me.matsumo.onenavi.core.model.RouteWaypoint
import me.matsumo.onenavi.core.navigation.CameraManager
import me.matsumo.onenavi.core.navigation.RouteManager
import me.matsumo.onenavi.feature.home.R
import me.matsumo.onenavi.feature.home.map.state.HomeMapScreenState

private const val PRIMARY_ROUTE_WIDTH = 14f
private const val SECONDARY_ROUTE_WIDTH = 9f
private const val GOOGLE_BLUE = 0xFF1A73E8.toInt()
private const val GOOGLE_ROUTE_GRAY = 0xFF78909C.toInt()
private const val TAG = "HomeMapsMap"

@Composable
internal fun HomeMapsMapEffectContent(
    viewportState: HomeMapViewportState,
    screenState: HomeMapScreenState,
    routeResults: ImmutableList<RouteResult>,
    selectedRouteIndex: Int,
    currentLocation: RoutePoint?,
    currentBearing: Float,
    routeManager: RouteManager,
    cameraManager: CameraManager,
    onMapReady: (GoogleMap) -> Unit,
    onMapLandmarkSelected: (name: String?, latitude: Double, longitude: Double) -> Unit,
    onRouteSelected: (index: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val hasLocationPermission = remember(context) {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
    }
    val vehiclePuckBitmap = remember(context) {
        context.createBitmap(R.drawable.ic_vehicle_puck)
    }
    val currentRouteResults = rememberUpdatedState(routeResults)
    val currentSelectedRouteIndex = rememberUpdatedState(selectedRouteIndex)
    val currentOnRouteSelected = rememberUpdatedState(onRouteSelected)
    val currentOnMapLandmarkSelected = rememberUpdatedState(onMapLandmarkSelected)
    val currentOnMapReady = rememberUpdatedState(onMapReady)
    val liveRoutes by routeManager.routes.collectAsStateWithLifecycle()

    var googleMap by remember { mutableStateOf<GoogleMap?>(null) }
    val mapView = rememberMapViewWithLifecycle(lifecycleOwner)

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = {
            mapView.apply {
                getMapAsync { map ->
                    googleMap = map
                    Napier.d(tag = TAG) {
                        "getMapAsync ready. target=${map.cameraPosition.target.latitude},${map.cameraPosition.target.longitude} zoom=${map.cameraPosition.zoom}"
                    }
                    viewportState.attachMap(map)
                    cameraManager.setupCamera(map)
                    currentOnMapReady.value(map)
                    configureMap(
                        map = map,
                        hasLocationPermission = hasLocationPermission,
                        onMapLandmarkSelected = { name, point ->
                            currentOnMapLandmarkSelected.value(name, point.latitude, point.longitude)
                        },
                        onRoutePolylineSelected = { index ->
                            if (index != currentSelectedRouteIndex.value) {
                                currentOnRouteSelected.value(index)
                            }
                        },
                    )
                    moveCameraToViewportState(map, viewportState.cameraState)
                    renderMapState(
                        map = map,
                        screenState = screenState,
                        routeGeometries = currentRouteResults.value.map { it.item.geometry },
                        selectedRouteIndex = currentSelectedRouteIndex.value,
                        currentLocation = currentLocation,
                        currentBearing = currentBearing,
                        vehiclePuckBitmap = vehiclePuckBitmap,
                    )
                    map.setOnMapLoadedCallback {
                        Napier.i(tag = TAG) {
                            "Map loaded. target=${map.cameraPosition.target.latitude},${map.cameraPosition.target.longitude} zoom=${map.cameraPosition.zoom}"
                        }
                        map.snapshot { snapshot ->
                            Napier.i(tag = TAG) {
                                "Snapshot ready. size=${snapshot?.width ?: -1}x${snapshot?.height ?: -1}"
                            }
                        }
                    }
                }
            }
        },
        update = {
            googleMap?.let { map ->
                renderMapState(
                    map = map,
                    screenState = screenState,
                    routeGeometries = if (screenState is HomeMapScreenState.Navigating || screenState is HomeMapScreenState.Arrived) {
                        liveRoutes.map { it.geometry }
                    } else {
                        routeResults.map { it.item.geometry }
                    },
                    selectedRouteIndex = if (screenState is HomeMapScreenState.Navigating || screenState is HomeMapScreenState.Arrived) {
                        0
                    } else {
                        selectedRouteIndex
                    },
                    currentLocation = currentLocation,
                    currentBearing = currentBearing,
                    vehiclePuckBitmap = vehiclePuckBitmap,
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
    map.isBuildingsEnabled = true
    map.mapType = GoogleMap.MAP_TYPE_NORMAL
    map.isMyLocationEnabled = hasLocationPermission

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

private fun moveCameraToViewportState(
    map: GoogleMap,
    cameraState: HomeMapCameraState,
) {
    map.moveCamera(
        CameraUpdateFactory.newCameraPosition(
            CameraPosition.Builder()
                .target(LatLng(cameraState.latitude, cameraState.longitude))
                .zoom(cameraState.zoom)
                .tilt(cameraState.tilt)
                .bearing(cameraState.bearing.toFloat())
                .build(),
        ),
    )
}

private fun renderMapState(
    map: GoogleMap,
    screenState: HomeMapScreenState,
    routeGeometries: List<List<RoutePoint>>,
    selectedRouteIndex: Int,
    currentLocation: RoutePoint?,
    currentBearing: Float,
    vehiclePuckBitmap: Bitmap,
) {
    map.clear()
    renderRoutes(map, routeGeometries, selectedRouteIndex)
    renderMarkers(map, screenState)
    renderVehiclePuck(
        map = map,
        currentLocation = currentLocation,
        currentBearing = currentBearing,
        vehiclePuckBitmap = vehiclePuckBitmap,
    )
}

private fun renderRoutes(
    map: GoogleMap,
    routeGeometries: List<List<RoutePoint>>,
    selectedRouteIndex: Int,
) {
    routeGeometries.forEachIndexed { index, geometry ->
        if (index == selectedRouteIndex) return@forEachIndexed
        map.addPolyline(
            PolylineOptions()
                .addAll(geometry.map(RoutePoint::toLatLng))
                .color(GOOGLE_ROUTE_GRAY)
                .width(SECONDARY_ROUTE_WIDTH)
                .clickable(true)
                .zIndex(1f),
        ).tag = index
    }

    routeGeometries.getOrNull(selectedRouteIndex)?.let { geometry ->
        map.addPolyline(
            PolylineOptions()
                .addAll(geometry.map(RoutePoint::toLatLng))
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

private fun renderVehiclePuck(
    map: GoogleMap,
    currentLocation: RoutePoint?,
    currentBearing: Float,
    vehiclePuckBitmap: Bitmap,
) {
    val location = currentLocation ?: return

    map.addMarker(
        MarkerOptions()
            .position(location.toLatLng())
            .icon(BitmapDescriptorFactory.fromBitmap(vehiclePuckBitmap))
            .anchor(0.5f, 0.5f)
            .flat(true)
            .rotation(currentBearing)
            .zIndex(4f),
    )
}

@Composable
private fun rememberMapViewWithLifecycle(lifecycleOwner: LifecycleOwner): MapView {
    val context = LocalContext.current
    val mapView = remember {
        runCatching { MapsInitializer.initialize(context.applicationContext) }
            .onSuccess { Napier.i(tag = TAG) { "MapsInitializer.initialize success" } }
            .onFailure { Napier.e(it, tag = TAG) { "MapsInitializer.initialize failed" } }
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

private fun Context.createBitmap(drawableRes: Int): Bitmap {
    val drawable = checkNotNull(AppCompatResources.getDrawable(this, drawableRes))
    val width = drawable.intrinsicWidth.coerceAtLeast(1)
    val height = drawable.intrinsicHeight.coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, canvas.width, canvas.height)
    drawable.draw(canvas)
    return bitmap
}
