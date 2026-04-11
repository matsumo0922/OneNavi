package me.matsumo.onenavi.feature.home.map

import android.app.Activity
import android.view.WindowManager
import androidx.compose.material3.BottomSheetScaffoldState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import com.mapbox.geojson.Point.fromLngLat
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.EdgeInsets
import com.mapbox.maps.MapView
import com.mapbox.maps.extension.compose.animation.viewport.MapViewportState
import com.mapbox.maps.extension.compose.style.standard.LightPresetValue
import com.mapbox.maps.extension.compose.style.standard.StandardStyleState
import com.mapbox.maps.plugin.animation.MapAnimationOptions
import com.mapbox.maps.plugin.locationcomponent.location
import com.mapbox.maps.plugin.viewport.ViewportStatus
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import me.matsumo.onenavi.core.model.SearchResultItem
import me.matsumo.onenavi.core.navigation.CameraManager
import me.matsumo.onenavi.core.navigation.RouteManager
import me.matsumo.onenavi.feature.home.map.components.LocationTrackingMode
import me.matsumo.onenavi.feature.home.map.state.HomeMapEffect
import me.matsumo.onenavi.feature.home.map.state.HomeMapScreenState

private const val FOLLOW_PUCK_ZOOM = 16.0

private const val CAMERA_ANIMATION_DURATION = 1500L

/**
 * BottomSheet の表示/非表示を screenState から導出して制御する。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeMapScreenSheetEffect(
    shouldShowSheet: Boolean,
    scaffoldState: BottomSheetScaffoldState,
    onSheetShowing: () -> Unit,
    onAllowSheetHide: (Boolean) -> Unit,
) {
    LaunchedEffect(shouldShowSheet) {
        if (shouldShowSheet) {
            onSheetShowing()
            scaffoldState.bottomSheetState.partialExpand()
        } else {
            onAllowSheetHide(true)
            scaffoldState.bottomSheetState.hide()
            onAllowSheetHide(false)
        }
    }
}

/**
 * Viewport が Idle になったらトラッキングモードを解除する。
 */
@Composable
internal fun HomeMapScreenViewportTrackingEffect(
    viewportState: MapViewportState,
    onTrackingModeCleared: () -> Unit,
) {
    LaunchedEffect(viewportState) {
        snapshotFlow { viewportState.mapViewportStatus }
            .distinctUntilChanged()
            .collect { status ->
                if (status is ViewportStatus.Idle) {
                    onTrackingModeCleared()
                }
            }
    }
}

/**
 * ダークテーマ切り替え時に地図スタイルを更新する。
 */
@Composable
internal fun HomeMapScreenThemeEffect(
    isDarkTheme: Boolean,
    standardStyleState: StandardStyleState,
) {
    LaunchedEffect(isDarkTheme) {
        standardStyleState.configurationsState.lightPreset =
            if (isDarkTheme) LightPresetValue.NIGHT else LightPresetValue.DAY
    }
}

/**
 * カメラ制御: Activity 再生成時の初期復元 + Effect Stream の collect。
 */
@Composable
internal fun HomeMapScreenCameraEffect(
    screenStateProvider: () -> HomeMapScreenState,
    effects: Flow<HomeMapEffect>,
    routeManager: RouteManager,
    cameraManager: CameraManager,
    routeResultsProvider: () -> ImmutableList<RouteResult>,
    mapView: MapView?,
    viewportState: MapViewportState,
    sheetPeekHeightPx: Double,
    topOverlayBottomPx: Float,
    activity: Activity?,
    onTrackingModeChanged: (LocationTrackingMode?) -> Unit,
) {
    // LaunchedEffect のクロージャは初回コンポジション時にキャプチャされるため、
    // 後から変わりうる値は rememberUpdatedState で最新値を参照できるようにする
    val currentActivity = rememberUpdatedState(activity)
    val currentSheetPeekHeightPx = rememberUpdatedState(sheetPeekHeightPx)
    val currentTopOverlayBottomPx = rememberUpdatedState(topOverlayBottomPx)

    LaunchedEffect(mapView) {
        val currentMapView = mapView ?: return@LaunchedEffect

        restoreCamera(
            screenState = screenStateProvider(),
            cameraManager = cameraManager,
            mapView = currentMapView,
            viewportState = viewportState,
            sheetPeekHeightPx = currentSheetPeekHeightPx.value,
            topOverlayBottomPx = currentTopOverlayBottomPx.value,
        )

        effects.collect { effect ->
            handleEffect(
                effect = effect,
                routeManager = routeManager,
                cameraManager = cameraManager,
                routeResults = routeResultsProvider(),
                mapView = currentMapView,
                viewportState = viewportState,
                sheetPeekHeightPx = currentSheetPeekHeightPx.value,
                topOverlayBottomPx = currentTopOverlayBottomPx.value,
                activity = currentActivity.value,
                onTrackingModeChanged = onTrackingModeChanged,
            )
        }
    }
}

/**
 * Activity 再生成後、現在の screenState に応じてカメラ位置を復元する。
 */
