package me.matsumo.onenavi.feature.map

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.GoogleMapOptions
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MapColorScheme
import com.google.android.gms.maps.model.PointOfInterest
import me.matsumo.onenavi.feature.map.state.MapCameraDefaults
import me.matsumo.onenavi.feature.map.state.MapCameraState
import me.matsumo.onenavi.feature.map.state.MapCanvasLayout
import me.matsumo.onenavi.feature.map.state.MapTouchExclusion

@Composable
internal fun MapItem(
    googleMap: GoogleMap?,
    cameraState: MapCameraState,
    isDarkMode: Boolean,
    mapCanvasLayout: MapCanvasLayout,
    mapTouchExclusion: MapTouchExclusion,
    onMapUpdate: (GoogleMap?) -> Unit,
    onPointOfInterestClicked: (PointOfInterest) -> Unit,
    onMapLongClicked: (LatLng) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val mapView = rememberMapViewWithLifecycle(isDarkMode = isDarkMode)
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    val canvasWidthPx = with(density) {
        mapCanvasLayout.width.roundToPx().coerceAtLeast(viewportSize.width)
    }
    val canvasOffsetXPx = with(density) { mapCanvasLayout.offsetX.roundToPx() }

    MapViewLifecycleEffect(
        mapView = mapView,
        onClear = { onMapUpdate(null) },
    )

    googleMap?.let {
        GoogleMapEffect(
            googleMap = it,
            isDarkMode = isDarkMode,
            onPointOfInterestClicked = onPointOfInterestClicked,
            onMapLongClicked = onMapLongClicked,
        )

        LaunchedEffect(it) {
            cameraState.attachMap(it)
        }
    }

    LaunchedEffect(
        viewportSize,
        canvasWidthPx,
    ) {
        if (viewportSize.hasNoArea()) return@LaunchedEffect

        cameraState.updateViewportSize(
            widthPx = canvasWidthPx,
            heightPx = viewportSize.height,
        )
    }

    Box(
        modifier = modifier,
    ) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { size ->
                    viewportSize = size
                },
            factory = {
                it.createMapContainer(
                    mapView = mapView,
                    onMapUpdate = onMapUpdate,
                )
            },
            update = { container ->
                container.updateMapViewLayout(
                    mapView = mapView,
                    canvasWidthPx = canvasWidthPx,
                    canvasHeightPx = viewportSize.height,
                    canvasOffsetXPx = canvasOffsetXPx,
                )
                container.mapTouchExclusion = mapTouchExclusion
            },
        )
    }
}

private fun Context.createMapContainer(
    mapView: MapView,
    onMapUpdate: (GoogleMap?) -> Unit,
): MapTouchGateFrameLayout {
    return MapTouchGateFrameLayout(this).apply {
        clipChildren = false
        clipToPadding = false
        addView(mapView)
        mapView.getMapAsync { map ->
            onMapUpdate(map)
        }
    }
}

private fun MapTouchGateFrameLayout.updateMapViewLayout(
    mapView: MapView,
    canvasWidthPx: Int,
    canvasHeightPx: Int,
    canvasOffsetXPx: Int,
) {
    if (canvasWidthPx <= 0 || canvasHeightPx <= 0) return

    if (mapView.parent != this) {
        (mapView.parent as? ViewGroup)?.removeView(mapView)
        addView(mapView)
    }

    val currentLayoutParams = mapView.layoutParams as? FrameLayout.LayoutParams
    val shouldUpdateLayoutParams = when {
        currentLayoutParams == null -> true
        currentLayoutParams.width != canvasWidthPx -> true
        currentLayoutParams.height != canvasHeightPx -> true
        else -> false
    }

    if (shouldUpdateLayoutParams) {
        mapView.layoutParams = FrameLayout.LayoutParams(
            canvasWidthPx,
            canvasHeightPx,
        )
    }

    mapView.translationX = canvasOffsetXPx.toFloat()
}

/** 地図上に重なる Compose UI の領域では MapView への touch dispatch を止める container。 */
private class MapTouchGateFrameLayout(
    context: Context,
) : FrameLayout(context) {

    var mapTouchExclusion: MapTouchExclusion? = null
    private var isCurrentGestureExcluded = false

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        updateCurrentGestureExclusion(event = event)

        if (isCurrentGestureExcluded) {
            return false
        }

        return super.dispatchTouchEvent(event)
    }

    private fun updateCurrentGestureExclusion(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val currentMapTouchExclusion = mapTouchExclusion
                isCurrentGestureExcluded = currentMapTouchExclusion?.contains(
                    pointX = event.x,
                    pointY = event.y,
                    viewportWidth = width,
                    viewportHeight = height,
                ) == true
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL,
            -> {
                isCurrentGestureExcluded = false
            }
        }
    }
}

