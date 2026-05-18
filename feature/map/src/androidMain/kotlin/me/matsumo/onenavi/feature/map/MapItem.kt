package me.matsumo.onenavi.feature.map

import android.annotation.SuppressLint
import android.os.Bundle
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.GoogleMapOptions
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.PointOfInterest
import com.google.android.libraries.navigation.NavigationView
import me.matsumo.onenavi.core.navigation.NavigationSdkManager
import me.matsumo.onenavi.feature.map.state.MapCameraState
import org.koin.compose.koinInject

@Composable
internal fun MapItem(
    googleMap: GoogleMap?,
    cameraState: MapCameraState,
    onMapUpdate: (GoogleMap?) -> Unit,
    onPointOfInterestClicked: (PointOfInterest) -> Unit,
    onMapLongClicked: (LatLng) -> Unit,
    modifier: Modifier = Modifier,
) {
    val navigationView = rememberNavigationViewWithLifecycle()
    val navigationSdkManager = koinInject<NavigationSdkManager>()
    val isNavigatorReady by navigationSdkManager.isNavigatorReady.collectAsStateWithLifecycle()

    googleMap?.let {
        MapEffect(
            navigationView = navigationView,
            googleMap = it,
            onPointOfInterestClicked = onPointOfInterestClicked,
            onMapLongClicked = onMapLongClicked,
            onClear = { onMapUpdate(null) },
        )

        LaunchedEffect(it) {
            cameraState.attachMap(it)
        }
    }

    LaunchedEffect(isNavigatorReady) {
        if (isNavigatorReady) {
            cameraState.onNavigatorReady()
        }
    }

    Box(modifier) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { size ->
                    cameraState.updateViewportWidth(size.width)
                },
            factory = {
                navigationView.apply {
                    getMapAsync { map ->
                        onMapUpdate(map)
                    }
                }
            },
        )
    }
}

@Composable
private fun rememberNavigationViewWithLifecycle(): NavigationView {
    val context = LocalContext.current

    return remember(context) {
        val mapOptions = GoogleMapOptions()
            .mapType(GoogleMap.MAP_TYPE_NORMAL)
            .liteMode(false)
            .tiltGesturesEnabled(true)
            .rotateGesturesEnabled(true)
            .zoomControlsEnabled(false)
            .compassEnabled(false)
            .mapToolbarEnabled(false)

        NavigationView(context, mapOptions).apply {
            onCreate(Bundle())

            isNavigationUiEnabled = false

            setHeaderEnabled(false)
            setEtaCardEnabled(false)
            setTripProgressBarEnabled(false)
            setRecenterButtonEnabled(false)
            setSpeedLimitIconEnabled(false)
            setSpeedometerEnabled(false)
            setTrafficIncidentCardsEnabled(false)
            setTrafficPromptsEnabled(false)
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun MapEffect(
    navigationView: NavigationView,
    googleMap: GoogleMap,
    onPointOfInterestClicked: (PointOfInterest) -> Unit,
    onMapLongClicked: (LatLng) -> Unit,
    onClear: () -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnPointOfInterestClicked by rememberUpdatedState(onPointOfInterestClicked)
    val currentOnMapLongClicked by rememberUpdatedState(onMapLongClicked)

    LaunchedEffect(googleMap) {
        googleMap.mapType = GoogleMap.MAP_TYPE_NORMAL
        googleMap.isBuildingsEnabled = true
        googleMap.isTrafficEnabled = true
        googleMap.isMyLocationEnabled = true

        googleMap.uiSettings.apply {
            isCompassEnabled = false
            isMapToolbarEnabled = false
            isMyLocationButtonEnabled = false
            isZoomControlsEnabled = false
        }
    }

    DisposableEffect(googleMap) {
        googleMap.setOnPoiClickListener { pointOfInterest ->
            currentOnPointOfInterestClicked(pointOfInterest)
        }
        googleMap.setOnMapLongClickListener { latLng ->
            currentOnMapLongClicked(latLng)
        }

        onDispose {
            googleMap.setOnPoiClickListener(null)
            googleMap.setOnMapLongClickListener(null)
        }
    }

    DisposableEffect(lifecycleOwner, navigationView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> navigationView.onStart()
                Lifecycle.Event.ON_RESUME -> navigationView.onResume()
                Lifecycle.Event.ON_PAUSE -> navigationView.onPause()
                Lifecycle.Event.ON_STOP -> navigationView.onStop()
                Lifecycle.Event.ON_DESTROY -> navigationView.onDestroy()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            onClear.invoke()
        }
    }
}
