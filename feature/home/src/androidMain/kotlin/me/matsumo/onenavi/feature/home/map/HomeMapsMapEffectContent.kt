package me.matsumo.onenavi.feature.home.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.content.res.Configuration
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
import androidx.savedstate.SavedStateRegistryOwner
import com.google.android.gms.maps.GoogleMap
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
    val currentRouteResults = rememberUpdatedState(routeResults)
    val currentScreenState = rememberUpdatedState(screenState)
    val currentSelectedRouteIndex = rememberUpdatedState(selectedRouteIndex)
    val currentOnRouteSelected = rememberUpdatedState(onRouteSelected)
    val currentOnMapLandmarkSelected = rememberUpdatedState(onMapLandmarkSelected)
    val currentOnMapReady = rememberUpdatedState(onMapReady)

    var googleMap by remember { mutableStateOf<GoogleMap?>(null) }
    var navigationViewLifecycleDelegate by remember { mutableStateOf<NavigationViewLifecycleDelegate?>(null) }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { viewContext ->
            NavigationView(viewContext).apply {
                navigationViewLifecycleDelegate = NavigationViewLifecycleDelegate(
                    navigationView = this,
                    initialSavedState = restoredNavigationState,
                ).also { delegate ->
                    viewContext.applicationContext.registerComponentCallbacks(delegate)
                    delegate.moveTo(lifecycleOwner.lifecycle.currentState)
                }

                setNavigationUiEnabled(false)
                getMapAsync { map ->
                    googleMap = map
                    viewportState.attachMap(map)
                    cameraManager.setupCamera(map)
                    currentOnMapReady.value(map)
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
                        screenState = currentScreenState.value,
                        routeResults = currentRouteResults.value,
                        selectedRouteIndex = currentSelectedRouteIndex.value,
                    )
                }
            }
        },
        update = { navigationView ->
            navigationView.setNavigationUiEnabled(false)
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

    DisposableEffect(lifecycleOwner, navigationViewLifecycleDelegate) {
        val delegate = navigationViewLifecycleDelegate ?: return@DisposableEffect onDispose {}
        val observer = object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                delegate.onStart()
            }

            override fun onResume(owner: LifecycleOwner) {
                delegate.onResume()
            }

            override fun onPause(owner: LifecycleOwner) {
                delegate.onPause()
            }

            override fun onStop(owner: LifecycleOwner) {
                delegate.onStop()
            }

            override fun onDestroy(owner: LifecycleOwner) {
                delegate.onDestroy()
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    DisposableEffect(savedStateRegistryOwner, navigationViewLifecycleDelegate) {
        val owner = savedStateRegistryOwner ?: return@DisposableEffect onDispose {}
        val delegate = navigationViewLifecycleDelegate ?: return@DisposableEffect onDispose {}
        owner.savedStateRegistry.unregisterSavedStateProvider(NAVIGATION_VIEW_SAVED_STATE_KEY)
        owner.savedStateRegistry.registerSavedStateProvider(NAVIGATION_VIEW_SAVED_STATE_KEY) {
            delegate.saveState()
        }
        onDispose {
            owner.savedStateRegistry.unregisterSavedStateProvider(NAVIGATION_VIEW_SAVED_STATE_KEY)
        }
    }

    DisposableEffect(context, navigationViewLifecycleDelegate) {
        val delegate = navigationViewLifecycleDelegate ?: return@DisposableEffect onDispose {}
        onDispose {
            context.applicationContext.unregisterComponentCallbacks(delegate)
            googleMap = null
            viewportState.detachMap()
            cameraManager.teardownCamera()
            delegate.onDestroy()
            navigationViewLifecycleDelegate = null
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
    private var isStarted = false
    private var isResumed = false
    private var isDestroyed = false

    init {
        navigationView.onCreate(initialSavedState)
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
                onStart()
                onPause()
            }
            else -> {
                onPause()
                onStop()
            }
        }
    }

    fun onStart() {
        if (isDestroyed || isStarted) return
        navigationView.onStart()
        isStarted = true
    }

    fun onResume() {
        if (isDestroyed || isResumed) return
        if (!isStarted) {
            onStart()
        }
        navigationView.onResume()
        isResumed = true
    }

    fun onPause() {
        if (isDestroyed || !isResumed) return
        navigationView.onPause()
        isResumed = false
    }

    fun onStop() {
        if (isDestroyed || !isStarted) return
        onPause()
        navigationView.onStop()
        isStarted = false
    }

    fun onDestroy() {
        if (isDestroyed) return
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
