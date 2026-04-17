package me.matsumo.onenavi.feature.home.map

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
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
import kotlinx.collections.immutable.ImmutableList
import me.matsumo.onenavi.core.model.ArrivalInfo
import me.matsumo.onenavi.core.model.RouteWaypoint
import me.matsumo.onenavi.core.model.SearchHistory
import me.matsumo.onenavi.core.model.SearchResultItem
import me.matsumo.onenavi.core.model.SearchSuggestionItem
import me.matsumo.onenavi.core.navigation.CameraManager
import me.matsumo.onenavi.core.navigation.GuidanceSessionManager
import me.matsumo.onenavi.feature.home.map.components.HomeMapControls
import me.matsumo.onenavi.feature.home.map.components.LocationTrackingMode
import me.matsumo.onenavi.feature.home.map.components.navi.HomeMapArrivalContent
import me.matsumo.onenavi.feature.home.map.components.navi.HomeMapNaviContent
import me.matsumo.onenavi.feature.home.map.components.topappbar.HomeMapRouteTopAppBar
import me.matsumo.onenavi.feature.home.map.components.topappbar.HomeMapTopAppBar
import me.matsumo.onenavi.feature.home.map.components.topappbar.HomeMapWaypointSearchScreen
import me.matsumo.onenavi.feature.home.map.state.HomeMapOverlayState
import me.matsumo.onenavi.feature.home.map.state.HomeMapScreenState

private val SHEET_PEEK_HEIGHT_DEFAULT = 200.dp
private const val DEFAULT_TRACKING_ZOOM = 17f
private const val TRACKING_TILT_3D = 45f

@Suppress("ParamsComparedByRef")
@OptIn(ExperimentalMaterial3Api::class)
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
    val currentLocation by viewModel.cameraManager.currentLocation.collectAsStateWithLifecycle()
    val currentBearing by viewModel.cameraManager.currentBearing.collectAsStateWithLifecycle()
    val navigationCameraState by viewModel.cameraManager.cameraState.collectAsStateWithLifecycle()
    val mapPadding by viewModel.cameraManager.mapPadding.collectAsStateWithLifecycle()
    val isNavigationFollowing3D by viewModel.cameraManager.isFollowing3D.collectAsStateWithLifecycle()
    val arrivalInfo by viewModel.guidanceSessionManager.arrivalInfo.collectAsStateWithLifecycle()

    var trackingMode by remember { mutableStateOf<LocationTrackingMode?>(LocationTrackingMode.TiltedHeading) }
    var trackingZoom by remember { mutableFloatStateOf(DEFAULT_TRACKING_ZOOM) }

    val viewportState = rememberHomeMapViewportState()

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

    LaunchedEffect(currentLocation) {
        currentLocation?.let { location ->
            viewModel.onUserLocationUpdated(location.latitude, location.longitude)
        }
    }

    val cameraFollowSpec by remember(
        trackingMode,
        screenState,
        navigationCameraState,
        isNavigationFollowing3D,
        trackingZoom,
    ) {
        derivedStateOf {
            when {
                screenState is HomeMapScreenState.Navigating &&
                    navigationCameraState == me.matsumo.onenavi.core.navigation.CameraState.FOLLOWING ->
                    CameraFollowSpec(
                        zoom = trackingZoom,
                        tilt = if (isNavigationFollowing3D) TRACKING_TILT_3D else 0f,
                        useLocationBearing = isNavigationFollowing3D,
                    )

                trackingMode == LocationTrackingMode.TiltedHeading ->
                    CameraFollowSpec(
                        zoom = trackingZoom,
                        tilt = TRACKING_TILT_3D,
                        useLocationBearing = true,
                    )

                trackingMode == LocationTrackingMode.TopDownHeading ->
                    CameraFollowSpec(
                        zoom = trackingZoom,
                        tilt = 0f,
                        useLocationBearing = true,
                    )

                trackingMode == LocationTrackingMode.TopDownNorth ->
                    CameraFollowSpec(
                        zoom = trackingZoom,
                        tilt = 0f,
                        useLocationBearing = false,
                    )

                else -> null
            }
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

    HomeMapScreenCameraEffect(
        screenState = screenState,
        effects = viewModel.effects,
        routeManager = viewModel.routeManager,
        cameraManager = viewModel.cameraManager,
        viewportState = viewportState,
        sheetPeekHeightPx = with(density) { if (shouldShowSheet) sheetPeekHeight.toPx() else 0f }.toDouble(),
        topOverlayBottomPx = topOverlayBottomPx,
        navigationCameraState = navigationCameraState,
        mapPadding = mapPadding,
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
                screenState = screenState,
                routeResults = routeResults,
                selectedRouteIndex = selectedRouteIndex,
                currentLocation = currentLocation,
                currentBearing = currentBearing,
                cameraManager = viewModel.cameraManager,
                cameraFollowSpec = cameraFollowSpec,
                onMapLandmarkSelected = viewModel::onMapLandmarkSelected,
                onRouteSelected = viewModel::onRouteSelected,
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
                onArrivalDismissed = viewModel::onArrivalDismissed,
                arrivalInfo = arrivalInfo,
                trackingMode = trackingMode,
                onTrackingModeChanged = { trackingMode = it },
                onTrackingZoomChanged = { trackingZoom = it },
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
    viewportState: HomeMapViewportState,
    guidanceSessionManager: GuidanceSessionManager,
    cameraManager: CameraManager,
    bottomSheetPeekHeightPx: Float,
    onNavigationStopped: () -> Unit,
    onArrivalDismissed: () -> Unit,
    arrivalInfo: ArrivalInfo?,
    trackingMode: LocationTrackingMode?,
    onTrackingModeChanged: (LocationTrackingMode?) -> Unit,
    onTrackingZoomChanged: (Float) -> Unit,
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

        is HomeMapScreenState.Arrived -> {
            val destinationName = (screenState.destination as? RouteWaypoint.Place)?.name

            HomeMapArrivalContent(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(
                        start = 16.dp,
                        end = 16.dp,
                        bottom = 24.dp,
                    ),
                arrivalInfo = arrivalInfo,
                destinationName = destinationName,
                onFinishClicked = onArrivalDismissed,
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
                cameraBearing = viewportState.cameraState.bearing,
                trackingMode = trackingMode,
                viewportState = viewportState,
                autoFollowOnStart = screenState is HomeMapScreenState.Browsing,
                onTrackingModeChanged = onTrackingModeChanged,
                onTrackingZoomChanged = onTrackingZoomChanged,
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
    viewportState: HomeMapViewportState,
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
