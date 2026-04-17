package me.matsumo.onenavi.feature.home.map

import android.Manifest
import android.animation.ValueAnimator
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.view.animation.LinearInterpolator
import androidx.appcompat.content.res.AppCompatResources
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.MapsInitializer
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions
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
private const val ROUTE_CALLOUT_ARROW_HEIGHT_PX = 10
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
    cameraFollowSpec: CameraFollowSpec?,
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
    val mapPadding by cameraManager.mapPadding.collectAsStateWithLifecycle()
    val vehiclePuckBitmap = remember(context) {
        context.createBitmap(R.drawable.ic_vehicle_puck)
    }
    val mapView = rememberMapViewWithLifecycle()
    val overlayObjects = remember { HomeMapOverlayObjects() }
    val currentOnMapLandmarkSelected = rememberUpdatedState(onMapLandmarkSelected)
    val currentOnRouteSelected = rememberUpdatedState(onRouteSelected)
    val currentSelectedRouteIndex = rememberUpdatedState(selectedRouteIndex)
    val currentRouteResults = rememberUpdatedState(routeResults)
    val currentScreenState = rememberUpdatedState(screenState)

    var googleMap by remember { mutableStateOf<GoogleMap?>(null) }
    var vehiclePuckIcon by remember { mutableStateOf<BitmapDescriptor?>(null) }
    var routeCalloutOffsets by remember(routeResults) {
        mutableStateOf(List(routeResults.size) { null as IntOffset? })
    }

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

    LaunchedEffect(googleMap, mapPadding, hasLocationPermission, vehiclePuckIcon) {
        val map = googleMap ?: return@LaunchedEffect
        map.setPadding(
            mapPadding.left,
            mapPadding.top,
            mapPadding.right,
            mapPadding.bottom,
        )
        map.isBuildingsEnabled = true
        map.isMyLocationEnabled = hasLocationPermission && vehiclePuckIcon == null
        map.uiSettings.apply {
            isCompassEnabled = false
            isMapToolbarEnabled = false
            isMyLocationButtonEnabled = false
            isZoomControlsEnabled = false
        }
    }

    LaunchedEffect(googleMap, routeResults, selectedRouteIndex, screenState) {
        val map = googleMap ?: return@LaunchedEffect
        overlayObjects.replaceRoutePolylines(
            googleMap = map,
            routeResults = routeResults,
            selectedRouteIndex = selectedRouteIndex,
        )
        overlayObjects.replaceStaticMarkers(
            googleMap = map,
            screenState = screenState,
        )
        routeCalloutOffsets = computeRouteCalloutOffsets(
            googleMap = map,
            routeResults = routeResults,
            screenState = screenState,
        )
    }

    LaunchedEffect(googleMap, currentLocation, currentBearing, vehiclePuckIcon, hasLocationPermission, cameraFollowSpec) {
        val map = googleMap ?: return@LaunchedEffect
        overlayObjects.updateVehicleMarker(
            googleMap = map,
            currentLocation = currentLocation,
            currentBearing = currentBearing,
            vehiclePuckIcon = vehiclePuckIcon,
            hasLocationPermission = hasLocationPermission,
            cameraFollowSpec = cameraFollowSpec,
        )
    }

    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            googleMap?.let(viewportState::clearMap)
            googleMap = null
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                mapView.apply {
                    getMapAsync { map ->
                        googleMap = map
                        viewportState.attachMap(map)
                        map.setOnMapLongClickListener { latLng ->
                            currentOnMapLandmarkSelected.value(null, latLng.latitude, latLng.longitude)
                        }
                        map.setOnPoiClickListener { poi ->
                            currentOnMapLandmarkSelected.value(poi.name, poi.latLng.latitude, poi.latLng.longitude)
                        }
                        map.setOnPolylineClickListener { polyline ->
                            val routeIndex = polyline.tag as? Int ?: return@setOnPolylineClickListener
                            if (routeIndex != currentSelectedRouteIndex.value) {
                                currentOnRouteSelected.value(routeIndex)
                            }
                        }
                        map.setOnCameraMoveStartedListener { reason ->
                            viewportState.setGestureInProgress(
                                reason == GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE,
                            )
                        }
                        map.setOnCameraMoveListener {
                            viewportState.updateCameraPosition(map.cameraPosition)
                        }
                        map.setOnCameraIdleListener {
                            viewportState.updateCameraPosition(map.cameraPosition)
                            viewportState.setGestureInProgress(false)
                            routeCalloutOffsets = computeRouteCalloutOffsets(
                                googleMap = map,
                                routeResults = currentRouteResults.value,
                                screenState = currentScreenState.value,
                            )
                        }
                        map.setOnMapLoadedCallback {
                            val camera = map.cameraPosition
                            Napier.d(tag = TAG) {
                                "Map loaded: target=${camera.target}, zoom=${camera.zoom}, tilt=${camera.tilt}, bearing=${camera.bearing}"
                            }
                            routeCalloutOffsets = computeRouteCalloutOffsets(
                                googleMap = map,
                                routeResults = currentRouteResults.value,
                                screenState = currentScreenState.value,
                            )
                        }
                    }
                }
            },
        )

        if (screenState is HomeMapScreenState.RoutePreview) {
            routeCalloutOffsets.forEachIndexed { index, offset ->
                val routeResult = routeResults.getOrNull(index) ?: return@forEachIndexed
                offset?.let {
                    HomeMapRouteCallout(
                        screenOffset = it,
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
        }
    }
}

private fun computeRouteCalloutOffsets(
    googleMap: GoogleMap,
    routeResults: ImmutableList<RouteResult>,
    screenState: HomeMapScreenState,
): List<IntOffset?> {
    if (screenState !is HomeMapScreenState.RoutePreview) {
        return List(routeResults.size) { null }
    }

    return GoogleMapCalloutPositioner.computePositions(
        googleMap = googleMap,
        geometries = routeResults.map { it.item.geometry },
        calloutSize = ROUTE_CALLOUT_SIZE,
    ).map { position ->
        position?.let { latLng ->
            val screenPoint = googleMap.projection.toScreenLocation(latLng)
            IntOffset(
                x = screenPoint.x - (ROUTE_CALLOUT_SIZE.widthPx / 2.0).toInt(),
                y = screenPoint.y - ROUTE_CALLOUT_SIZE.heightPx.toInt() - ROUTE_CALLOUT_ARROW_HEIGHT_PX,
            )
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

@Composable
private fun rememberMapViewWithLifecycle(): MapView {
    val context = LocalContext.current
    return remember(context) {
        MapView(context).apply {
            onCreate(Bundle())
        }
    }
}

private class HomeMapOverlayObjects {

    private val routePolylines = mutableListOf<Polyline>()
    private val staticMarkers = mutableListOf<Marker>()
    private var vehicleMarker: Marker? = null
    private var vehicleAnimator: ValueAnimator? = null
    private var lastAppliedPosition: LatLng? = null
    private var lastAppliedBearing: Float = 0f

    fun replaceRoutePolylines(
        googleMap: GoogleMap,
        routeResults: ImmutableList<RouteResult>,
        selectedRouteIndex: Int,
    ) {
        routePolylines.forEach(Polyline::remove)
        routePolylines.clear()

        routeResults.forEachIndexed { index, routeResult ->
            val polyline = googleMap.addPolyline(
                PolylineOptions()
                    .addAll(routeResult.item.geometry.map(RoutePoint::toLatLng))
                    .color(if (index == selectedRouteIndex) Color(GOOGLE_BLUE).toArgb() else Color(GOOGLE_ROUTE_GRAY).toArgb())
                    .width(if (index == selectedRouteIndex) PRIMARY_ROUTE_WIDTH else SECONDARY_ROUTE_WIDTH)
                    .clickable(true)
                    .zIndex(if (index == selectedRouteIndex) 2f else 1f),
            )
            polyline.tag = index
            routePolylines += polyline
        }
    }

    fun replaceStaticMarkers(
        googleMap: GoogleMap,
        screenState: HomeMapScreenState,
    ) {
        staticMarkers.forEach(Marker::remove)
        staticMarkers.clear()

        when (screenState) {
            is HomeMapScreenState.Browsing,
            is HomeMapScreenState.Navigating,
            is HomeMapScreenState.Arrived,
            -> Unit

            is HomeMapScreenState.SearchResultsList -> {
                screenState.results.forEachIndexed { index, result ->
                    googleMap.addMarker(
                        MarkerOptions()
                            .position(LatLng(result.latitude, result.longitude))
                            .title(result.name)
                            .snippet((index + 1).toString())
                            .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)),
                    )?.let(staticMarkers::add)
                }
            }

            is HomeMapScreenState.PlaceDetails -> {
                googleMap.addMarker(
                    MarkerOptions()
                        .position(LatLng(screenState.place.latitude, screenState.place.longitude))
                        .title(screenState.place.name)
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)),
                )?.let(staticMarkers::add)
            }

            is HomeMapScreenState.RoutePreview -> {
                screenState.waypoints.lastOrNull()?.let { waypoint ->
                    googleMap.addMarker(
                        MarkerOptions()
                            .position(waypoint.toRoutePoint().toLatLng())
                            .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)),
                    )?.let(staticMarkers::add)
                }
                screenState.waypoints.drop(1).dropLast(1).forEachIndexed { index, waypoint ->
                    googleMap.addMarker(
                        MarkerOptions()
                            .position(waypoint.toRoutePoint().toLatLng())
                            .title("K${index + 1}"),
                    )?.let(staticMarkers::add)
                }
            }
        }
    }

    fun updateVehicleMarker(
        googleMap: GoogleMap,
        currentLocation: RoutePoint?,
        currentBearing: Float,
        vehiclePuckIcon: BitmapDescriptor?,
        hasLocationPermission: Boolean,
        cameraFollowSpec: CameraFollowSpec?,
    ) {
        googleMap.isMyLocationEnabled = hasLocationPermission && vehiclePuckIcon == null

        if (vehiclePuckIcon == null || currentLocation == null) {
            vehicleAnimator?.cancel()
            vehicleAnimator = null
            vehicleMarker?.remove()
            vehicleMarker = null
            lastAppliedPosition = null
            return
        }

        val targetPosition = currentLocation.toLatLng()
        val marker = vehicleMarker
        if (marker == null) {
            vehicleMarker = googleMap.addMarker(
                MarkerOptions()
                    .position(targetPosition)
                    .icon(vehiclePuckIcon)
                    .flat(true)
                    .anchor(0.5f, 0.5f)
                    .rotation(currentBearing)
                    .zIndex(4f),
            )
            lastAppliedPosition = targetPosition
            lastAppliedBearing = currentBearing
            return
        }

        marker.setIcon(vehiclePuckIcon)

        val fromPosition = lastAppliedPosition ?: targetPosition
        val fromBearing = lastAppliedBearing
        val bearingDelta = shortestBearingDelta(fromBearing, currentBearing)
        val toBearing = fromBearing + bearingDelta

        vehicleAnimator?.cancel()
        vehicleAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = VEHICLE_ANIMATION_DURATION_MS
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                val fraction = animator.animatedValue as Float
                val latitude = fromPosition.latitude +
                    (targetPosition.latitude - fromPosition.latitude) * fraction
                val longitude = fromPosition.longitude +
                    (targetPosition.longitude - fromPosition.longitude) * fraction
                val bearing = fromBearing + (toBearing - fromBearing) * fraction
                val latLng = LatLng(latitude, longitude)

                marker.position = latLng
                marker.rotation = bearing

                cameraFollowSpec?.let { spec ->
                    googleMap.moveCamera(
                        CameraUpdateFactory.newCameraPosition(
                            CameraPosition.Builder()
                                .target(latLng)
                                .zoom(spec.zoom)
                                .tilt(spec.tilt)
                                .bearing(if (spec.useLocationBearing) bearing else 0f)
                                .build(),
                        ),
                    )
                }
            }
            start()
        }

        lastAppliedPosition = targetPosition
        lastAppliedBearing = currentBearing
    }

    companion object {
        private const val VEHICLE_ANIMATION_DURATION_MS = 1000L
    }
}

/**
 * カメラ追従の指定仕様。null ならカメラ追従せず、マーカー補間のみを行う。
 *
 * @param zoom カメラのズーム
 * @param tilt カメラのチルト
 * @param useLocationBearing true の場合は自車の進行方向、false の場合は常に北を向ける
 */
@Immutable
internal data class CameraFollowSpec(
    val zoom: Float,
    val tilt: Float,
    val useLocationBearing: Boolean,
)

private fun shortestBearingDelta(from: Float, to: Float): Float {
    return ((to - from + 540f) % 360f) - 180f
}
