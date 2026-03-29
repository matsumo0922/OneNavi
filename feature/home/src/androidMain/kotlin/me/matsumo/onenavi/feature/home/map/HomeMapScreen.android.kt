package me.matsumo.onenavi.feature.home.map

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.core.os.ConfigurationCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mapbox.common.MapboxOptions
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.extension.compose.MapEffect
import com.mapbox.maps.extension.compose.MapboxMap
import com.mapbox.maps.extension.compose.animation.viewport.rememberMapViewportState
import com.mapbox.maps.extension.compose.style.standard.LightPresetValue
import com.mapbox.maps.extension.compose.style.standard.MapboxStandardStyle
import com.mapbox.maps.extension.compose.style.standard.rememberStandardStyleState
import com.mapbox.maps.extension.localization.localizeLabels
import com.mapbox.maps.plugin.PuckBearing
import com.mapbox.maps.plugin.locationcomponent.createDefault2DPuck
import com.mapbox.maps.plugin.locationcomponent.location
import com.mapbox.maps.plugin.viewport.ViewportStatus
import com.mapbox.maps.plugin.viewport.data.DefaultViewportTransitionOptions
import com.mapbox.maps.plugin.viewport.data.FollowPuckViewportStateBearing
import com.mapbox.maps.plugin.viewport.data.FollowPuckViewportStateOptions
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import me.matsumo.onenavi.core.model.SearchResultItem
import me.matsumo.onenavi.feature.home.map.components.HomeMapControls
import me.matsumo.onenavi.feature.home.map.components.HomeMapTopAppBar
import me.matsumo.onenavi.feature.home.map.components.LocationTrackingMode
import java.util.*

