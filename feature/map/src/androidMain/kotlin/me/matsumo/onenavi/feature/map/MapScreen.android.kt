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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationEventHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.google.android.gms.maps.GoogleMap
import me.matsumo.onenavi.feature.map.components.MapBrowsingContent
import me.matsumo.onenavi.feature.map.components.MapControls
import me.matsumo.onenavi.feature.map.components.MapMarker
import me.matsumo.onenavi.feature.map.components.bottomsheet.MapPlaceDetailSheet
import me.matsumo.onenavi.feature.map.state.MapCameraState
import me.matsumo.onenavi.feature.map.state.MapScreenState
import me.matsumo.onenavi.feature.map.state.MapUiEvent
import me.matsumo.onenavi.feature.map.state.MapUiState
import me.matsumo.onenavi.feature.map.state.rememberMapCameraState
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
actual fun MapScreen(modifier: Modifier) {
    val viewModel = koinViewModel<MapViewModel>()

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val screenState by viewModel.currentScreenState.collectAsStateWithLifecycle()
    val hasScreenStateStack by viewModel.hasScreenStateStack.collectAsStateWithLifecycle()

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

    val controlsBottomPadding by animateDpAsState(
        targetValue = if (shouldShowSheet) uiState.bottomSheetPeekHeight else navigationBarHeightDp,
        label = "ControlsBottomPadding",
    )

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

    NavigationEventHandler(navigationState, isBackEnabled = hasScreenStateStack) {
        viewModel.popScreenState()
    }

    LaunchedEffect(shouldShowSheet) {
        if (shouldShowSheet) {
            allowSheetHide = false
            scaffoldState.bottomSheetState.partialExpand()
        } else {
            allowSheetHide = true
            scaffoldState.bottomSheetState.hide()
        }
    }

    MapEffect(
        screenState = screenState,
        googleMap = googleMap,
    )

    MapCameraEffect(
        screenState = screenState,
        cameraState = cameraState
    )

    BottomSheetScaffold(
        modifier = modifier,
        scaffoldState = scaffoldState,
        sheetPeekHeight = uiState.bottomSheetPeekHeight,
        sheetContent = {
            MapScreenBottomSheetContent(
                modifier = Modifier.fillMaxSize(),
                uiState = uiState,
                screenState = screenState,
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
                onMapUpdate = { googleMap = it },
            )

            MapScreenContent(
                modifier = Modifier.fillMaxSize(),
                uiState = uiState,
                screenState = screenState,
                cameraState = cameraState,
                onUiEvent = viewModel::onUiEvent,
            )

            MapControls(
                modifier = Modifier
                    .padding(bottom = controlsBottomPadding)
                    .fillMaxSize(),
                cameraState = cameraState,
            )
        }
    }
}

@Composable
private fun MapScreenContent(
    uiState: MapUiState,
    screenState: MapScreenState,
    cameraState: MapCameraState,
    onUiEvent: (MapUiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (screenState) {
        is MapScreenState.Browsing,
        is MapScreenState.PlaceDetails,
        is MapScreenState.SearchResultsList -> {
            MapBrowsingContent(
                modifier = modifier,
                cameraState = cameraState,
                uiState = uiState,
                onUiEvent = onUiEvent,
            )
        }

        is MapScreenState.RoutePreview -> TODO()
        is MapScreenState.Navigating -> TODO()
        is MapScreenState.Arrived -> TODO()
    }
}

@Composable
private fun MapScreenBottomSheetContent(
    uiState: MapUiState,
    screenState: MapScreenState,
    cameraState: MapCameraState,
    onUiEvent: (MapUiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (screenState) {
        is MapScreenState.Browsing -> Unit
        is MapScreenState.PlaceDetails -> {
            MapPlaceDetailSheet(
                modifier = modifier,
                selectedResult = screenState.place,
                onUiEvent = onUiEvent,
            )
        }

        is MapScreenState.SearchResultsList -> TODO()
        is MapScreenState.RoutePreview -> TODO()
        is MapScreenState.Navigating -> TODO()
        is MapScreenState.Arrived -> TODO()
    }
}

@Composable
private fun MapCameraEffect(
    screenState: MapScreenState,
    cameraState: MapCameraState,
) {
    LaunchedEffect(screenState) {
        when (screenState) {
            is MapScreenState.Browsing -> {
                cameraState.followMyLocation()
            }

            is MapScreenState.PlaceDetails -> {
                cameraState.moveTo(
                    latitude = screenState.place.latitude,
                    longitude = screenState.place.longitude,
                    zoom = 18f,
                )
            }

            is MapScreenState.SearchResultsList -> TODO()
            is MapScreenState.RoutePreview -> TODO()
            is MapScreenState.Navigating -> TODO()
            is MapScreenState.Arrived -> TODO()
        }
    }
}

@Composable
private fun MapEffect(
    screenState: MapScreenState,
    googleMap: GoogleMap?,
) {
    when (screenState) {
        is MapScreenState.Browsing -> Unit

        is MapScreenState.PlaceDetails -> {
            if (googleMap != null) {
                MapMarker(
                    googleMap = googleMap,
                    latitude = screenState.place.latitude,
                    longitude = screenState.place.longitude,
                    title = screenState.place.name,
                )
            }
        }

        is MapScreenState.SearchResultsList -> TODO()
        is MapScreenState.RoutePreview -> TODO()
        is MapScreenState.Navigating -> TODO()
        is MapScreenState.Arrived -> TODO()
    }
}

private val SHEET_VISIBLE_STATES = listOf(
    MapScreenState.SearchResultsList::class,
    MapScreenState.PlaceDetails::class,
    MapScreenState.RoutePreview::class,
)