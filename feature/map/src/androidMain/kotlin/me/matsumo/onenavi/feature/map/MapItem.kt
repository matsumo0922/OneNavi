package me.matsumo.onenavi.feature.map

import android.annotation.SuppressLint
import android.os.Bundle
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.gms.maps.GoogleMap
import com.google.android.libraries.navigation.NavigationView

@Composable
internal fun MapItem(
    googleMap: GoogleMap?,
    onMapChanged: (GoogleMap?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val navigationView = rememberNavigationViewWithLifecycle()

    googleMap?.let {
        MapEffect(
            navigationView = navigationView,
            googleMap = it,
            onClear = { onMapChanged(null) },
        )
    }

    Box(modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                navigationView.apply {
                    getMapAsync { map ->
                        onMapChanged(map)
                    }
                }
            }
        )
    }
}

@Composable
private fun rememberNavigationViewWithLifecycle(): NavigationView {
    val context = LocalContext.current

    return remember(context) {
        NavigationView(context).apply {
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
    onClear: () -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(googleMap) {
        googleMap.isBuildingsEnabled = true
        googleMap.isMyLocationEnabled = true

        googleMap.uiSettings.apply {
            isCompassEnabled = false
            isMapToolbarEnabled = false
            isMyLocationButtonEnabled = false
            isZoomControlsEnabled = false
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
