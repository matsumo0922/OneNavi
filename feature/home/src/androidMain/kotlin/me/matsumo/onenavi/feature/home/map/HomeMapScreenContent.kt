package me.matsumo.onenavi.feature.home.map

import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mapbox.geojson.Point.fromLngLat
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.EdgeInsets
import com.mapbox.maps.MapView
import com.mapbox.maps.MapboxExperimental
import com.mapbox.maps.extension.compose.animation.viewport.rememberMapViewportState
import com.mapbox.maps.extension.compose.style.standard.LightPresetValue
import com.mapbox.maps.extension.compose.style.standard.rememberStandardStyleState
import com.mapbox.maps.plugin.animation.MapAnimationOptions
import com.mapbox.maps.plugin.locationcomponent.location
import com.mapbox.maps.plugin.viewport.ViewportStatus
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import me.matsumo.onenavi.feature.home.map.components.HomeMapControls
import me.matsumo.onenavi.feature.home.map.components.LocationTrackingMode
import me.matsumo.onenavi.feature.home.map.components.navi.HomeMapNaviContent
import me.matsumo.onenavi.feature.home.map.components.topappbar.HomeMapRouteTopAppBar
import me.matsumo.onenavi.feature.home.map.components.topappbar.HomeMapTopAppBar
import me.matsumo.onenavi.feature.home.map.components.topappbar.HomeMapWaypointSearchScreen
import me.matsumo.onenavi.feature.home.map.state.HomeMapEffect
import me.matsumo.onenavi.feature.home.map.state.HomeMapOverlayState
import me.matsumo.onenavi.feature.home.map.state.HomeMapScreenState

private const val FOLLOW_PUCK_ZOOM = 16.0
private const val CAMERA_PADDING = 100.0
private const val CAMERA_PADDING_TOP = 200.0
private const val CAMERA_PADDING_BOTTOM = 400.0
private const val ROUTE_CAMERA_MARGIN_VERTICAL = 150.0
private const val ROUTE_CAMERA_MARGIN_HORIZONTAL = 100.0
private const val ROUTE_CAMERA_MARGIN_TOP = 300.0
private const val ROUTE_CAMERA_MARGIN_END = 250.0

private val SHEET_PEEK_HEIGHT_DEFAULT = 200.dp

