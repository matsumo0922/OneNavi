package me.matsumo.onenavi.feature.map

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BottomSheetScaffold
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationEventHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.google.android.gms.maps.GoogleMap
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.rememberHazeState
import me.matsumo.onenavi.core.navigation.newguidance.model.GuidanceState
import me.matsumo.onenavi.core.navigation.newguidance.model.RoutePreviewState
import me.matsumo.onenavi.core.ui.theme.LocalAppSetting
import me.matsumo.onenavi.core.ui.theme.shouldUseDarkTheme
import me.matsumo.onenavi.feature.map.components.MapControls
import me.matsumo.onenavi.feature.map.components.bottomsheet.MapPlaceDetailSheet
import me.matsumo.onenavi.feature.map.components.bottomsheet.MapRoutePreviewSheet
import me.matsumo.onenavi.feature.map.components.bottomsheet.MapSearchResultsSheet
import me.matsumo.onenavi.feature.map.components.content.MapBrowsingContent
import me.matsumo.onenavi.feature.map.components.content.MapNavigationContent
import me.matsumo.onenavi.feature.map.components.content.MapRoutePreviewContent
import me.matsumo.onenavi.feature.map.components.topappbar.MapWaypointSearchScreen
import me.matsumo.onenavi.feature.map.state.MapCameraState
import me.matsumo.onenavi.feature.map.state.MapOverlayState
import me.matsumo.onenavi.feature.map.state.MapScreenState
import me.matsumo.onenavi.feature.map.state.MapUiEvent
import me.matsumo.onenavi.feature.map.state.MapUiState
import me.matsumo.onenavi.feature.map.state.rememberMapCameraState
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

    var googleMap by remember { mutableStateOf<GoogleMap?>(null) }
    var allowSheetHide by remember { mutableStateOf(false) }
    val shouldShowSheet by remember(screenState) {
        derivedStateOf {
            screenState::class in SHEET_VISIBLE_STATES && uiState.bottomSheetPeekHeight > 0.dp
        }
    }

    val density = LocalDensity.current
    val appSetting = LocalAppSetting.current
    val isMapDarkMode = shouldUseDarkTheme(appSetting.theme)
    val isNavigating = screenState is MapScreenState.Navigating
    val navigationCardHeightDp = with(density) { uiState.navigationCardHeight.toDp() }

    val controlsBottomPadding by animateDpAsState(
        targetValue = when {
            isNavigating -> navigationCardHeightDp.coerceAtLeast(navigationBarHeightDp)
            shouldShowSheet -> uiState.bottomSheetPeekHeight
            else -> navigationBarHeightDp
        },
        label = "ControlsBottomPadding",
    )

    val hazeState = rememberHazeState()
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
        isBackEnabled = hasScreenStateStack && screenState !is MapScreenState.Navigating,
    ) {
        viewModel.popScreenState()
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

    if (googleMap != null) {
        MapCameraEffect(
            uiState = uiState,
            screenState = screenState,
            routePreviewState = routePreviewState,
            guidanceState = guidanceState,
            vehicleLocationState = vehicleLocationState,
            cameraState = cameraState,
        )
    }

    Box(
        modifier = modifier,
    ) {
        BottomSheetScaffold(
            modifier = Modifier.fillMaxSize(),
            scaffoldState = scaffoldState,
            sheetPeekHeight = uiState.bottomSheetPeekHeight,
            sheetContent = {
                MapScreenBottomSheetContent(
                    modifier = Modifier.fillMaxSize(),
                    screenState = screenState,
                    routePreviewState = routePreviewState,
                    cameraState = cameraState,
                    onUiEvent = viewModel::onUiEvent,
                )
            },
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                MapItem(
                    modifier = Modifier.fillMaxSize(),
                    googleMap = googleMap,
                    cameraState = cameraState,
                    hazeState = hazeState,
                    isDarkMode = isMapDarkMode,
                    onMapUpdate = { googleMap = it },
                    onPointOfInterestClicked = { pointOfInterest ->
                        viewModel.onUiEvent(
                            MapUiEvent.OnMapPointOfInterestSelected(
                                placeId = pointOfInterest.placeId.orEmpty(),
                                name = pointOfInterest.name.orEmpty(),
                                latitude = pointOfInterest.latLng.latitude,
                                longitude = pointOfInterest.latLng.longitude,
                            ),
                        )
                    },
                    onMapLongClicked = { latLng ->
                        viewModel.onUiEvent(
                            MapUiEvent.OnMapLongPressed(
                                latitude = latLng.latitude,
                                longitude = latLng.longitude,
                            ),
                        )
                    },
                )

                googleMap?.let {
                    MapEffect(
                        modifier = Modifier.fillMaxSize(),
                        screenState = screenState,
                        routePreviewState = routePreviewState,
                        guidanceState = guidanceState,
                        vehicleLocationState = vehicleLocationState,
                        googleMap = it,
                        cameraState = cameraState,
                        topAppBarHeightPx = uiState.topAppBarHeight,
                        bottomSheetPeekHeight = uiState.bottomSheetPeekHeight,
                        onRouteSelected = { index ->
                            viewModel.onUiEvent(MapUiEvent.OnRouteIndexChanged(index))
                        },
                    )
                }

                MapScreenContent(
                    modifier = Modifier.fillMaxSize(),
                    uiState = uiState,
                    screenState = screenState,
                    guidanceState = guidanceState,
                    cameraState = cameraState,
                    hazeState = hazeState,
                    onUiEvent = viewModel::onUiEvent,
                )

                MapControls(
                    modifier = Modifier
                        .padding(bottom = controlsBottomPadding)
                        .fillMaxSize(),
                    cameraState = cameraState,
                    vehicleLocationState = vehicleLocationState,
                )
            }
        }

        MapWaypointSearchScreen(
            modifier = Modifier.fillMaxSize(),
            isVisible = uiState.overlayState is MapOverlayState.WaypointSearch,
            initialQuery = (uiState.overlayState as? MapOverlayState.WaypointSearch)?.initialQuery,
            suggestions = uiState.suggestions,
            histories = uiState.histories,
            onUiEvent = viewModel::onUiEvent,
        )
    }
}

