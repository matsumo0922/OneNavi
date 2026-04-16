package me.matsumo.onenavi.feature.home.map

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.appcompat.content.res.AppCompatResources
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.maps.MapsInitializer
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapEffect
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polyline
import io.github.aakira.napier.Napier
import kotlinx.collections.immutable.ImmutableList
import me.matsumo.onenavi.core.model.RoutePoint
import me.matsumo.onenavi.core.model.RouteWaypoint
import me.matsumo.onenavi.core.navigation.CameraManager
import me.matsumo.onenavi.feature.home.R
import me.matsumo.onenavi.feature.home.map.components.HomeMapRouteCallout
import me.matsumo.onenavi.feature.home.map.state.HomeMapScreenState
import me.matsumo.onenavi.feature.home.map.util.CalloutSize
import me.matsumo.onenavi.feature.home.map.util.GoogleMapCalloutPositioner

private const val TAG = "HomeMapsMap"
private const val PRIMARY_ROUTE_WIDTH = 14f
private const val SECONDARY_ROUTE_WIDTH = 9f
private const val GOOGLE_BLUE = 0xFF1A73E8
private const val GOOGLE_ROUTE_GRAY = 0xFF78909C
private val ROUTE_CALLOUT_SIZE = CalloutSize(
    widthPx = 132.0,
    heightPx = 72.0,
)

@Composable
internal fun HomeMapsMapEffectContent(
    viewportState: HomeMapViewportState,
    screenState: HomeMapScreenState,
    routeResults: ImmutableList<RouteResult>,
    selectedRouteIndex: Int,
    currentLocation: RoutePoint?,
    currentBearing: Float,
    cameraManager: CameraManager,
    onMapLandmarkSelected: (name: String?, latitude: Double, longitude: Double) -> Unit,
    onRouteSelected: (index: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val hasLocationPermission = remember(context) {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
    }
    val mapPadding by cameraManager.mapPadding.collectAsStateWithLifecycle()
    val vehiclePuckBitmap = remember(context) {
        context.createBitmap(R.drawable.ic_vehicle_puck)
    }
    var routeCalloutPositions by remember(routeResults) {
        mutableStateOf(List(routeResults.size) { null as LatLng? })
    }

    var vehiclePuckIcon by remember { mutableStateOf<BitmapDescriptor?>(null) }

    LaunchedEffect(context, vehiclePuckBitmap) {
        runCatching {
            MapsInitializer.initialize(context.applicationContext)
            BitmapDescriptorFactory.fromBitmap(vehiclePuckBitmap)
        }.onSuccess { icon ->
            vehiclePuckIcon = icon
        }.onFailure { error ->
            Napier.e(error, tag = TAG) { "Failed to create vehicle puck icon." }
        }
    }

    GoogleMap(
        modifier = modifier.fillMaxSize(),
        cameraPositionState = viewportState.cameraPositionState,
        properties = MapProperties(
            isBuildingEnabled = true,
            isMyLocationEnabled = hasLocationPermission && vehiclePuckIcon == null,
        ),
        uiSettings = MapUiSettings(
            compassEnabled = false,
            mapToolbarEnabled = false,
            myLocationButtonEnabled = false,
            zoomControlsEnabled = false,
        ),
        contentPadding = PaddingValues(
            start = with(density) { mapPadding.left.toDp() },
            top = with(density) { mapPadding.top.toDp() },
            end = with(density) { mapPadding.right.toDp() },
            bottom = with(density) { mapPadding.bottom.toDp() },
        ),
        onMapLongClick = { latLng ->
            onMapLandmarkSelected(null, latLng.latitude, latLng.longitude)
        },
        onPOIClick = { poi ->
            onMapLandmarkSelected(poi.name, poi.latLng.latitude, poi.latLng.longitude)
        },
        onMapLoaded = {
            val camera = viewportState.cameraPositionState.position
            Napier.d(tag = TAG) {
                "Map loaded: target=${camera.target}, zoom=${camera.zoom}, tilt=${camera.tilt}, bearing=${camera.bearing}"
            }
        },
    ) {
        MapEffect(
            routeResults,
            viewportState.cameraPositionState.position,
        ) { googleMap ->
            routeCalloutPositions = GoogleMapCalloutPositioner.computePositions(
                googleMap = googleMap,
                geometries = routeResults.map { it.item.geometry },
                calloutSize = ROUTE_CALLOUT_SIZE,
            )
        }

        routeResults.forEachIndexed { index, routeResult ->
            Polyline(
                points = routeResult.item.geometry.map(RoutePoint::toLatLng),
                color = Color(if (index == selectedRouteIndex) GOOGLE_BLUE else GOOGLE_ROUTE_GRAY),
                width = if (index == selectedRouteIndex) PRIMARY_ROUTE_WIDTH else SECONDARY_ROUTE_WIDTH,
                clickable = true,
                zIndex = if (index == selectedRouteIndex) 2f else 1f,
                onClick = {
                    if (index != selectedRouteIndex) {
                        onRouteSelected(index)
                    }
                },
            )
        }

        currentLocation?.let { location ->
            vehiclePuckIcon?.let { icon ->
                Marker(
                    state = MarkerState(position = location.toLatLng()),
                    icon = icon,
                    flat = true,
                    anchor = Offset(0.5f, 0.5f),
                    rotation = currentBearing,
                    zIndex = 4f,
                )
            }
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
                routeResults.forEachIndexed { index, routeResult ->
                    routeCalloutPositions.getOrNull(index)?.let { position ->
                        HomeMapRouteCallout(
                            position = position,
                            routeResult = routeResult,
                            isPrimary = index == selectedRouteIndex,
                            onClick = {
                                if (index != selectedRouteIndex) {
                                    onRouteSelected(index)
                                }
                            },
                        )
                    }
                }
                screenState.waypoints.lastOrNull()?.let { waypoint ->
                    Marker(
                        state = MarkerState(position = waypoint.toRoutePoint().toLatLng()),
                        icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED),
                    )
                }
                screenState.waypoints.drop(1).dropLast(1).forEachIndexed { index, waypoint ->
                    Marker(
                        state = MarkerState(position = waypoint.toRoutePoint().toLatLng()),
                        title = "K${index + 1}",
                    )
                }
            }

            is HomeMapScreenState.Navigating,
            is HomeMapScreenState.Arrived,
            -> Unit
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
