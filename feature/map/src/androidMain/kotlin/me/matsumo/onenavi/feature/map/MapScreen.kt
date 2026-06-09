package me.matsumo.onenavi.feature.map

import android.view.Display
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.BottomSheetScaffoldState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationEventHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.PointOfInterest
import me.matsumo.onenavi.core.navigation.newguidance.model.GuidanceState
import me.matsumo.onenavi.core.navigation.newguidance.model.RoutePreviewState
import me.matsumo.onenavi.core.ui.screen.Destination
import me.matsumo.onenavi.core.ui.theme.LocalAppSetting
import me.matsumo.onenavi.core.ui.theme.LocalNavBackStack
import me.matsumo.onenavi.core.ui.theme.shouldUseDarkTheme
import me.matsumo.onenavi.feature.map.components.MapControls
import me.matsumo.onenavi.feature.map.components.MapRecenterButton
import me.matsumo.onenavi.feature.map.components.bottomsheet.MapPlaceDetailSheet
import me.matsumo.onenavi.feature.map.components.bottomsheet.MapRoutePreviewSheet
import me.matsumo.onenavi.feature.map.components.bottomsheet.MapSearchResultsSheet
import me.matsumo.onenavi.feature.map.components.content.MapBrowsingContent
import me.matsumo.onenavi.feature.map.components.content.MapNavigationContent
import me.matsumo.onenavi.feature.map.components.content.MapRoutePreviewContent
import me.matsumo.onenavi.feature.map.components.topappbar.MapWaypointSearchScreen
import me.matsumo.onenavi.feature.map.state.MAP_CONTROLS_COLUMN_WIDTH
import me.matsumo.onenavi.feature.map.state.MapCameraState
import me.matsumo.onenavi.feature.map.state.MapCanvasLayout
import me.matsumo.onenavi.feature.map.state.MapOverlayState
import me.matsumo.onenavi.feature.map.state.MapPanelLayout
import me.matsumo.onenavi.feature.map.state.MapPanelSide
import me.matsumo.onenavi.feature.map.state.MapScreenState
import me.matsumo.onenavi.feature.map.state.MapUiEvent
import me.matsumo.onenavi.feature.map.state.MapUiState
import me.matsumo.onenavi.feature.map.state.VehicleLocationState
import me.matsumo.onenavi.feature.map.state.rememberMapCameraState
import me.matsumo.onenavi.feature.map.state.resolveMapPanelLayout
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(modifier: Modifier = Modifier) {
    val viewModel = koinViewModel<MapViewModel>()

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val screenState by viewModel.currentScreenState.collectAsStateWithLifecycle()
    val hasScreenStateStack by viewModel.hasScreenStateStack.collectAsStateWithLifecycle()
    val routePreviewState by viewModel.newRoutePreviewState.collectAsStateWithLifecycle()
    val guidanceState by viewModel.newGuidanceState.collectAsStateWithLifecycle()
    val vehicleLocationState by viewModel.vehicleLocationState.collectAsStateWithLifecycle()

    val navigationBarHeightDp = WindowInsets.navigationBars
        .asPaddingValues()
        .calculateBottomPadding()
    val statusBarHeightDp = WindowInsets.statusBars
        .asPaddingValues()
        .calculateTopPadding()

    var googleMap by remember { mutableStateOf<GoogleMap?>(null) }
    var allowSheetHide by remember { mutableStateOf(false) }
    val hasSheetOverlay = uiState.overlayState.isSheetOverlay()
    val shouldShowSheet by remember(screenState, uiState.overlayState, uiState.bottomSheetPeekHeight) {
        derivedStateOf {
            val hasScreenStateSheet = screenState::class in SHEET_VISIBLE_STATES
            val hasSheetContent = hasSheetOverlay || hasScreenStateSheet
            hasSheetContent && uiState.bottomSheetPeekHeight > 0.dp
        }
    }

    val density = LocalDensity.current
    val display = LocalView.current.display
    val appSetting = LocalAppSetting.current
    val navBackStack = LocalNavBackStack.current
    val isMapDarkMode = shouldUseDarkTheme(appSetting.theme)
    val isNavigating = screenState is MapScreenState.Navigating
    val navigationCardHeightDp = with(density) { uiState.navigationCardHeight.toDp() }
    val topAppBarHeightDp = with(density) { uiState.topAppBarHeight.toDp() }
    val waypointSearchOverlay = uiState.overlayState as? MapOverlayState.WaypointSearch
    val isAddWaypointSearchOverlay = uiState.overlayState is MapOverlayState.AddWaypointSearch
    val shouldShowWaypointSearchOverlay = isAddWaypointSearchOverlay || waypointSearchOverlay != null

    val navigationState = rememberNavigationEventState(NavigationEventInfo.None)
    val cameraState = rememberMapCameraState()
    val scaffoldState = rememberBottomSheetScaffoldState(
        bottomSheetState = rememberStandardBottomSheetState(
            initialValue = SheetValue.Hidden,
            skipHiddenState = false,
            confirmValueChange = { newValue ->
                if (newValue == SheetValue.Hidden) allowSheetHide else true
            },
        ),
    )

    NavigationEventHandler(
        state = navigationState,
        isBackEnabled = hasSheetOverlay || (hasScreenStateStack && screenState !is MapScreenState.Navigating),
    ) {
        if (hasSheetOverlay) {
            viewModel.onUiEvent(MapUiEvent.OnOverlaySheetDismissed)
        } else {
            viewModel.popScreenState()
        }
    }

    LaunchedEffect(shouldShowSheet) {
        if (shouldShowSheet) {
            allowSheetHide = false
            scaffoldState.bottomSheetState.partialExpand()
        } else {
            allowSheetHide = true
            scaffoldState.bottomSheetState.hide()
            viewModel.onUiEvent(MapUiEvent.OnBottomSheetPeekHeightChanged(0.dp))
        }
    }

    BoxWithConstraints(
        modifier = modifier.fillMaxSize(),
    ) {
        val panelLayout = remember(maxWidth) {
            resolveMapPanelLayout(maxWidth = maxWidth)
        }
        val shouldDisableMapCanvasExtension = remember(
            display?.displayId,
            display?.name,
        ) {
            display.isCarVirtualDisplayProbe()
        }
        val mapCanvasLayout = remember(panelLayout, maxWidth, shouldDisableMapCanvasExtension) {
            if (shouldDisableMapCanvasExtension) {
                MapCanvasLayout(
                    width = maxWidth,
                    offsetX = 0.dp,
                    horizontalInset = 0.dp,
                )
            } else {
                panelLayout.resolveCanvasLayout(viewportWidth = maxWidth)
            }
        }
        val viewportWidthPx = with(density) { maxWidth.roundToPx() }
        val viewportHeightPx = with(density) { maxHeight.roundToPx() }
        val controlsBottomPadding by animateDpAsState(
            targetValue = resolveMapControlsBottomPadding(
                hasSheetOverlay = hasSheetOverlay,
                isNavigating = isNavigating,
                shouldShowSheet = shouldShowSheet,
                isSplit = panelLayout.isSplit,
                bottomSheetPeekHeight = uiState.bottomSheetPeekHeight,
                navigationCardHeight = navigationCardHeightDp,
                navigationBarHeight = navigationBarHeightDp,
            ),
            label = "ControlsBottomPadding",
        )
        val controlsTopPadding by animateDpAsState(
            targetValue = resolveMapControlsTopPadding(
                isSplit = panelLayout.isSplit,
                statusBarHeight = statusBarHeightDp,
                topAppBarHeight = topAppBarHeightDp,
            ),
            label = "ControlsTopPadding",
        )

        googleMap?.let {
            MapCameraEffect(
                uiState = uiState,
                screenState = screenState,
                routePreviewState = routePreviewState,
                overlayState = uiState.overlayState,
                guidanceState = guidanceState,
                vehicleLocationState = vehicleLocationState,
                cameraState = cameraState,
                panelLayout = panelLayout,
                viewportWidthPx = viewportWidthPx,
                viewportHeightPx = viewportHeightPx,
            )
        }

        if (panelLayout.isSplit) {
            MapScreenSplitLayout(
                modifier = Modifier.fillMaxSize(),
                uiState = uiState,
                screenState = screenState,
                routePreviewState = routePreviewState,
                guidanceState = guidanceState,
                vehicleLocationState = vehicleLocationState,
                googleMap = googleMap,
                cameraState = cameraState,
                panelLayout = panelLayout,
                mapCanvasLayout = mapCanvasLayout,
                isMapDarkMode = isMapDarkMode,
                controlsTopPadding = controlsTopPadding,
                controlsBottomPadding = controlsBottomPadding,
                scaffoldState = scaffoldState,
                onMapUpdate = { googleMap = it },
                onPointOfInterestClicked = { pointOfInterest ->
                    viewModel.onUiEvent(pointOfInterest.toMapPointOfInterestSelectedEvent())
                },
                onMapLongClicked = { latLng ->
                    viewModel.onUiEvent(latLng.toMapLongPressedEvent())
                },
                onRouteSelected = { index ->
                    viewModel.onUiEvent(MapUiEvent.OnRouteIndexChanged(index))
                },
                onUiEvent = viewModel::onUiEvent,
                onSettingClicked = {
                    navBackStack.add(Destination.Setting.Root)
                },
            )
        } else {
            MapScreenCompactLayout(
                modifier = Modifier.fillMaxSize(),
                uiState = uiState,
                screenState = screenState,
                routePreviewState = routePreviewState,
                guidanceState = guidanceState,
                vehicleLocationState = vehicleLocationState,
                googleMap = googleMap,
                cameraState = cameraState,
                panelLayout = panelLayout,
                mapCanvasLayout = mapCanvasLayout,
                isMapDarkMode = isMapDarkMode,
                controlsTopPadding = controlsTopPadding,
                controlsBottomPadding = controlsBottomPadding,
                scaffoldState = scaffoldState,
                onMapUpdate = { googleMap = it },
                onPointOfInterestClicked = { pointOfInterest ->
                    viewModel.onUiEvent(pointOfInterest.toMapPointOfInterestSelectedEvent())
                },
                onMapLongClicked = { latLng ->
                    viewModel.onUiEvent(latLng.toMapLongPressedEvent())
                },
                onRouteSelected = { index ->
                    viewModel.onUiEvent(MapUiEvent.OnRouteIndexChanged(index))
                },
                onUiEvent = viewModel::onUiEvent,
                onSettingClicked = {
                    navBackStack.add(Destination.Setting.Root)
                },
            )
        }

        MapRecenterButton(
            modifier = Modifier
                .fillMaxHeight()
                .width(panelLayout.visibleMapWidth(maxWidth))
                .align(panelLayout.toMapColumnAlignment()),
            isVisible = screenState.allowsRecenterButton() && !cameraState.cameraState.isFollowingMyLocation,
            bottomPadding = controlsBottomPadding,
            onClicked = {
                if (isNavigating) {
                    viewModel.onUiEvent(MapUiEvent.OnNavigationRoutePreviewDismissed)
                    cameraState.startGuidanceCamera(vehicleLocationState)
                } else {
                    cameraState.followVehicleLocation(vehicleLocationState)
                }
            },
        )

        MapWaypointSearchScreen(
            modifier = Modifier.fillMaxSize(),
            isVisible = shouldShowWaypointSearchOverlay,
            initialQuery = waypointSearchOverlay?.initialQuery,
            suggestions = uiState.suggestions,
            histories = uiState.histories,
            onSearch = { query ->
                when {
                    isAddWaypointSearchOverlay -> {
                        viewModel.onUiEvent(
                            MapUiEvent.OnAddWaypointSearch(
                                query = query,
                                latitude = cameraState.myLocationLatitude,
                                longitude = cameraState.myLocationLongitude,
                            ),
                        )
                    }

                    waypointSearchOverlay != null -> {
                        viewModel.onUiEvent(
                            MapUiEvent.OnWaypointSearch(
                                query = query,
                                latitude = cameraState.myLocationLatitude,
                                longitude = cameraState.myLocationLongitude,
                            ),
                        )
                    }
                }
            },
            onUiEvent = viewModel::onUiEvent,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MapScreenCompactLayout(
    uiState: MapUiState,
    screenState: MapScreenState,
    routePreviewState: RoutePreviewState,
    guidanceState: GuidanceState,
    vehicleLocationState: VehicleLocationState?,
    googleMap: GoogleMap?,
    cameraState: MapCameraState,
    panelLayout: MapPanelLayout,
    mapCanvasLayout: MapCanvasLayout,
    isMapDarkMode: Boolean,
    controlsTopPadding: Dp,
    controlsBottomPadding: Dp,
    scaffoldState: BottomSheetScaffoldState,
    onMapUpdate: (GoogleMap?) -> Unit,
    onPointOfInterestClicked: (PointOfInterest) -> Unit,
    onMapLongClicked: (LatLng) -> Unit,
    onRouteSelected: (Int) -> Unit,
    onUiEvent: (MapUiEvent) -> Unit,
    onSettingClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BottomSheetScaffold(
        modifier = modifier,
        scaffoldState = scaffoldState,
        sheetPeekHeight = uiState.bottomSheetPeekHeight,
        sheetContent = {
            MapScreenBottomSheetContent(
                modifier = Modifier.fillMaxSize(),
                screenState = screenState,
                overlayState = uiState.overlayState,
                routePreviewState = routePreviewState,
                cameraState = cameraState,
                onUiEvent = onUiEvent,
            )
        },
    ) { _ ->
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            MapScreenMapLayer(
                modifier = Modifier.fillMaxSize(),
                uiState = uiState,
                screenState = screenState,
                routePreviewState = routePreviewState,
                guidanceState = guidanceState,
                vehicleLocationState = vehicleLocationState,
                googleMap = googleMap,
                cameraState = cameraState,
                mapCanvasLayout = mapCanvasLayout,
                isMapDarkMode = isMapDarkMode,
                onMapUpdate = onMapUpdate,
                onPointOfInterestClicked = onPointOfInterestClicked,
                onMapLongClicked = onMapLongClicked,
                onRouteSelected = onRouteSelected,
            )

            MapScreenContent(
                modifier = Modifier.fillMaxSize(),
                uiState = uiState,
                screenState = screenState,
                guidanceState = guidanceState,
                cameraState = cameraState,
                panelLayout = panelLayout,
                onUiEvent = onUiEvent,
                onSettingClicked = onSettingClicked,
            )

            MapControls(
                modifier = Modifier.fillMaxSize(),
                cameraState = cameraState,
                panelLayout = panelLayout,
                topPadding = controlsTopPadding,
                bottomPadding = controlsBottomPadding,
                isNavigating = screenState is MapScreenState.Navigating,
                onSettingClicked = onSettingClicked,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MapScreenSplitLayout(
    uiState: MapUiState,
    screenState: MapScreenState,
    routePreviewState: RoutePreviewState,
    guidanceState: GuidanceState,
    vehicleLocationState: VehicleLocationState?,
    googleMap: GoogleMap?,
    cameraState: MapCameraState,
    panelLayout: MapPanelLayout,
    mapCanvasLayout: MapCanvasLayout,
    isMapDarkMode: Boolean,
    controlsTopPadding: Dp,
    controlsBottomPadding: Dp,
    scaffoldState: BottomSheetScaffoldState,
    onMapUpdate: (GoogleMap?) -> Unit,
    onPointOfInterestClicked: (PointOfInterest) -> Unit,
    onMapLongClicked: (LatLng) -> Unit,
    onRouteSelected: (Int) -> Unit,
    onUiEvent: (MapUiEvent) -> Unit,
    onSettingClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier,
    ) {
        MapScreenMapLayer(
            modifier = Modifier.fillMaxSize(),
            uiState = uiState,
            screenState = screenState,
            routePreviewState = routePreviewState,
            guidanceState = guidanceState,
            vehicleLocationState = vehicleLocationState,
            googleMap = googleMap,
            cameraState = cameraState,
            mapCanvasLayout = mapCanvasLayout,
            isMapDarkMode = isMapDarkMode,
            onMapUpdate = onMapUpdate,
            onPointOfInterestClicked = onPointOfInterestClicked,
            onMapLongClicked = onMapLongClicked,
            onRouteSelected = onRouteSelected,
        )

        MapControls(
            modifier = Modifier.fillMaxSize(),
            cameraState = cameraState,
            panelLayout = panelLayout,
            topPadding = controlsTopPadding,
            bottomPadding = controlsBottomPadding,
            isNavigating = screenState is MapScreenState.Navigating,
            onSettingClicked = onSettingClicked,
        )

        BottomSheetScaffold(
            modifier = Modifier
                .width(panelLayout.panelWidth)
                .fillMaxHeight()
                .align(panelLayout.toPanelAlignment())
                .absoluteOffset(x = panelLayout.panelControlsOffsetX()),
            scaffoldState = scaffoldState,
            sheetPeekHeight = uiState.bottomSheetPeekHeight,
            containerColor = Color.Transparent,
            sheetContent = {
                MapScreenBottomSheetContent(
                    modifier = Modifier.fillMaxSize(),
                    screenState = screenState,
                    overlayState = uiState.overlayState,
                    routePreviewState = routePreviewState,
                    cameraState = cameraState,
                    onUiEvent = onUiEvent,
                )
            },
        ) { _ ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Transparent),
                contentAlignment = Alignment.Center,
            ) {
                MapScreenContent(
                    modifier = Modifier.fillMaxSize(),
                    uiState = uiState,
                    screenState = screenState,
                    guidanceState = guidanceState,
                    cameraState = cameraState,
                    panelLayout = panelLayout,
                    onUiEvent = onUiEvent,
                    onSettingClicked = onSettingClicked,
                )
            }
        }
    }
}

@Composable
private fun MapScreenMapLayer(
    uiState: MapUiState,
    screenState: MapScreenState,
    routePreviewState: RoutePreviewState,
    guidanceState: GuidanceState,
    vehicleLocationState: VehicleLocationState?,
    googleMap: GoogleMap?,
    cameraState: MapCameraState,
    mapCanvasLayout: MapCanvasLayout,
    isMapDarkMode: Boolean,
    onMapUpdate: (GoogleMap?) -> Unit,
    onPointOfInterestClicked: (PointOfInterest) -> Unit,
    onMapLongClicked: (LatLng) -> Unit,
    onRouteSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier,
    ) {
        MapItem(
            modifier = Modifier.fillMaxSize(),
            googleMap = googleMap,
            cameraState = cameraState,
            isDarkMode = isMapDarkMode,
            mapCanvasLayout = mapCanvasLayout,
            onMapUpdate = onMapUpdate,
            onPointOfInterestClicked = onPointOfInterestClicked,
            onMapLongClicked = onMapLongClicked,
        )

        googleMap?.let {
            MapScreenMapCanvasLayer(
                modifier = Modifier.fillMaxSize(),
                mapCanvasLayout = mapCanvasLayout,
            ) {
                MapEffect(
                    modifier = Modifier.fillMaxSize(),
                    screenState = screenState,
                    routePreviewState = routePreviewState,
                    overlayState = uiState.overlayState,
                    guidanceState = guidanceState,
                    vehicleLocationState = vehicleLocationState,
                    googleMap = it,
                    cameraState = cameraState,
                    topAppBarHeightPx = uiState.topAppBarHeight,
                    bottomSheetPeekHeight = uiState.bottomSheetPeekHeight,
                    navigationCardHeightPx = uiState.navigationCardHeight,
                    horizontalViewportPadding = mapCanvasLayout.horizontalInset,
                    onRouteSelected = onRouteSelected,
                )
            }
        }
    }
}

@Composable
private fun MapScreenMapCanvasLayer(
    mapCanvasLayout: MapCanvasLayout,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Layout(
        modifier = modifier,
        content = content,
    ) { measurables, constraints ->
        val viewportWidthPx = constraints.maxWidth
        val viewportHeightPx = constraints.maxHeight
        val canvasWidthPx = mapCanvasLayout.width.roundToPx().coerceAtLeast(viewportWidthPx)
        val canvasOffsetXPx = mapCanvasLayout.offsetX.roundToPx()
        val canvasConstraints = Constraints.fixed(
            width = canvasWidthPx,
            height = viewportHeightPx,
        )
        val placeables = measurables.map { measurable ->
            measurable.measure(canvasConstraints)
        }

        layout(
            width = viewportWidthPx,
            height = viewportHeightPx,
        ) {
            placeables.forEach { placeable ->
                placeable.place(
                    x = canvasOffsetXPx,
                    y = 0,
                )
            }
        }
    }
}

@Composable
private fun MapScreenContent(
    uiState: MapUiState,
    screenState: MapScreenState,
    guidanceState: GuidanceState,
    cameraState: MapCameraState,
    panelLayout: MapPanelLayout,
    onUiEvent: (MapUiEvent) -> Unit,
    onSettingClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val navigationCardHeightDp = with(density) { uiState.navigationCardHeight.toDp() }

    when (screenState) {
        is MapScreenState.Browsing,
        is MapScreenState.PlaceDetails,
        is MapScreenState.SearchResultsList,
        -> {
            MapBrowsingContent(
                modifier = modifier,
                cameraState = cameraState,
                uiState = uiState,
                showSettingAction = screenState is MapScreenState.Browsing,
                onUiEvent = onUiEvent,
                onSettingClicked = onSettingClicked,
            )
        }

        is MapScreenState.RoutePreview -> {
            MapRoutePreviewContent(
                modifier = modifier,
                screenState = screenState,
                uiState = uiState,
                isSheetOverlayVisible = uiState.overlayState.isSheetOverlay(),
                panelLayout = panelLayout,
                onUiEvent = onUiEvent,
            )
        }

        is MapScreenState.Navigating -> {
            MapNavigationContent(
                modifier = modifier,
                guidanceState = guidanceState,
                navigationGuideImage = uiState.navigationGuideImage,
                overlayState = uiState.overlayState,
                panelLayout = panelLayout,
                navigationCardHeight = navigationCardHeightDp,
                onUiEvent = onUiEvent,
            )
        }

        is MapScreenState.Arrived -> Unit
    }
}

@Composable
private fun MapScreenBottomSheetContent(
    screenState: MapScreenState,
    overlayState: MapOverlayState,
    routePreviewState: RoutePreviewState,
    cameraState: MapCameraState,
    onUiEvent: (MapUiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (overlayState) {
        is MapOverlayState.PlaceDetails -> {
            MapPlaceDetailSheet(
                modifier = modifier,
                cameraState = cameraState,
                selectedResult = overlayState.place,
                isAddWaypointAction = screenState.supportsPlaceAddWaypoint(),
                isPrimaryActionEnabled = screenState.canAddWaypointFromPlaceDetails(),
                onUiEvent = onUiEvent,
            )
            return
        }

        is MapOverlayState.SearchResults -> {
            MapSearchResultsSheet(
                modifier = modifier,
                query = overlayState.query,
                results = overlayState.results,
                onResultClicked = { result ->
                    onUiEvent(MapUiEvent.OnSearchResultSelected(result))
                },
                onUiEvent = onUiEvent,
            )
            return
        }

        MapOverlayState.AddWaypointSearch,
        is MapOverlayState.AddWaypointSearchResults,
        is MapOverlayState.AddWaypointSelected,
        is MapOverlayState.AddWaypointAlternatives,
        is MapOverlayState.NavigationAlternatives,
        is MapOverlayState.NavigationWaypointEditor,
        MapOverlayState.None,
        is MapOverlayState.WaypointSearch,
        -> Unit
    }

    when (screenState) {
        is MapScreenState.Browsing -> Unit
        is MapScreenState.PlaceDetails -> {
            MapPlaceDetailSheet(
                modifier = modifier,
                cameraState = cameraState,
                selectedResult = screenState.place,
                isAddWaypointAction = false,
                isPrimaryActionEnabled = true,
                onUiEvent = onUiEvent,
            )
        }

        is MapScreenState.SearchResultsList -> {
            MapSearchResultsSheet(
                modifier = modifier,
                query = screenState.query,
                results = screenState.results,
                onResultClicked = { result ->
                    onUiEvent(MapUiEvent.OnSearchResultSelected(result))
                },
                onUiEvent = onUiEvent,
            )
        }

        is MapScreenState.RoutePreview -> {
            val ready = routePreviewState as? RoutePreviewState.Ready
            if (ready != null) {
                MapRoutePreviewSheet(
                    routes = ready.routes,
                    selectedRouteIndex = ready.selectedIndex,
                    onUiEvent = onUiEvent,
                )
            }
        }

        is MapScreenState.Navigating -> Unit
        is MapScreenState.Arrived -> Unit
    }
}

/**
 * map controls カラム上グループ（設定/音量/コンパス）の上 padding を返す。
 *
 * 分割レイアウトではトップパネルは UI 帯ペイン内に収まり controls カラムへ被らないため status bar 分のみ。
 * Compact ではトップバー（検索バー / 案内パネル）が地図に重なるため、その下端へ揃える。トップバーは
 * `.statusBarsPadding()` の内側で高さ計測されるため [topAppBarHeight] は status bar を含まない。よって
 * 下端 = status bar + トップバー高さ。トップバー未表示時（0dp）は status bar 分だけ確保される。
 *
 * @param isSplit 分割レイアウトか
 * @param statusBarHeight ステータスバー高さ
 * @param topAppBarHeight 計測済みトップバー高さ（status bar を含まない）。未表示時は 0dp
 * @return controls 上グループへ与える上 padding
 */
private fun resolveMapControlsTopPadding(
    isSplit: Boolean,
    statusBarHeight: Dp,
    topAppBarHeight: Dp,
): Dp {
    if (isSplit) {
        return statusBarHeight
    }

    return statusBarHeight + topAppBarHeight
}

private fun resolveMapControlsBottomPadding(
    hasSheetOverlay: Boolean,
    isNavigating: Boolean,
    shouldShowSheet: Boolean,
    isSplit: Boolean,
    bottomSheetPeekHeight: Dp,
    navigationCardHeight: Dp,
    navigationBarHeight: Dp,
): Dp {
    if (isSplit) {
        return if (navigationBarHeight != 0.dp) navigationBarHeight else 8.dp
    }

    return when {
        hasSheetOverlay -> bottomSheetPeekHeight
        isNavigating -> navigationCardHeight.coerceAtLeast(navigationBarHeight)
        shouldShowSheet -> bottomSheetPeekHeight
        else -> navigationBarHeight
    }
}

private fun MapPanelLayout.toPanelAlignment(): Alignment {
    return when (panelSide) {
        MapPanelSide.LEFT -> AbsoluteAlignment.CenterLeft
        MapPanelSide.RIGHT -> AbsoluteAlignment.CenterRight
    }
}

/**
 * 可視地図領域（UI 帯の反対側）へ寄せる縦中央寄せの alignment を返す。
 *
 * UI 帯が右なら地図は左側、左なら右側に見えるため、その側へ寄せる。
 *
 * @return 可視地図領域側へ寄せる alignment
 */
/**
 * 「現在地に戻る」ボタンを出してよい画面状態かを返す。
 *
 * ブラウジング / 案内中 / 到着のみ許可し、検索結果・地点詳細・ルートプレビューでは出さない。
 *
 * @return ボタン表示を許可する場合 true
 */
private fun MapScreenState.allowsRecenterButton(): Boolean {
    return when (this) {
        MapScreenState.Browsing,
        MapScreenState.Navigating,
        is MapScreenState.Arrived,
        -> true

        is MapScreenState.SearchResultsList,
        is MapScreenState.PlaceDetails,
        is MapScreenState.RoutePreview,
        -> false
    }
}

private fun MapPanelLayout.toMapColumnAlignment(): Alignment {
    return when (panelSide) {
        MapPanelSide.LEFT -> AbsoluteAlignment.CenterRight
        MapPanelSide.RIGHT -> AbsoluteAlignment.CenterLeft
    }
}

/**
 * UI パネルを画面端の map controls カラムぶん内側へ寄せるオフセットを返す。
 *
 * 分割時は map controls が画面端の専用カラムに置かれるため、パネルはその幅
 * （[MAP_CONTROLS_COLUMN_WIDTH]）ぶん中央側へずらして重なりを避ける。
 *
 * @return パネルへ与える X 方向オフセット。Compact では 0dp
 */
private fun MapPanelLayout.panelControlsOffsetX(): Dp {
    if (!isSplit) {
        return 0.dp
    }

    return when (panelSide) {
        MapPanelSide.LEFT -> MAP_CONTROLS_COLUMN_WIDTH
        MapPanelSide.RIGHT -> -MAP_CONTROLS_COLUMN_WIDTH
    }
}

private fun PointOfInterest.toMapPointOfInterestSelectedEvent(): MapUiEvent {
    return MapUiEvent.OnMapPointOfInterestSelected(
        placeId = placeId.orEmpty(),
        name = name.orEmpty(),
        latitude = latLng.latitude,
        longitude = latLng.longitude,
    )
}

private fun LatLng.toMapLongPressedEvent(): MapUiEvent {
    return MapUiEvent.OnMapLongPressed(
        latitude = latitude,
        longitude = longitude,
    )
}

private fun Display?.isCarVirtualDisplayProbe(): Boolean {
    return this?.name?.startsWith(CAR_VIRTUAL_DISPLAY_NAME_PREFIX) == true
}

/** 車載向け検証用 VirtualDisplay の display name prefix。 */
private const val CAR_VIRTUAL_DISPLAY_NAME_PREFIX = "OneNaviCarVirtualDisplayProbe"

private val SHEET_VISIBLE_STATES = listOf(
    MapScreenState.SearchResultsList::class,
    MapScreenState.PlaceDetails::class,
    MapScreenState.RoutePreview::class,
)

private fun MapOverlayState.isSheetOverlay(): Boolean {
    return when (this) {
        is MapOverlayState.PlaceDetails,
        is MapOverlayState.SearchResults,
        -> true

        MapOverlayState.AddWaypointSearch,
        is MapOverlayState.AddWaypointSearchResults,
        is MapOverlayState.AddWaypointSelected,
        is MapOverlayState.AddWaypointAlternatives,
        is MapOverlayState.NavigationAlternatives,
        is MapOverlayState.NavigationWaypointEditor,
        MapOverlayState.None,
        is MapOverlayState.WaypointSearch,
        -> false
    }
}

private fun MapScreenState.supportsPlaceAddWaypoint(): Boolean {
    return when (this) {
        is MapScreenState.RoutePreview,
        MapScreenState.Navigating,
        -> true

        is MapScreenState.Arrived,
        MapScreenState.Browsing,
        is MapScreenState.PlaceDetails,
        is MapScreenState.SearchResultsList,
        -> false
    }
}

private fun MapScreenState.canAddWaypointFromPlaceDetails(): Boolean {
    return when (this) {
        is MapScreenState.RoutePreview -> waypoints.size < MAP_ROUTE_PREVIEW_MAX_WAYPOINTS
        MapScreenState.Navigating -> true

        is MapScreenState.Arrived,
        MapScreenState.Browsing,
        is MapScreenState.PlaceDetails,
        is MapScreenState.SearchResultsList,
        -> false
    }
}
