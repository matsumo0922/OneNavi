package me.matsumo.onenavi.feature.map

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Rect
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.Display
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
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
import androidx.compose.ui.unit.Density
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
import io.github.aakira.napier.Napier
import kotlinx.coroutines.delay
import me.matsumo.onenavi.feature.map.state.MapCameraDefaults
import me.matsumo.onenavi.feature.map.state.MapCameraState
import me.matsumo.onenavi.feature.map.state.MapCanvasLayout
import kotlin.math.roundToInt

@Composable
internal fun MapItem(
    googleMap: GoogleMap?,
    cameraState: MapCameraState,
    isDarkMode: Boolean,
    mapCanvasLayout: MapCanvasLayout,
    shouldLogDiagnostics: Boolean,
    onMapUpdate: (GoogleMap?) -> Unit,
    onPointOfInterestClicked: (PointOfInterest) -> Unit,
    onMapLongClicked: (LatLng) -> Unit,
    modifier: Modifier = Modifier,
) {
    val displayDensity = LocalDensity.current
    val mapRenderScale = LocalMapRenderScale.current
    val mapSpaceDensity = remember(displayDensity, mapRenderScale) {
        Density(
            density = displayDensity.density * mapRenderScale,
            fontScale = displayDensity.fontScale,
        )
    }
    val mapView = rememberMapViewWithLifecycle(
        isDarkMode = isDarkMode,
        mapRenderScale = mapRenderScale,
    )
    val mapViewDiagnosticState = remember { MapViewDiagnosticState() }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    val viewportWidthMapPx = (viewportSize.width * mapRenderScale).roundToInt()
    val viewportHeightMapPx = (viewportSize.height * mapRenderScale).roundToInt()
    val canvasWidthPx = with(mapSpaceDensity) {
        mapCanvasLayout.width.roundToPx().coerceAtLeast(viewportWidthMapPx)
    }
    val canvasOffsetXPx = with(displayDensity) { mapCanvasLayout.offsetX.roundToPx() }

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

        if (shouldLogDiagnostics) {
            MapRenderDensityDiagnosticsEffect(
                googleMap = it,
                composeDensity = displayDensity.density,
                mapView = mapView,
            )
        }
    }

    LaunchedEffect(
        viewportSize,
        canvasWidthPx,
        canvasOffsetXPx,
    ) {
        if (viewportSize.hasNoArea()) return@LaunchedEffect

        cameraState.updateViewportSize(
            widthPx = canvasWidthPx,
            heightPx = viewportHeightMapPx,
        )
        if (shouldLogDiagnostics) {
            Napier.i(tag = MAP_CAMERA_LOG_TAG) {
                "MapView layout updated. viewport=${viewportSize.width}x${viewportSize.height} " +
                    "canvas=${canvasWidthPx}x$viewportHeightMapPx offsetX=$canvasOffsetXPx"
            }
        }
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
                    shouldLogDiagnostics = shouldLogDiagnostics,
                    diagnosticState = mapViewDiagnosticState,
                    onMapUpdate = onMapUpdate,
                )
            },
            update = { container ->
                container.updateMapViewLayout(
                    mapView = mapView,
                    canvasWidthPx = canvasWidthPx,
                    canvasHeightPx = viewportHeightMapPx,
                    canvasOffsetXPx = canvasOffsetXPx,
                    renderScale = mapRenderScale,
                    shouldLogDiagnostics = shouldLogDiagnostics,
                    diagnosticState = mapViewDiagnosticState,
                )
            },
        )
    }
}

private fun Context.createMapContainer(
    mapView: MapView,
    shouldLogDiagnostics: Boolean,
    diagnosticState: MapViewDiagnosticState,
    onMapUpdate: (GoogleMap?) -> Unit,
): FrameLayout {
    return FrameLayout(this).apply {
        clipChildren = false
        clipToPadding = false
        addView(mapView)
        registerMapUpdateCallback(
            mapView = mapView,
            shouldLogDiagnostics = shouldLogDiagnostics,
            diagnosticState = diagnosticState,
            onMapUpdate = onMapUpdate,
        )
    }
}

private fun FrameLayout.registerMapUpdateCallback(
    mapView: MapView,
    shouldLogDiagnostics: Boolean,
    diagnosticState: MapViewDiagnosticState,
    onMapUpdate: (GoogleMap?) -> Unit,
) {
    mapView.getMapAsync { googleMap ->
        onMapReady(
            mapView = mapView,
            googleMap = googleMap,
            shouldLogDiagnostics = shouldLogDiagnostics,
            diagnosticState = diagnosticState,
            onMapUpdate = onMapUpdate,
        )
    }
}