private fun IntSize.hasNoArea(): Boolean {
    return width <= 0 || height <= 0
}

@Composable
private fun rememberMapViewWithLifecycle(isDarkMode: Boolean): MapView {
    val context = LocalContext.current
    val mapColorScheme = isDarkMode.toMapColorScheme()

    return remember(context) {
        val mapOptions = GoogleMapOptions()
            .mapType(GoogleMap.MAP_TYPE_NORMAL)
            .mapColorScheme(mapColorScheme)
            .camera(defaultCameraPosition())
            .liteMode(false)
            .tiltGesturesEnabled(true)
            .rotateGesturesEnabled(true)
            .zoomControlsEnabled(false)
            .compassEnabled(false)
            .mapToolbarEnabled(false)

        MapView(context, mapOptions).apply {
            onCreate(Bundle())
        }
    }
}

/**
 * MapView 生成時に GoogleMap へ渡す初期カメラ位置を作る。
 *
 * @return GoogleMap の初期 [CameraPosition]
 */
private fun defaultCameraPosition(): CameraPosition = CameraPosition.Builder()
    .target(
        LatLng(
            MapCameraDefaults.DEFAULT_LATITUDE,
            MapCameraDefaults.DEFAULT_LONGITUDE,
        ),
    )
    .zoom(MapCameraDefaults.DEFAULT_ZOOM)
    .build()

@SuppressLint("MissingPermission")
@Composable
private fun GoogleMapEffect(
    googleMap: GoogleMap,
    isDarkMode: Boolean,
    onPointOfInterestClicked: (PointOfInterest) -> Unit,
    onMapLongClicked: (LatLng) -> Unit,
) {
    val currentOnPointOfInterestClicked by rememberUpdatedState(onPointOfInterestClicked)
    val currentOnMapLongClicked by rememberUpdatedState(onMapLongClicked)

    LaunchedEffect(googleMap) {
        googleMap.mapType = GoogleMap.MAP_TYPE_NORMAL
        googleMap.isBuildingsEnabled = true
        googleMap.isTrafficEnabled = true
        googleMap.isMyLocationEnabled = false

        googleMap.uiSettings.apply {
            isCompassEnabled = false
            isMapToolbarEnabled = false
            isMyLocationButtonEnabled = false
            isZoomControlsEnabled = false
        }
    }

    LaunchedEffect(
        key1 = googleMap,
        key2 = isDarkMode,
    ) {
        googleMap.setMapColorScheme(isDarkMode.toMapColorScheme())
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
}

private fun Boolean.toMapColorScheme(): Int {
    return if (this) MapColorScheme.DARK else MapColorScheme.LIGHT
}

@Composable
private fun MapViewLifecycleEffect(
    mapView: MapView,
    onClear: () -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnClear by rememberUpdatedState(onClear)

    DisposableEffect(lifecycleOwner, mapView) {
        val lifecycle = lifecycleOwner.lifecycle
        var isStarted = false
        var isResumed = false
        var isDestroyed = false

        fun startMapView() {
            if (!isStarted && !isDestroyed) {
                mapView.onStart()
                isStarted = true
            }
        }

        fun resumeMapView() {
            startMapView()
            if (!isResumed && !isDestroyed) {
                mapView.onResume()
                isResumed = true
            }
        }

        fun pauseMapView() {
            if (isResumed && !isDestroyed) {
                mapView.onPause()
                isResumed = false
            }
        }

        fun stopMapView() {
            pauseMapView()
            if (isStarted && !isDestroyed) {
                mapView.onStop()
                isStarted = false
            }
        }

        fun destroyMapView() {
            if (!isDestroyed) {
                stopMapView()
                mapView.onDestroy()
                isDestroyed = true
            }
        }

        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            startMapView()
        }
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            resumeMapView()
        }

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> startMapView()
                Lifecycle.Event.ON_RESUME -> resumeMapView()
                Lifecycle.Event.ON_PAUSE -> pauseMapView()
                Lifecycle.Event.ON_STOP -> stopMapView()
                Lifecycle.Event.ON_DESTROY -> destroyMapView()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            destroyMapView()
            currentOnClear.invoke()
        }
    }
}
