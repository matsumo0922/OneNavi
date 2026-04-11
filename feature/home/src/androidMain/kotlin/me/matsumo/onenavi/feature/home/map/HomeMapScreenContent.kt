package me.matsumo.onenavi.feature.home.map

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mapbox.maps.MapView
import com.mapbox.maps.MapboxExperimental
import com.mapbox.maps.extension.compose.animation.viewport.MapViewportState
import com.mapbox.maps.extension.compose.animation.viewport.rememberMapViewportState
import com.mapbox.maps.extension.compose.style.standard.LightPresetValue
import com.mapbox.maps.extension.compose.style.standard.rememberStandardStyleState
import kotlinx.collections.immutable.ImmutableList
import me.matsumo.onenavi.core.model.RouteWaypoint
import me.matsumo.onenavi.core.model.SearchHistory
import me.matsumo.onenavi.core.model.SearchResultItem
import me.matsumo.onenavi.core.model.SearchSuggestionItem
import me.matsumo.onenavi.core.navigation.CameraManager
import me.matsumo.onenavi.core.navigation.GuidanceSessionManager
import me.matsumo.onenavi.feature.home.map.components.HomeMapControls
import me.matsumo.onenavi.feature.home.map.components.LocationTrackingMode
import me.matsumo.onenavi.feature.home.map.components.navi.HomeMapNaviContent
import me.matsumo.onenavi.feature.home.map.components.topappbar.HomeMapRouteTopAppBar
import me.matsumo.onenavi.feature.home.map.components.topappbar.HomeMapTopAppBar
import me.matsumo.onenavi.feature.home.map.components.topappbar.HomeMapWaypointSearchScreen
import me.matsumo.onenavi.feature.home.map.state.HomeMapOverlayState
import me.matsumo.onenavi.feature.home.map.state.HomeMapScreenState

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

            viewModel.onMapLandmarkSelected(
                name = name,
                latitude = point.latitude(),
                longitude = point.longitude(),
            )
            true
        }
        interactionsState.onMapLongClicked { context ->
            val point = context.coordinateInfo.coordinate

            viewModel.onMapLandmarkSelected(
                name = null,
                latitude = point.latitude(),
                longitude = point.longitude(),
            )
            true
        }
    }

    // 設計書では allowSheetHide 廃止としたが、BottomSheetState.hide() が confirmValueChange を経由するため
    // プログラムからの hide を許可するフラグとして残す必要がある
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
    var topOverlayBottomPx by remember { mutableFloatStateOf(0f) }
    var sheetPeekHeight by remember { mutableStateOf(SHEET_PEEK_HEIGHT_DEFAULT) }

    val sheetVisibleHeight by remember {
        derivedStateOf {
            val offset = runCatching { scaffoldState.bottomSheetState.requireOffset() }.getOrDefault(contentHeight)
            with(density) { (contentHeight - offset).coerceAtLeast(0f).toDp() }
        }
    }

    val shouldShowSheet = screenState is HomeMapScreenState.SearchResultsList ||
        screenState is HomeMapScreenState.PlaceDetails ||
        screenState is HomeMapScreenState.RoutePreview

    LaunchedEffect(screenState) {
        if (screenState !is HomeMapScreenState.Browsing) {
            trackingMode = null
        }
    }

    // ── 副作用 ──

    HomeMapScreenSheetEffect(
        shouldShowSheet = shouldShowSheet,
        scaffoldState = scaffoldState,
        onSheetShowing = {
            sheetPeekHeight = SHEET_PEEK_HEIGHT_DEFAULT
        },
        onAllowSheetHide = { allowSheetHide = it },
    )

    HomeMapScreenViewportTrackingEffect(
        viewportState = viewportState,
        onTrackingModeCleared = { trackingMode = null },
    )

    HomeMapScreenThemeEffect(
        isDarkTheme = isDarkTheme,
        standardStyleState = standardStyleState,
    )

    HomeMapScreenCameraEffect(
        screenStateProvider = { viewModel.screenState.value },
        effects = viewModel.effects,
        routeManager = viewModel.routeManager,
        cameraManager = viewModel.cameraManager,
        routeResultsProvider = { viewModel.routeResults.value },
        mapView = mapView,
        viewportState = viewportState,
        sheetPeekHeightPx = with(density) {
            if (shouldShowSheet) sheetPeekHeight.toPx() else 0f
        }.toDouble(),
        topOverlayBottomPx = topOverlayBottomPx,
        activity = activity,
        onTrackingModeChanged = { trackingMode = it },
    )

    BackHandler(enabled = screenState !is HomeMapScreenState.Browsing) {
        viewModel.onBackPressed()
    }

    // ── UI ──

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
                onNavigationStarted = viewModel::onNavigationStarted,
                onRouteSelected = viewModel::onRouteSelected,
                onSearchResultSelected = viewModel::onSearchResultSelected,
                onRouteSearchClicked = viewModel::onRouteSearch,
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
                screenState = screenState,
                routeResults = routeResults,
                selectedRouteIndex = selectedRouteIndex,
                routeManager = viewModel.routeManager,
                cameraManager = viewModel.cameraManager,
                onMapViewChanged = { mapView = it },
                onUserLocationUpdated = viewModel::onUserLocationUpdated,
                onRouteSelected = viewModel::onRouteSelected,
                onBearingChanged = { deviceBearing = it },
            )

            HomeMapScreenContentControls(
                screenState = screenState,
                sheetVisibleHeight = sheetVisibleHeight,
                viewportState = viewportState,
                guidanceSessionManager = viewModel.guidanceSessionManager,
                cameraManager = viewModel.cameraManager,
                bottomSheetPeekHeightPx = with(density) {
                    if (shouldShowSheet) sheetPeekHeight.toPx() else 0f
                },
                onNavigationStopped = viewModel::onNavigationStopped,
                deviceBearing = deviceBearing,
                trackingMode = trackingMode,
                onTrackingModeChanged = { trackingMode = it },
            )

            HomeMapScreenContentTopAppBar(
                screenState = screenState,
                suggestions = suggestions,
                histories = histories,
                selectedResult = selectedResult,
                waypoints = waypoints,
                waypointEditResult = waypointEditResult,
                viewportState = viewportState,
                onQueryChanged = viewModel::onQueryChanged,
                onSearch = viewModel::onSearch,
                onSuggestionSelected = viewModel::onSuggestionSelected,
                onHistorySelected = viewModel::onHistorySelected,
                onRemoveHistory = viewModel::onRemoveHistory,
                onDismissSearchResult = viewModel::onDismissSearchResults,
                onWaypointEditResultConsumed = viewModel::consumeWaypointEditResult,
                onDismissRoutes = viewModel::onDismissRoutes,
                onSwapOriginDestination = viewModel::onSwapOriginDestination,
                onRouteWaypointsConfirmed = viewModel::onRouteWaypointsConfirmed,
                onWaypointClicked = viewModel::onWaypointClicked,
                onTopOverlayBottomChanged = { topOverlayBottomPx = it },
            )

            HomeMapScreenContentOverlay(
                overlayState = overlayState,
                suggestions = suggestions,
                histories = histories,
                onWaypointSuggestionSelected = viewModel::onWaypointSuggestionSelected,
                onWaypointHistorySelected = viewModel::onWaypointHistorySelected,
                onRemoveHistory = viewModel::onRemoveHistory,
                onQueryChanged = viewModel::onQueryChanged,
                onWaypointSearchDismissed = viewModel::onWaypointSearchDismissed,
            )
        }
    }
}

