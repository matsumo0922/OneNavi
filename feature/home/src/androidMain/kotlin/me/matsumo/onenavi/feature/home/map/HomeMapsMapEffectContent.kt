package me.matsumo.onenavi.feature.home.map

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.appcompat.content.res.AppCompatResources
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapsInitializer
import com.google.android.gms.maps.model.BitmapDescriptor
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
import com.google.maps.android.compose.internal.GoogleMapsInitializer
import com.google.maps.android.compose.internal.InitializationState
import com.google.maps.android.compose.internal.LocalGoogleMapsInitializer
import com.google.maps.android.compose.rememberCameraPositionState
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import me.matsumo.onenavi.core.model.RoutePoint
import me.matsumo.onenavi.core.model.RouteWaypoint
import me.matsumo.onenavi.core.navigation.CameraManager
import me.matsumo.onenavi.core.navigation.RouteManager
import me.matsumo.onenavi.feature.home.R
import me.matsumo.onenavi.feature.home.map.state.HomeMapScreenState

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
    val hasLocationPermission = remember(context) {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
    }
    val vehiclePuckBitmap = remember(context) {
        context.createBitmap(R.drawable.ic_vehicle_puck)
    }
    val currentOnMapReady = rememberUpdatedState(onMapReady)
    val currentOnRouteSelected = rememberUpdatedState(onRouteSelected)
    val currentOnMapLandmarkSelected = rememberUpdatedState(onMapLandmarkSelected)
    val liveRoutes by routeManager.routes.collectAsStateWithLifecycle()
    val mapsInitializer = remember {
        object : GoogleMapsInitializer {
            override val state = mutableStateOf(InitializationState.UNINITIALIZED)
            override var attributionId: String = ""

            override suspend fun initialize(
                context: Context,
                forceInitialization: Boolean,
            ) {
                if (!forceInitialization && state.value == InitializationState.SUCCESS) {
                    return
                }
                state.value = InitializationState.INITIALIZING
                state.value = if (MapsInitializer.initialize(context.applicationContext) == ConnectionResult.SUCCESS) {
                    InitializationState.SUCCESS
                } else {
                    InitializationState.FAILURE
                }
            }

            override suspend fun reset() {
                state.value = InitializationState.UNINITIALIZED
            }
        }
    }

    val displayedRouteGeometries = remember(screenState, routeResults, liveRoutes) {
        if (screenState is HomeMapScreenState.Navigating || screenState is HomeMapScreenState.Arrived) {
            liveRoutes.map { it.geometry }.toImmutableList()
        } else {
            routeResults.map { it.item.geometry }.toImmutableList()
        }
    }
    val displayedSelectedRouteIndex = if (screenState is HomeMapScreenState.Navigating || screenState is HomeMapScreenState.Arrived) {
        0
    } else {
        selectedRouteIndex
    }

    var googleMap by remember { mutableStateOf<GoogleMap?>(null) }
    var vehiclePuckIcon by remember { mutableStateOf<BitmapDescriptor?>(null) }
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(
            LatLng(
                viewportState.cameraState.latitude,
                viewportState.cameraState.longitude,
            ),
            viewportState.cameraState.zoom,
        )
    }

    CompositionLocalProvider(LocalGoogleMapsInitializer provides mapsInitializer) {
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
        ) {
            displayedRouteGeometries.forEachIndexed { index, geometry ->
                Polyline(
                    points = geometry.map(RoutePoint::toLatLng),
                    color = Color(if (index == displayedSelectedRouteIndex) GOOGLE_BLUE else GOOGLE_ROUTE_GRAY),
                    width = if (index == displayedSelectedRouteIndex) PRIMARY_ROUTE_WIDTH else SECONDARY_ROUTE_WIDTH,
                    clickable = true,
                    zIndex = if (index == displayedSelectedRouteIndex) 2f else 1f,
                    onClick = {
                        if (index != displayedSelectedRouteIndex) {
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

            if (currentLocation != null && vehiclePuckIcon != null) {
                Marker(
                    state = MarkerState(position = currentLocation.toLatLng()),
                    icon = requireNotNull(vehiclePuckIcon),
                    anchor = Offset(0.5f, 0.5f),
                    flat = true,
                    rotation = currentBearing,
                    zIndex = 4f,
                )
            }

            MapEffect(hasLocationPermission) { map ->
                googleMap = map
                if (vehiclePuckIcon == null) {
                    vehiclePuckIcon = BitmapDescriptorFactory.fromBitmap(vehiclePuckBitmap)
                }
                map.mapType = GoogleMap.MAP_TYPE_NORMAL
                map.isBuildingsEnabled = true

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
    }

    LaunchedEffect(routeResults) {
        routeManager.setRoutes(routeResults.map { it.googleRoute })
        cameraManager.onRouteChanged(routeResults.firstOrNull()?.googleRoute)
    }

    DisposableEffect(Unit) {
        onDispose {
            googleMap = null
            vehiclePuckIcon = null
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
