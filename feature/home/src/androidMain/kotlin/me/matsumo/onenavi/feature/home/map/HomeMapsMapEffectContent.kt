package me.matsumo.onenavi.feature.home.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.savedstate.SavedStateRegistryOwner
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.PolylineOptions
import com.google.android.libraries.navigation.NavigationView
import kotlinx.collections.immutable.ImmutableList
import me.matsumo.onenavi.core.model.RoutePoint
import me.matsumo.onenavi.core.model.RouteWaypoint
import me.matsumo.onenavi.core.navigation.CameraManager
import me.matsumo.onenavi.core.navigation.RouteManager
import me.matsumo.onenavi.feature.home.R
import me.matsumo.onenavi.feature.home.map.state.HomeMapScreenState

private const val NAVIGATION_VIEW_SAVED_STATE_KEY = "home_navigation_view_state"
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
    val savedStateRegistryOwner = remember(context) { context.findSavedStateRegistryOwner() }
    val restoredNavigationState = remember(savedStateRegistryOwner) {
        savedStateRegistryOwner?.savedStateRegistry?.consumeRestoredStateForKey(NAVIGATION_VIEW_SAVED_STATE_KEY)
    }
    val hasLocationPermission = remember(context) {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
    }
    val vehiclePuckBitmap = remember(context) {
        context.createBitmap(R.drawable.ic_vehicle_puck)
    }
    val currentScreenState = rememberUpdatedState(screenState)
    val currentSelectedRouteIndex = rememberUpdatedState(selectedRouteIndex)
    val currentOnRouteSelected = rememberUpdatedState(onRouteSelected)
    val currentOnMapLandmarkSelected = rememberUpdatedState(onMapLandmarkSelected)
    val currentOnMapReady = rememberUpdatedState(onMapReady)
    val liveRoutes by routeManager.routes.collectAsStateWithLifecycle()

    var googleMap by remember { mutableStateOf<GoogleMap?>(null) }
    var vehiclePuckIcon by remember { mutableStateOf<BitmapDescriptor?>(null) }
    val navigationView = remember(context, restoredNavigationState) {
        NavigationView(context).apply {
            setNavigationUiEnabled(false)
            getMapAsync { map ->
                googleMap = map
                vehiclePuckIcon = BitmapDescriptorFactory.fromBitmap(vehiclePuckBitmap)
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
                renderMapState(
                    map = map,
                    screenState = currentScreenState.value,
                    routeGeometries = routeResults.map { it.item.geometry },
                    selectedRouteIndex = currentSelectedRouteIndex.value,
                    currentLocation = currentLocation,
                    currentBearing = currentBearing,
                    vehiclePuckIcon = vehiclePuckIcon,
                )
            }
        }
    }
    val navigationViewLifecycleDelegate = remember(navigationView, restoredNavigationState) {
        NavigationViewLifecycleDelegate(
            navigationView = navigationView,
            initialSavedState = restoredNavigationState,
        )
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { navigationView },
        update = { navigationView ->
            navigationView.setNavigationUiEnabled(false)
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
                    vehiclePuckIcon = vehiclePuckIcon,
                )
            }
        },
    )

    LaunchedEffect(routeResults) {
        routeManager.setRoutes(routeResults.map { it.googleRoute })
        cameraManager.onRouteChanged(routeResults.firstOrNull()?.googleRoute)
    }

    DisposableEffect(lifecycleOwner, navigationViewLifecycleDelegate) {
        val observer = object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                navigationViewLifecycleDelegate.onStart()
            }

            override fun onResume(owner: LifecycleOwner) {
                navigationViewLifecycleDelegate.onResume()
            }

            override fun onPause(owner: LifecycleOwner) {
                navigationViewLifecycleDelegate.onPause()
            }

            override fun onStop(owner: LifecycleOwner) {
                navigationViewLifecycleDelegate.onStop()
            }

            override fun onDestroy(owner: LifecycleOwner) {
                navigationViewLifecycleDelegate.onDestroy()
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)
        navigationViewLifecycleDelegate.moveTo(lifecycleOwner.lifecycle.currentState)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    DisposableEffect(savedStateRegistryOwner, navigationViewLifecycleDelegate) {
        val owner = savedStateRegistryOwner ?: return@DisposableEffect onDispose {}
        owner.savedStateRegistry.unregisterSavedStateProvider(NAVIGATION_VIEW_SAVED_STATE_KEY)
        owner.savedStateRegistry.registerSavedStateProvider(NAVIGATION_VIEW_SAVED_STATE_KEY) {
            navigationViewLifecycleDelegate.saveState()
        }
        onDispose {
            owner.savedStateRegistry.unregisterSavedStateProvider(NAVIGATION_VIEW_SAVED_STATE_KEY)
        }
    }

    DisposableEffect(context, navigationViewLifecycleDelegate) {
        context.applicationContext.registerComponentCallbacks(navigationViewLifecycleDelegate)
        onDispose {
            context.applicationContext.unregisterComponentCallbacks(navigationViewLifecycleDelegate)
            googleMap = null
            vehiclePuckIcon = null
            viewportState.detachMap()
            cameraManager.teardownCamera()
            navigationViewLifecycleDelegate.onDestroy()
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
    routeGeometries: List<List<RoutePoint>>,
    selectedRouteIndex: Int,
    currentLocation: RoutePoint?,
    currentBearing: Float,
    vehiclePuckIcon: BitmapDescriptor?,
) {
    map.clear()
    renderRoutes(map, routeGeometries, selectedRouteIndex)
    renderMarkers(map, screenState)
    renderVehiclePuck(
        map = map,
        currentLocation = currentLocation,
        currentBearing = currentBearing,
        vehiclePuckIcon = vehiclePuckIcon,
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
    vehiclePuckIcon: BitmapDescriptor?,
) {
    val location = currentLocation ?: return
    val icon = vehiclePuckIcon ?: return

    map.addMarker(
        MarkerOptions()
            .position(location.toLatLng())
            .icon(icon)
            .anchor(0.5f, 0.5f)
            .flat(true)
            .rotation(currentBearing)
            .zIndex(4f),
    )
}

private tailrec fun Context.findSavedStateRegistryOwner(): SavedStateRegistryOwner? {
    return when (this) {
        is SavedStateRegistryOwner -> this
        is ContextWrapper -> baseContext.findSavedStateRegistryOwner()
        else -> null
    }
}

private class NavigationViewLifecycleDelegate(
    private val navigationView: NavigationView,
    initialSavedState: android.os.Bundle?,
) : ComponentCallbacks2 {
    private var isCreated = false
    private var isStarted = false
    private var isResumed = false
    private var isDestroyed = false

    init {
        navigationView.onCreate(initialSavedState)
        isCreated = true
    }

    fun moveTo(state: Lifecycle.State) {
        if (isDestroyed) return
        when {
            state == Lifecycle.State.DESTROYED -> onDestroy()
            state.isAtLeast(Lifecycle.State.RESUMED) -> {
                onStart()
                onResume()
            }

            state.isAtLeast(Lifecycle.State.STARTED) -> {
                if (isResumed) onPause()
                onStart()
            }

            state.isAtLeast(Lifecycle.State.CREATED) -> {
                if (isResumed) onPause()
                if (isStarted) onStop()
            }

            else -> Unit
        }
    }

    fun onStart() {
        if (!isCreated || isDestroyed || isStarted) return
        navigationView.onStart()
        isStarted = true
    }

    fun onResume() {
        if (!isCreated || isDestroyed || isResumed) return
        if (!isStarted) {
            onStart()
        }
        navigationView.onResume()
        isResumed = true
    }

    fun onPause() {
        if (!isCreated || isDestroyed || !isResumed) return
        navigationView.onPause()
        isResumed = false
    }

    fun onStop() {
        if (!isCreated || isDestroyed || !isStarted) return
        if (isResumed) {
            onPause()
        }
        navigationView.onStop()
        isStarted = false
    }

    fun onDestroy() {
        if (!isCreated || isDestroyed) return
        onStop()
        navigationView.onDestroy()
        isDestroyed = true
    }

    fun saveState(): android.os.Bundle {
        return android.os.Bundle().also(navigationView::onSaveInstanceState)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        if (!isDestroyed) {
            navigationView.onConfigurationChanged(newConfig)
        }
    }

    override fun onTrimMemory(level: Int) {
        if (!isDestroyed) {
            navigationView.onTrimMemory(level)
        }
    }

    @Deprecated("Deprecated in Android")
    @Suppress("DEPRECATION")
    override fun onLowMemory() {
        onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
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