/**
 * ナビ中は HomeMapNaviContent、それ以外は地図コントロールを表示する。
 */
@Composable
private fun BoxScope.HomeMapScreenContentControls(
    screenState: HomeMapScreenState,
    sheetVisibleHeight: Dp,
    viewportState: MapViewportState,
    guidanceSessionManager: GuidanceSessionManager,
    cameraManager: CameraManager,
    bottomSheetPeekHeightPx: Float,
    onNavigationStopped: () -> Unit,
    deviceBearing: Double,
    trackingMode: LocationTrackingMode?,
    onTrackingModeChanged: (LocationTrackingMode?) -> Unit,
) {
    when (screenState) {
        is HomeMapScreenState.Navigating -> {
            HomeMapNaviContent(
                modifier = Modifier.fillMaxSize(),
                guidanceSessionManager = guidanceSessionManager,
                cameraManager = cameraManager,
                bottomSheetPeekHeightPx = bottomSheetPeekHeightPx,
                onNavigationStopped = onNavigationStopped,
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
                autoFollowOnStart = screenState is HomeMapScreenState.Browsing,
                onTrackingModeChanged = onTrackingModeChanged,
            )
        }
    }
}

/**
 * screenState に応じた TopAppBar を表示する。
 */
@Composable
private fun BoxScope.HomeMapScreenContentTopAppBar(
    screenState: HomeMapScreenState,
    suggestions: ImmutableList<SearchSuggestionItem>,
    histories: ImmutableList<SearchHistory>,
    selectedResult: SearchResultItem?,
    waypoints: ImmutableList<RouteWaypoint>,
    waypointEditResult: Pair<Int, RouteWaypoint.Place>?,
    viewportState: MapViewportState,
    onQueryChanged: (String) -> Unit,
    onSearch: (String, Double?, Double?) -> Unit,
    onSuggestionSelected: (SearchSuggestionItem) -> Unit,
    onHistorySelected: (SearchHistory) -> Unit,
    onRemoveHistory: (String) -> Unit,
    onDismissSearchResult: () -> Unit,
    onWaypointEditResultConsumed: () -> Unit,
    onDismissRoutes: () -> Unit,
    onSwapOriginDestination: () -> Unit,
    onRouteWaypointsConfirmed: (ImmutableList<RouteWaypoint>) -> Unit,
    onWaypointClicked: (Int) -> Unit,
    onTopOverlayBottomChanged: (Float) -> Unit,
) {
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
                    .onGloballyPositioned { coordinates ->
                        onTopOverlayBottomChanged(
                            coordinates.positionInParent().y + coordinates.size.height.toFloat(),
                        )
                    },
                suggestions = suggestions,
                histories = histories,
                selectedResult = selectedResult,
                viewportState = viewportState,
                onQueryChanged = onQueryChanged,
                onSearch = onSearch,
                onSuggestionSelected = onSuggestionSelected,
                onHistorySelected = onHistorySelected,
                onRemoveHistory = onRemoveHistory,
                onDismissSearchResult = onDismissSearchResult,
            )
        }
        is HomeMapScreenState.RoutePreview -> {
            HomeMapRouteTopAppBar(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .onGloballyPositioned { coordinates ->
                        onTopOverlayBottomChanged(
                            coordinates.positionInParent().y + coordinates.size.height.toFloat(),
                        )
                    },
                waypoints = waypoints,
                waypointEditResult = waypointEditResult,
                onWaypointEditResultConsumed = onWaypointEditResultConsumed,
                onDismissRoutes = onDismissRoutes,
                onSwapOriginDestination = onSwapOriginDestination,
                onRouteWaypointsConfirmed = onRouteWaypointsConfirmed,
                onWaypointClicked = onWaypointClicked,
            )
        }
        is HomeMapScreenState.Navigating,
        is HomeMapScreenState.Arrived,
        -> {
            SideEffect {
                onTopOverlayBottomChanged(0f)
            }
        }
    }
}

/**
 * Overlay（WaypointSearch）を表示する。
 */
@Composable
private fun HomeMapScreenContentOverlay(
    overlayState: HomeMapOverlayState,
    suggestions: ImmutableList<SearchSuggestionItem>,
    histories: ImmutableList<SearchHistory>,
    onWaypointSuggestionSelected: (SearchSuggestionItem) -> Unit,
    onWaypointHistorySelected: (SearchHistory) -> Unit,
    onRemoveHistory: (String) -> Unit,
    onQueryChanged: (String) -> Unit,
    onWaypointSearchDismissed: () -> Unit,
) {
    if (overlayState is HomeMapOverlayState.WaypointSearch) {
        HomeMapWaypointSearchScreen(
            modifier = Modifier.fillMaxSize(),
            isVisible = true,
            initialQuery = overlayState.initialQuery,
            suggestions = suggestions,
            histories = histories,
            onSuggestionSelected = onWaypointSuggestionSelected,
            onHistorySelected = onWaypointHistorySelected,
            onRemoveHistory = onRemoveHistory,
            onQueryChanged = onQueryChanged,
            onDismiss = onWaypointSearchDismissed,
        )
    }
}