@Suppress("ParamsComparedByRef")
@OptIn(ExperimentalMaterial3Api::class, MapboxExperimental::class)
@Composable
internal fun HomeMapScreenContent(
    viewModel: HomeMapViewModel,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val activity = LocalActivity.current

    val screenState by viewModel.screenState.collectAsStateWithLifecycle()
    val overlayState by viewModel.overlayState.collectAsStateWithLifecycle()
    val suggestions by viewModel.suggestions.collectAsStateWithLifecycle()
    val histories by viewModel.histories.collectAsStateWithLifecycle()

    // 旧互換用（Phase 3 のマーカー整理で削除予定）
    val searchResults by viewModel.searchResults.collectAsStateWithLifecycle()
    val selectedResult by viewModel.selectedResult.collectAsStateWithLifecycle()
    val routeResults by viewModel.routeResults.collectAsStateWithLifecycle()
    val selectedRouteIndex by viewModel.selectedRouteIndex.collectAsStateWithLifecycle()
    val waypoints by viewModel.waypoints.collectAsStateWithLifecycle()
    val waypointEditResult by viewModel.waypointEditResult.collectAsStateWithLifecycle()

    var mapView by remember { mutableStateOf<MapView?>(null) }
    var trackingMode by remember { mutableStateOf<LocationTrackingMode?>(LocationTrackingMode.TiltedHeading) }
    var deviceBearing by remember { mutableStateOf(0.0) }

    val isDarkTheme = isSystemInDarkTheme()
    val viewportState = rememberMapViewportState()
    val standardStyleState = rememberStandardStyleState {
        configurationsState.lightPreset = if (isDarkTheme) LightPresetValue.NIGHT else LightPresetValue.DAY
        interactionsState.onPoiClicked { poiFeature, context ->
            val name = runCatching { poiFeature.name }.getOrNull()
            val point = context.coordinateInfo.coordinate

            viewModel.onViewEvent(
                HomeMapViewEvent.OnMapLandmarkSelected(
                    name = name,
                    latitude = point.latitude(),
                    longitude = point.longitude(),
                ),
            )
            true
        }
        interactionsState.onMapLongClicked { context ->
            val point = context.coordinateInfo.coordinate

            viewModel.onViewEvent(
                HomeMapViewEvent.OnMapLandmarkSelected(
                    name = null,
                    latitude = point.latitude(),
                    longitude = point.longitude(),
                ),
            )
            true
        }
    }

    val scaffoldState = rememberBottomSheetScaffoldState(
        bottomSheetState = rememberStandardBottomSheetState(
            initialValue = SheetValue.Hidden,
            skipHiddenState = false,
            confirmValueChange = { newValue ->
                // ユーザーは swipe で Sheet を閉じられない
                newValue != SheetValue.Hidden
            },
        ),
    )

    var contentHeight by remember { mutableFloatStateOf(0f) }
    var topAppBarHeightPx by remember { mutableFloatStateOf(0f) }
    var sheetPeekHeight by remember { mutableStateOf(SHEET_PEEK_HEIGHT_DEFAULT) }

    val sheetVisibleHeight by remember {
        derivedStateOf {
            val offset = runCatching { scaffoldState.bottomSheetState.requireOffset() }.getOrDefault(contentHeight)
            with(density) { (contentHeight - offset).coerceAtLeast(0f).toDp() }
        }
    }

    // Sheet 制御: screenState から導出
    val shouldShowSheet = screenState is HomeMapScreenState.SearchResultsList ||
        screenState is HomeMapScreenState.PlaceDetails ||
        screenState is HomeMapScreenState.RoutePreview

    LaunchedEffect(shouldShowSheet) {
        if (shouldShowSheet) {
            sheetPeekHeight = SHEET_PEEK_HEIGHT_DEFAULT
            scaffoldState.bottomSheetState.partialExpand()
        } else {
            scaffoldState.bottomSheetState.hide()
        }
    }

    LaunchedEffect(viewportState) {
        snapshotFlow { viewportState.mapViewportStatus }
            .distinctUntilChanged()
            .collect { status ->
                if (status is ViewportStatus.Idle) {
                    trackingMode = null
                }
            }
    }

    LaunchedEffect(isDarkTheme) {
        standardStyleState.configurationsState.lightPreset =
            if (isDarkTheme) LightPresetValue.NIGHT else LightPresetValue.DAY
    }

    // カメラ制御: 初期復元 + Effect collect
    LaunchedEffect(Unit) {
        // 初期復元: Activity 再生成後、現在の screenState に応じてカメラを合わせる
        when (val state = viewModel.screenState.value) {
            is HomeMapScreenState.SearchResultsList -> {
                val currentMapView = mapView
                if (currentMapView != null) {
                    val points = state.results.map { fromLngLat(it.longitude, it.latitude) }
                    val padding = EdgeInsets(CAMERA_PADDING_TOP, CAMERA_PADDING, CAMERA_PADDING_BOTTOM, CAMERA_PADDING)
                    @Suppress("DEPRECATION")
                    val cameraOptions = currentMapView.mapboxMap.cameraForCoordinates(points, padding, 0.0, 0.0)
                    viewportState.flyTo(cameraOptions)
                }
            }
            is HomeMapScreenState.PlaceDetails -> {
                viewportState.easeTo(
                    cameraOptions = CameraOptions.Builder()
                        .center(fromLngLat(state.place.longitude, state.place.latitude))
                        .zoom(FOLLOW_PUCK_ZOOM)
                        .pitch(0.0)
                        .bearing(0.0)
                        .build(),
                )
            }
            is HomeMapScreenState.RoutePreview -> {
                viewModel.cameraManager.requestCameraOverview()
            }
            is HomeMapScreenState.Navigating -> {
                viewModel.cameraManager.requestCameraFollowing(pitch3D = true)
            }
            else -> { /* Browsing / Arrived: デフォルト */ }
        }

        // 以降は Effect を collect して遷移時カメラ移動を処理
        viewModel.effects.collect { effect ->
            when (effect) {
                is HomeMapEffect.MoveCameraToSearchResults -> {
                    trackingMode = null
                    val currentMapView = mapView ?: return@collect
                    val points = effect.results.map { fromLngLat(it.longitude, it.latitude) }
                    val padding = EdgeInsets(CAMERA_PADDING_TOP, CAMERA_PADDING, CAMERA_PADDING_BOTTOM, CAMERA_PADDING)
                    @Suppress("DEPRECATION")
                    val opts = currentMapView.mapboxMap.cameraForCoordinates(points, padding, 0.0, 0.0)
                    viewportState.flyTo(
                        cameraOptions = opts,
                        animationOptions = MapAnimationOptions.Builder().duration(1500).build(),
                    )
                }
                is HomeMapEffect.MoveCameraToPlace -> {
                    trackingMode = null
                    viewportState.easeTo(
                        cameraOptions = CameraOptions.Builder()
                            .center(fromLngLat(effect.place.longitude, effect.place.latitude))
                            .zoom(FOLLOW_PUCK_ZOOM)
                            .pitch(0.0)
                            .bearing(0.0)
                            .build(),
                        animationOptions = MapAnimationOptions.Builder().duration(1500).build(),
                    )
                }
                is HomeMapEffect.MoveCameraToRouteOverview -> {
                    trackingMode = null

                    val sheetPeekPx = with(density) { sheetPeekHeight.toPx() }.toDouble()
                    val topPadding = topAppBarHeightPx.toDouble() + ROUTE_CAMERA_MARGIN_TOP
                    val bottomPadding = sheetPeekPx + ROUTE_CAMERA_MARGIN_VERTICAL
                    val padding = EdgeInsets(topPadding, ROUTE_CAMERA_MARGIN_HORIZONTAL, bottomPadding, ROUTE_CAMERA_MARGIN_END)

                    viewModel.routeManager.routes.first { it.isNotEmpty() }

                    viewModel.cameraManager.applyNavigationPadding(
                        followingPadding = EdgeInsets(0.0, 0.0, 0.0, 0.0),
                        overviewPadding = padding,
                    )
                    viewModel.cameraManager.requestCameraOverview()
                }
                is HomeMapEffect.EnterGuidanceFollowing -> {
                    viewModel.cameraManager.requestCameraFollowing(pitch3D = true)
                }
                is HomeMapEffect.RestoreTracking -> {
                    trackingMode = LocationTrackingMode.TiltedHeading
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
                        mapView?.location?.setLocationProvider(viewModel.cameraManager.navigationLocationProvider)
                    } else {
                        mapView?.location?.enabled = true
                    }
                }
            }
        }
    }

    BottomSheetScaffold(
        modifier = modifier,
        scaffoldState = scaffoldState,
        sheetPeekHeight = sheetPeekHeight,
        sheetContent = {
            HomeMapSheetContent(
                screenState = screenState,
                searchResults = searchResults,
                selectedResult = selectedResult,
                routeResults = routeResults,
                selectedRouteIndex = selectedRouteIndex,
                onViewEvent = viewModel::onViewEvent,
                onPeekHeightChanged = { sheetPeekHeight = it },
            )
        },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { contentHeight = it.size.height.toFloat() },
        ) {
            HomeMapsMapEffectContent(
                viewportState = viewportState,
                standardStyleState = standardStyleState,
                sheetVisibleHeight = sheetVisibleHeight,
                searchResults = searchResults,
                selectedResult = selectedResult,
                routeResults = routeResults,
                selectedRouteIndex = selectedRouteIndex,
                waypoints = waypoints,
                isNavigating = screenState is HomeMapScreenState.Navigating || screenState is HomeMapScreenState.Arrived,
                routeManager = viewModel.routeManager,
                cameraManager = viewModel.cameraManager,
                onMapViewChanged = { mapView = it },
                onUserLocationUpdated = viewModel::onUserLocationUpdated,
                onRouteSelected = { viewModel.onViewEvent(HomeMapViewEvent.OnRouteSelected(it)) },
                onBearingChanged = { deviceBearing = it },
            )

            // UI 分岐: screenState ベース
            when (screenState) {
                is HomeMapScreenState.Navigating -> {
                    HomeMapNaviContent(
                        modifier = Modifier.fillMaxSize(),
                        viewModel = viewModel,
                    )
                }
                else -> {
                    HomeMapControls(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(
                                bottom = 16.dp,
                                end = 16.dp,
                            )
                            .offset(y = -sheetVisibleHeight),
                        cameraBearing = viewportState.cameraState?.bearing ?: 0.0,
                        deviceBearing = deviceBearing,
                        trackingMode = trackingMode,
                        viewportState = viewportState,
                        onTrackingModeChanged = { trackingMode = it },
                    )
                }
            }

            // TopAppBar 分岐
            when (screenState) {
                is HomeMapScreenState.Browsing,
                is HomeMapScreenState.SearchResultsList,
                is HomeMapScreenState.PlaceDetails,
                -> {
                    HomeMapTopAppBar(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .statusBarsPadding()
                            .fillMaxWidth()
                            .onGloballyPositioned { topAppBarHeightPx = it.size.height.toFloat() },
                        suggestions = suggestions,
                        histories = histories,
                        selectedResult = selectedResult,
                        viewportState = viewportState,
                        onViewEvent = viewModel::onViewEvent,
                    )
                }
                is HomeMapScreenState.RoutePreview -> {
                    HomeMapRouteTopAppBar(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .statusBarsPadding()
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .onGloballyPositioned { topAppBarHeightPx = it.size.height.toFloat() },
                        waypoints = waypoints,
                        waypointEditResult = waypointEditResult,
                        onWaypointEditResultConsumed = viewModel::consumeWaypointEditResult,
                        onViewEvent = viewModel::onViewEvent,
                    )
                }
                is HomeMapScreenState.Navigating,
                is HomeMapScreenState.Arrived,
                -> { /* TopAppBar なし */ }
            }

            // Overlay
            val currentOverlay = overlayState
            if (currentOverlay is HomeMapOverlayState.WaypointSearch) {
                HomeMapWaypointSearchScreen(
                    modifier = Modifier.fillMaxSize(),
                    isVisible = true,
                    initialQuery = currentOverlay.initialQuery,
                    suggestions = suggestions,
                    histories = histories,
                    onSuggestionSelected = viewModel::onWaypointSuggestionSelected,
                    onHistorySelected = viewModel::onWaypointHistorySelected,
                    onRemoveHistory = { viewModel.onViewEvent(HomeMapViewEvent.OnRemoveHistory(it)) },
                    onQueryChanged = { viewModel.onViewEvent(HomeMapViewEvent.OnQueryChanged(it)) },
                    onDismiss = viewModel::onWaypointSearchDismissed,
                )
            }
        }
    }
}