private fun FrameLayout.onMapReady(
    mapView: MapView,
    googleMap: GoogleMap,
    shouldLogDiagnostics: Boolean,
    diagnosticState: MapViewDiagnosticState,
    onMapUpdate: (GoogleMap?) -> Unit,
) {
    onMapUpdate(googleMap)
    if (!shouldLogDiagnostics) return

    post {
        logMapViewDiagnostics(
            mapView = mapView,
            reason = "map-ready",
            diagnosticState = diagnosticState,
        )
    }
}

private fun FrameLayout.updateMapViewLayout(
    mapView: MapView,
    canvasWidthPx: Int,
    canvasHeightPx: Int,
    canvasOffsetXPx: Int,
    renderScale: Float,
    shouldLogDiagnostics: Boolean,
    diagnosticState: MapViewDiagnosticState,
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

    val inverseRenderScale = 1f / renderScale
    mapView.pivotX = 0f
    mapView.pivotY = 0f
    mapView.scaleX = inverseRenderScale
    mapView.scaleY = inverseRenderScale
    mapView.translationX = canvasOffsetXPx.toFloat()
    if (!shouldLogDiagnostics) return

    post {
        logMapViewDiagnostics(
            mapView = mapView,
            reason = "layout-updated",
            diagnosticState = diagnosticState,
        )
    }
}

private fun IntSize.hasNoArea(): Boolean {
    return width <= 0 || height <= 0
}

private fun FrameLayout.logMapViewDiagnostics(
    mapView: MapView,
    reason: String,
    diagnosticState: MapViewDiagnosticState,
) {
    val contextMetricsLabel = mapView.context.resources.displayMetrics.toDiagnosticLabel()
    val displayMetricsLabel = mapView.display.toDiagnosticLabel()
    val containerLabel = viewDiagnosticLabel()
    val mapViewLabel = mapView.viewDiagnosticLabel()
    val childLabel = directChildrenDiagnosticLabel()
    val rendererLabel = mapView.rendererViewDiagnosticLabels().joinToString(separator = " | ")
    val diagnosticSignature = "$contextMetricsLabel/$displayMetricsLabel/" +
        "$containerLabel/$mapViewLabel/$childLabel/$rendererLabel"

    if (diagnosticSignature == diagnosticState.lastSignature) return

    diagnosticState.lastSignature = diagnosticSignature
    Napier.i(tag = MAP_CAMERA_LOG_TAG) {
        "MapView diagnostics. reason=$reason display=${mapView.display?.displayId} " +
            "contextMetrics={$contextMetricsLabel} displayMetrics={$displayMetricsLabel} " +
            "container={$containerLabel} mapView={$mapViewLabel} " +
            "children=[$childLabel] renderers=[$rendererLabel]"
    }
}

private fun ViewGroup.directChildrenDiagnosticLabel(): String {
    if (childCount == 0) return "none"

    return (0 until childCount).joinToString(separator = " | ") { childIndex ->
        val child = getChildAt(childIndex)
        "#$childIndex ${child.viewDiagnosticLabel()}"
    }
}

private fun View.rendererViewDiagnosticLabels(
    depth: Int = 0,
): List<String> {
    if (depth > MAP_VIEW_DIAGNOSTIC_MAX_DEPTH) return emptyList()

    val selfLabel = if (isMapRendererView()) {
        listOf("depth=$depth ${viewDiagnosticLabel()}")
    } else {
        emptyList()
    }

    if (this !is ViewGroup) return selfLabel

    return buildList {
        addAll(selfLabel)
        for (childIndex in 0 until childCount) {
            addAll(getChildAt(childIndex).rendererViewDiagnosticLabels(depth = depth + 1))
        }
    }
}

private fun View.isMapRendererView(): Boolean {
    val className = javaClass.name
    val isSurfaceView = this is SurfaceView
    val isTextureView = this is TextureView
    val isGlView = className.contains("GL", ignoreCase = true)
    val isRendererView = className.contains("Renderer", ignoreCase = true)

    return isSurfaceView || isTextureView || isGlView || isRendererView
}

private fun View.viewDiagnosticLabel(): String {
    val screenLocation = IntArray(2)
    val windowLocation = IntArray(2)
    val globalVisibleRect = Rect()
    getLocationOnScreen(screenLocation)
    getLocationInWindow(windowLocation)
    getGlobalVisibleRect(globalVisibleRect)

    return "${javaClass.simpleName} " +
        "size=${width}x$height measured=${measuredWidth}x$measuredHeight " +
        "pos=$left,$top,$right,$bottom translation=$translationX,$translationY " +
        "screen=${screenLocation[0]},${screenLocation[1]} " +
        "window=${windowLocation[0]},${windowLocation[1]} visible=$globalVisibleRect"
}

@Suppress("DEPRECATION")
private fun Display?.toDiagnosticLabel(): String {
    val display = this ?: return "n/a"
    val appMetrics = DisplayMetrics()
    val realMetrics = DisplayMetrics()
    display.getMetrics(appMetrics)
    display.getRealMetrics(realMetrics)

    return "id=${display.displayId} name=${display.name} " +
        "app=${appMetrics.toDiagnosticLabel()} real=${realMetrics.toDiagnosticLabel()}"
}