@Composable
private fun MapScreenContent(
    uiState: MapUiState,
    screenState: MapScreenState,
    guidanceState: GuidanceState,
    cameraState: MapCameraState,
    hazeState: HazeState,
    onUiEvent: (MapUiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (screenState) {
        is MapScreenState.Browsing,
        is MapScreenState.PlaceDetails,
        is MapScreenState.SearchResultsList,
        -> {
            MapBrowsingContent(
                modifier = modifier,
                cameraState = cameraState,
                uiState = uiState,
                onUiEvent = onUiEvent,
            )
        }

        is MapScreenState.RoutePreview -> {
            MapRoutePreviewContent(
                modifier = modifier,
                screenState = screenState,
                uiState = uiState,
                onUiEvent = onUiEvent,
            )
        }

        is MapScreenState.Navigating -> {
            MapNavigationContent(
                modifier = modifier,
                guidanceState = guidanceState,
                hazeState = hazeState,
                onUiEvent = onUiEvent,
            )
        }

        is MapScreenState.Arrived -> Unit
    }
}

@Composable
private fun MapScreenBottomSheetContent(
    screenState: MapScreenState,
    routePreviewState: RoutePreviewState,
    cameraState: MapCameraState,
    onUiEvent: (MapUiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (screenState) {
        is MapScreenState.Browsing -> Unit
        is MapScreenState.PlaceDetails -> {
            MapPlaceDetailSheet(
                modifier = modifier,
                cameraState = cameraState,
                selectedResult = screenState.place,
                onUiEvent = onUiEvent,
            )
        }

        is MapScreenState.SearchResultsList -> {
            MapSearchResultsSheet(
                modifier = modifier,
                query = screenState.query,
                results = screenState.results,
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

private val SHEET_VISIBLE_STATES = listOf(
    MapScreenState.SearchResultsList::class,
    MapScreenState.PlaceDetails::class,
    MapScreenState.RoutePreview::class,
)