private const val FOLLOW_PUCK_ZOOM = 16.0
private const val FOLLOW_PUCK_PITCH = 45.0
private const val ZOOM_STEP = 1.0
private const val TRANSITION_MAX_DURATION_MS = 1000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal actual fun HomeMapScreenContent(
    viewModel: HomeMapViewModel,
    modifier: Modifier,
) {
    val suggestions by viewModel.suggestions.collectAsStateWithLifecycle()
    val histories by viewModel.histories.collectAsStateWithLifecycle()
    val selectedResult by viewModel.selectedResult.collectAsStateWithLifecycle()

    var showSearchResult by rememberSaveable { mutableStateOf(false) }
    var trackingMode by remember { mutableStateOf<LocationTrackingMode?>(LocationTrackingMode.TiltedHeading) }
    var lastTrackingMode by remember { mutableStateOf(LocationTrackingMode.TiltedHeading) }
    val scope = rememberCoroutineScope()

    @Suppress("DEPRECATION")
    BackHandler(showSearchResult) {
        showSearchResult = false
    }

    LaunchedEffect(viewModel.mapBoxToken) {
        MapboxOptions.accessToken = viewModel.mapBoxToken
    }

    val viewportState = rememberMapViewportState()

    val transitionOptions = remember {
        DefaultViewportTransitionOptions.Builder()
            .maxDurationMs(TRANSITION_MAX_DURATION_MS)
            .build()
    }

    LaunchedEffect(Unit) {
        viewportState.transitionToFollowPuckState(
            followPuckViewportStateOptions = buildFollowPuckOptions(LocationTrackingMode.TiltedHeading),
            defaultTransitionOptions = transitionOptions,
        )
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

    val isDarkTheme = isSystemInDarkTheme()
    val locale = ConfigurationCompat.getLocales(LocalConfiguration.current).get(0) ?: Locale.getDefault()

    val standardStyleState = rememberStandardStyleState {
        configurationsState.lightPreset = if (isDarkTheme) LightPresetValue.NIGHT else LightPresetValue.DAY
    }

    LaunchedEffect(isDarkTheme) {
        standardStyleState.configurationsState.lightPreset =
            if (isDarkTheme) LightPresetValue.NIGHT else LightPresetValue.DAY
    }

    Box(modifier) {
        MapboxMap(
            modifier = Modifier.fillMaxSize(),
            mapViewportState = viewportState,
            scaleBar = {},
            style = {
                MapboxStandardStyle(
                    standardStyleState = standardStyleState,
                )
            },
        ) {
            MapEffect(locale) { mapView ->
                mapView.location.enabled = true
                mapView.location.locationPuck = createDefault2DPuck(withBearing = true)
                mapView.location.puckBearing = PuckBearing.HEADING
                mapView.location.puckBearingEnabled = true
                mapView.mapboxMap.style?.localizeLabels(Locale.JAPANESE)
            }
        }

        HomeMapTopAppBar(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .fillMaxWidth(),
            showSearchResult = showSearchResult,
            suggestions = suggestions,
            histories = histories,
            onQueryChanged = viewModel::onQueryChanged,
            onSuggestionSelected = { suggestion ->
                viewModel.onSuggestionSelected(suggestion)
                showSearchResult = true
            },
            onHistorySelected = { history ->
                viewModel.onHistorySelected(history)
                showSearchResult = true
            },
            onRemoveHistory = viewModel::onRemoveHistory,
            onSearchBarExpand = { },
            onBackClicked = { showSearchResult = false },
        )

        HomeMapControls(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(16.dp),
            trackingMode = trackingMode,
            onLocationClicked = {
                scope.launch {
                    val currentZoom = viewportState.cameraState?.zoom
                    if (trackingMode == null) {
                        trackingMode = lastTrackingMode
                        viewportState.transitionToFollowPuckState(
                            followPuckViewportStateOptions = buildFollowPuckOptions(
                                mode = lastTrackingMode,
                                zoom = currentZoom,
                            ),
                            defaultTransitionOptions = transitionOptions,
                        )
                    } else {
                        val nextMode = when (trackingMode) {
                            LocationTrackingMode.TiltedHeading -> LocationTrackingMode.TopDownHeading
                            LocationTrackingMode.TopDownHeading -> LocationTrackingMode.TopDownNorth
                            LocationTrackingMode.TopDownNorth -> LocationTrackingMode.TiltedHeading
                            null -> LocationTrackingMode.TiltedHeading
                        }
                        trackingMode = nextMode
                        lastTrackingMode = nextMode
                        viewportState.transitionToFollowPuckState(
                            followPuckViewportStateOptions = buildFollowPuckOptions(
                                mode = nextMode,
                                zoom = currentZoom,
                            ),
                            defaultTransitionOptions = transitionOptions,
                        )
                    }
                }
            },
            onZoomInClicked = {
                scope.launch {
                    val currentZoom = viewportState.cameraState?.zoom ?: FOLLOW_PUCK_ZOOM
                    viewportState.easeTo(
                        CameraOptions.Builder()
                            .zoom(currentZoom + ZOOM_STEP)
                            .build(),
                    )
                }
            },
            onZoomOutClicked = {
                scope.launch {
                    val currentZoom = viewportState.cameraState?.zoom ?: FOLLOW_PUCK_ZOOM
                    viewportState.easeTo(
                        CameraOptions.Builder()
                            .zoom(currentZoom - ZOOM_STEP)
                            .build(),
                    )
                }
            },
        )
    }

    if (selectedResult != null) {
        val sheetState = rememberModalBottomSheetState()

        ModalBottomSheet(
            onDismissRequest = viewModel::onDismissResult,
            sheetState = sheetState,
        ) {
            HomeMapResultSheetContent(
                result = selectedResult,
            )
        }
    }
}

@Composable
private fun HomeMapResultSheetContent(
    result: SearchResultItem?,
    modifier: Modifier = Modifier,
) {
    if (result == null) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = 24.dp,
                vertical = 16.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = result.name,
            style = MaterialTheme.typography.titleLarge,
        )

        val address = result.address
        if (address != null) {
            Text(
                text = address,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text(
            text = "%.6f, %.6f".format(result.latitude, result.longitude),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun buildFollowPuckOptions(
    mode: LocationTrackingMode,
    zoom: Double? = null,
): FollowPuckViewportStateOptions {
    val effectiveZoom = zoom ?: FOLLOW_PUCK_ZOOM
    return when (mode) {
        LocationTrackingMode.TiltedHeading -> FollowPuckViewportStateOptions.Builder()
            .zoom(effectiveZoom)
            .pitch(FOLLOW_PUCK_PITCH)
            .bearing(FollowPuckViewportStateBearing.SyncWithLocationPuck)
            .build()

        LocationTrackingMode.TopDownHeading -> FollowPuckViewportStateOptions.Builder()
            .zoom(effectiveZoom)
            .pitch(0.0)
            .bearing(FollowPuckViewportStateBearing.SyncWithLocationPuck)
            .build()

        LocationTrackingMode.TopDownNorth -> FollowPuckViewportStateOptions.Builder()
            .zoom(effectiveZoom)
            .pitch(0.0)
            .bearing(FollowPuckViewportStateBearing.Constant(0.0))
            .build()
    }
}