private fun DisplayMetrics.toDiagnosticLabel(): String {
    return "${widthPixels}x$heightPixels density=$density dpi=$densityDpi xdpi=$xdpi ydpi=$ydpi"
}

/** MapView 診断ログの重複出力を MapView 単位で抑える状態。 */
private class MapViewDiagnosticState {
    var lastSignature: String? = null
}

/** Map camera 周辺の検証ログ用タグ。 */
private const val MAP_CAMERA_LOG_TAG = "OneNaviMapCamera"

/** MapView 内部 View の探索深さ上限。 */
private const val MAP_VIEW_DIAGNOSTIC_MAX_DEPTH = 6

/** 描画 density 計測のポーリング間隔 (ms)。 */
private const val MAP_RENDER_DENSITY_SAMPLE_INTERVAL_MS = 1000L

@Composable
private fun rememberMapViewWithLifecycle(
    isDarkMode: Boolean,
    mapRenderScale: Float,
): MapView {
    val context = LocalContext.current
    val mapColorScheme = isDarkMode.toMapColorScheme()

    return remember(context, mapRenderScale) {
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

        MapView(context.withMapRenderDensityContext(mapRenderScale), mapOptions).apply {
            onCreate(Bundle())
        }
    }
}

/**
 * MapView へ渡す context の density を、地図の描画 density（焼付 density）へ揃える。
 *
 * GoogleMap SDK は bounds フィットや zoom 計算の px↔dp 変換に MapView の context density を使う一方、
 * 地図描画自体はプロセス共通の焼付 density で行う。VirtualDisplay 上では context density が表示先
 * （低 density）のままになり、両者の factor が食い違って bounds フィットや追従 zoom が寄りすぎる。
 * context density を焼付 density（表示先 density × [renderScale]）へ揃えることで、描画と zoom 計算の
 * factor を一致させる。
 *
 * 通常端末では [renderScale] が [DEFAULT_MAP_RENDER_SCALE] のため [this] をそのまま返す（no-op）。
 */
private fun Context.withMapRenderDensityContext(renderScale: Float): Context {
    if (renderScale == DEFAULT_MAP_RENDER_SCALE) {
        return this
    }

    val bakedDensityDpi = (resources.configuration.densityDpi * renderScale).roundToInt()
    return createDensityConfigurationContext(bakedDensityDpi)
}

private fun Context.createDensityConfigurationContext(targetDensityDpi: Int): Context {
    val densityConfiguration = Configuration(resources.configuration).apply {
        densityDpi = targetDensityDpi
    }

    return createConfigurationContext(densityConfiguration)
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

/**
 * GoogleMap の描画実効 density をカメラ静止ごとに計測し、logcat と overlay へ反映する診断 Effect。
 *
 * VirtualDisplay 上で地図が拡大表示される問題の原因切り分け用。debug 診断時のみ呼ぶ。
 */
@Composable
private fun MapRenderDensityDiagnosticsEffect(googleMap: GoogleMap, composeDensity: Float, mapView: MapView) {
    DisposableEffect(googleMap, composeDensity) {
        onDispose {
            MapRenderDensityDiagnostics.clear()
        }
    }

    LaunchedEffect(googleMap, composeDensity) {
        var lastLabel: String? = null

        while (true) {
            val measurement = googleMap.measureRenderDensity()
            val label = measurement?.toDiagnosticLabel(composeDensity)

            if (label != null && label != lastLabel) {
                lastLabel = label
                MapRenderDensityDiagnostics.update(label)
                Napier.i(tag = MAP_CAMERA_LOG_TAG) {
                    "MapView render density. displayId=${mapView.display?.displayId} " +
                        "$label ${mapView.densitySourceLabel()}"
                }
            }

            delay(MAP_RENDER_DENSITY_SAMPLE_INTERVAL_MS)
        }
    }
}

/** GoogleMap が描画 density を引きうる候補を横並びでログ化し、eff と一致する源を切り分ける。 */
@Suppress("DEPRECATION")
private fun MapView.densitySourceLabel(): String {
    val viewContextDpi = context.resources.displayMetrics.densityDpi
    val appContextDpi = context.applicationContext.resources.displayMetrics.densityDpi
    val systemDpi = Resources.getSystem().displayMetrics.densityDpi
    val displayDpi = display?.let { mapDisplay ->
        DisplayMetrics().also { metrics -> mapDisplay.getRealMetrics(metrics) }.densityDpi
    } ?: -1

    return "densitySrc[viewCtx=$viewContextDpi appCtx=$appContextDpi system=$systemDpi display=$displayDpi]"
}

@Composable
private fun MapViewLifecycleEffect(mapView: MapView, onClear: () -> Unit) {
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
