package me.matsumo.onenavi.feature.home.map

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
import com.mapbox.maps.plugin.viewport.ViewportStatus
import kotlinx.coroutines.flow.distinctUntilChanged
import me.matsumo.onenavi.core.model.RouteWaypoint
import me.matsumo.onenavi.feature.home.map.components.HomeMapControls
import me.matsumo.onenavi.feature.home.map.components.LocationTrackingMode
import me.matsumo.onenavi.feature.home.map.components.topappbar.HomeMapRouteTopAppBar
import me.matsumo.onenavi.feature.home.map.components.topappbar.HomeMapTopAppBar
import me.matsumo.onenavi.feature.home.map.components.topappbar.HomeMapWaypointSearchScreen

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
    modifier: Modifier,
) {
    val density = LocalDensity.current

    val suggestions by viewModel.suggestions.collectAsStateWithLifecycle()
    val histories by viewModel.histories.collectAsStateWithLifecycle()
    val searchResults by viewModel.searchResults.collectAsStateWithLifecycle()
    val selectedResult by viewModel.selectedResult.collectAsStateWithLifecycle()
    val routeResults by viewModel.routeResults.collectAsStateWithLifecycle()
    val selectedRouteIndex by viewModel.selectedRouteIndex.collectAsStateWithLifecycle()
    val waypoints by viewModel.waypoints.collectAsStateWithLifecycle()
    val editingWaypointIndex by viewModel.editingWaypointIndex.collectAsStateWithLifecycle()
    val waypointEditResult by viewModel.waypointEditResult.collectAsStateWithLifecycle()
    val navigationRoutes by viewModel.navigationManager.routes.collectAsStateWithLifecycle()
    val alternativeRouteMetadata by viewModel.navigationManager.alternativeRouteMetadata.collectAsStateWithLifecycle()
    val routeProgress by viewModel.navigationManager.routeProgress.collectAsStateWithLifecycle()
    val enhancedLocation by viewModel.navigationManager.enhancedLocation.collectAsStateWithLifecycle()

    var mapView by remember { mutableStateOf<MapView?>(null) }
    var trackingMode by remember { mutableStateOf<LocationTrackingMode?>(LocationTrackingMode.TiltedHeading) }

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

    var allowSheetHide by remember { mutableStateOf(false) }

    val scaffoldState = rememberBottomSheetScaffoldState(
        bottomSheetState = rememberStandardBottomSheetState(
            initialValue = SheetValue.Hidden,
            skipHiddenState = false,
            confirmValueChange = { newValue ->
                if (newValue == SheetValue.Hidden) allowSheetHide else true
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

    LaunchedEffect(searchResults, mapView) {
        if (searchResults.isEmpty()) {
            if (selectedResult == null) {
                allowSheetHide = true
                scaffoldState.bottomSheetState.hide()
                allowSheetHide = false
            }
            return@LaunchedEffect
        }
        val currentMapView = mapView ?: return@LaunchedEffect

        trackingMode = null
        sheetPeekHeight = SHEET_PEEK_HEIGHT_DEFAULT
        scaffoldState.bottomSheetState.partialExpand()

        val points = searchResults.map { fromLngLat(it.longitude, it.latitude) }
        val padding = EdgeInsets(CAMERA_PADDING_TOP, CAMERA_PADDING, CAMERA_PADDING_BOTTOM, CAMERA_PADDING)

        val cameraOptions = currentMapView.mapboxMap.cameraForCoordinates(
            coordinates = points,
            coordinatesPadding = padding,
            bearing = 0.0,
            pitch = 0.0,
        )

        viewportState.flyTo(
            cameraOptions = cameraOptions,
            animationOptions = MapAnimationOptions.Builder()
                .duration(1500)
                .build(),
        )
    }

    LaunchedEffect(selectedResult) {
        val result = selectedResult ?: run {
            if (searchResults.isEmpty()) {
                allowSheetHide = true
                scaffoldState.bottomSheetState.hide()
                allowSheetHide = false
            }
            return@LaunchedEffect
        }

        sheetPeekHeight = SHEET_PEEK_HEIGHT_DEFAULT
        trackingMode = null
        scaffoldState.bottomSheetState.partialExpand()

        viewportState.easeTo(
            cameraOptions = CameraOptions.Builder()
                .center(fromLngLat(result.longitude, result.latitude))
                .zoom(FOLLOW_PUCK_ZOOM)
                .pitch(0.0)
                .bearing(0.0)
                .build(),
            animationOptions = MapAnimationOptions.Builder()
                .duration(1500)
                .build(),
        )
    }

    LaunchedEffect(routeResults) {
        if (routeResults.isEmpty()) {
            val result = selectedResult ?: return@LaunchedEffect
            trackingMode = null
            viewportState.easeTo(
                cameraOptions = CameraOptions.Builder()
                    .center(fromLngLat(result.longitude, result.latitude))
                    .zoom(FOLLOW_PUCK_ZOOM)
                    .pitch(0.0)
                    .bearing(0.0)
                    .build(),
                animationOptions = MapAnimationOptions.Builder()
                    .duration(1500)
                    .build(),
            )
            return@LaunchedEffect
        }

        // NavigationCamera の Overview モードでルート全体を表示
        // ViewportDataSource が RoutesObserver 経由でルート情報を受け取り、最適なカメラ位置を計算
        trackingMode = null
        val sheetPeekPx = with(density) { sheetPeekHeight.toPx() }.toDouble()
        val topPadding = topAppBarHeightPx.toDouble() + ROUTE_CAMERA_MARGIN_TOP
        val bottomPadding = sheetPeekPx + ROUTE_CAMERA_MARGIN_VERTICAL
        val padding = EdgeInsets(topPadding, ROUTE_CAMERA_MARGIN_HORIZONTAL, bottomPadding, ROUTE_CAMERA_MARGIN_END)

        viewModel.navigationManager.viewportDataSource?.overviewPadding = padding
        viewModel.navigationManager.viewportDataSource?.evaluate()
        viewModel.navigationManager.requestCameraOverview()
    }

    BottomSheetScaffold(
        modifier = modifier,
        scaffoldState = scaffoldState,
        sheetPeekHeight = sheetPeekHeight,
        sheetContent = {
            HomeMapSheetContent(
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
                navigationRoutes = navigationRoutes,
                alternativeRouteMetadata = alternativeRouteMetadata,
                routeProgress = routeProgress,
                navigationManager = viewModel.navigationManager,
                onMapViewChanged = { mapView = it },
                onRouteSelected = { viewModel.onViewEvent(HomeMapViewEvent.OnRouteSelected(it)) },
            )

            HomeMapControls(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(
                        bottom = 16.dp,
                        end = 16.dp,
                    )
                    .offset(y = -sheetVisibleHeight),
                cameraBearing = viewportState.cameraState?.bearing ?: 0.0,
                deviceBearing = enhancedLocation?.bearing ?: 0.0,
                trackingMode = trackingMode,
                viewportState = viewportState,
                onTrackingModeChanged = { trackingMode = it },
            )

            if (routeResults.isNotEmpty() && waypoints.isNotEmpty()) {
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
            } else {
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

            val editingWaypoint = editingWaypointIndex?.let { waypoints.getOrNull(it) }
            val initialQuery = when (editingWaypoint) {
                is RouteWaypoint.Place -> editingWaypoint.name
                else -> null
            }

            HomeMapWaypointSearchScreen(
                modifier = Modifier.fillMaxSize(),
                isVisible = editingWaypointIndex != null,
                initialQuery = initialQuery,
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