private fun restoreCamera(
    screenState: HomeMapScreenState,
    cameraManager: CameraManager,
    mapView: MapView?,
    viewportState: MapViewportState,
    sheetPeekHeightPx: Double,
    topOverlayBottomPx: Float,
) {
    val overlayPadding = buildOverlayPadding(
        topOverlayBottomPx = topOverlayBottomPx,
        bottomSheetPeekHeightPx = sheetPeekHeightPx,
    )

    when (val state = screenState) {
        is HomeMapScreenState.SearchResultsList -> {
            val currentMapView = mapView ?: return
            val points = state.results.map { fromLngLat(it.longitude, it.latitude) }

            @Suppress("DEPRECATION")
            val cameraOptions = currentMapView.mapboxMap.cameraForCoordinates(points, overlayPadding, 0.0, 0.0)
            viewportState.flyTo(cameraOptions)
        }
        is HomeMapScreenState.PlaceDetails -> {
            viewportState.easeTo(
                cameraOptions = buildPlaceCameraOptions(
                    place = state.place,
                    padding = overlayPadding,
                ),
            )
        }
        is HomeMapScreenState.RoutePreview -> {
            cameraManager.applyNavigationPadding(
                followingPadding = EdgeInsets(0.0, 0.0, 0.0, 0.0),
                overviewPadding = overlayPadding,
            )
            cameraManager.requestCameraOverview()
        }
        is HomeMapScreenState.Navigating -> {
            cameraManager.requestCameraFollowing(pitch3D = true)
        }
        else -> { /* Browsing / Arrived: デフォルト */ }
    }
}

/**
 * Effect Stream から受信した one-shot 副作用を処理する。
 */
private suspend fun handleEffect(
    effect: HomeMapEffect,
    routeManager: RouteManager,
    cameraManager: CameraManager,
    routeResults: ImmutableList<RouteResult>,
    mapView: MapView?,
    viewportState: MapViewportState,
    sheetPeekHeightPx: Double,
    topOverlayBottomPx: Float,
    activity: Activity?,
    onTrackingModeChanged: (LocationTrackingMode?) -> Unit,
) {
    when (effect) {
        is HomeMapEffect.MoveCameraToSearchResults -> {
            onTrackingModeChanged(null)
            val currentMapView = mapView ?: return
            val points = effect.results.map { fromLngLat(it.longitude, it.latitude) }
            val padding = buildOverlayPadding(topOverlayBottomPx, sheetPeekHeightPx)

            @Suppress("DEPRECATION")
            val cameraOptions = currentMapView.mapboxMap.cameraForCoordinates(points, padding, 0.0, 0.0)
            val animationOptions = MapAnimationOptions.Builder()
                .duration(CAMERA_ANIMATION_DURATION)
                .build()

            viewportState.flyTo(
                cameraOptions = cameraOptions,
                animationOptions = animationOptions,
            )
        }
        is HomeMapEffect.MoveCameraToPlace -> {
            onTrackingModeChanged(null)
            val padding = buildOverlayPadding(topOverlayBottomPx, sheetPeekHeightPx)
            val cameraOptions = buildPlaceCameraOptions(effect.place, padding)
            val animationOptions = MapAnimationOptions.Builder()
                .duration(CAMERA_ANIMATION_DURATION)
                .build()

            viewportState.easeTo(
                cameraOptions = cameraOptions,
                animationOptions = animationOptions,
            )
        }
        is HomeMapEffect.MoveCameraToRouteOverview -> {
            onTrackingModeChanged(null)
            val currentMapView = mapView ?: return
            val padding = buildOverlayPadding(topOverlayBottomPx, sheetPeekHeightPx)

            routeManager.routes.first { it.isNotEmpty() }
            cameraManager.requestCameraIdle()

            val overviewPoints = routeResults.flatMap { result ->
                result.item.geometry.map { point ->
                    fromLngLat(point.longitude, point.latitude)
                }
            }

            if (overviewPoints.isNotEmpty()) {
                @Suppress("DEPRECATION")
                val fittedCamera = currentMapView.mapboxMap.cameraForCoordinates(
                    coordinates = overviewPoints,
                    coordinatesPadding = EdgeInsets(0.0, 0.0, 0.0, 0.0),
                    bearing = 0.0,
                    pitch = 0.0,
                )
                val cameraOptions = CameraOptions.Builder()
                    .center(fittedCamera.center)
                    .zoom(fittedCamera.zoom)
                    .bearing(fittedCamera.bearing)
                    .pitch(fittedCamera.pitch)
                    .padding(padding)
                    .build()
                val animationOptions = MapAnimationOptions.Builder()
                    .duration(CAMERA_ANIMATION_DURATION)
                    .build()
                viewportState.flyTo(
                    cameraOptions = cameraOptions,
                    animationOptions = animationOptions,
                )
            } else {
                cameraManager.applyNavigationPadding(
                    followingPadding = EdgeInsets(0.0, 0.0, 0.0, 0.0),
                    overviewPadding = padding,
                )
                cameraManager.requestCameraOverview()
            }
        }
        is HomeMapEffect.EnterGuidanceFollowing -> {
            cameraManager.requestCameraFollowing(pitch3D = true)
        }
        is HomeMapEffect.RestoreTracking -> {
            onTrackingModeChanged(LocationTrackingMode.TiltedHeading)
        }
        is HomeMapEffect.SetKeepScreenOn -> {
            if (effect.enabled) {
                activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        is HomeMapEffect.UseNavigationLocationProvider -> {
            if (effect.enabled) {
                mapView?.location?.setLocationProvider(cameraManager.navigationLocationProvider)
            } else {
                mapView?.location?.enabled = true
            }
        }
    }
}

private fun buildOverlayPadding(
    topOverlayBottomPx: Float,
    bottomSheetPeekHeightPx: Double,
): EdgeInsets {
    return EdgeInsets(topOverlayBottomPx.toDouble(), 0.0, bottomSheetPeekHeightPx, 0.0)
}

private fun buildPlaceCameraOptions(
    place: SearchResultItem,
    padding: EdgeInsets,
): CameraOptions {
    return CameraOptions.Builder()
        .center(fromLngLat(place.longitude, place.latitude))
        .zoom(FOLLOW_PUCK_ZOOM)
        .pitch(0.0)
        .bearing(0.0)
        .padding(padding)
        .build()
}
