package me.matsumo.onenavi.feature.home.map

import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.os.ConfigurationCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mapbox.common.MapboxOptions
import com.mapbox.geojson.Point
import com.mapbox.geojson.Point.fromLngLat
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.EdgeInsets
import com.mapbox.maps.MapView
import com.mapbox.maps.MapboxExperimental
import com.mapbox.maps.extension.compose.MapEffect
import com.mapbox.maps.extension.compose.MapboxMap
import com.mapbox.maps.extension.compose.animation.viewport.rememberMapViewportState
import com.mapbox.maps.extension.compose.annotation.Marker
import com.mapbox.maps.extension.compose.annotation.ViewAnnotation
import com.mapbox.maps.extension.compose.style.standard.LightPresetValue
import com.mapbox.maps.extension.compose.style.standard.MapboxStandardStyle
import com.mapbox.maps.extension.compose.style.standard.rememberStandardStyleState
import com.mapbox.maps.extension.localization.localizeLabels
import com.mapbox.maps.plugin.PuckBearing
import com.mapbox.maps.plugin.animation.MapAnimationOptions
import com.mapbox.maps.plugin.locationcomponent.createDefault2DPuck
import com.mapbox.maps.plugin.locationcomponent.location
import com.mapbox.maps.plugin.viewport.ViewportStatus
import com.mapbox.maps.plugin.viewport.data.DefaultViewportTransitionOptions
import com.mapbox.maps.plugin.viewport.data.FollowPuckViewportStateBearing
import com.mapbox.maps.plugin.viewport.data.FollowPuckViewportStateOptions
import com.mapbox.maps.viewannotation.annotationAnchor
import com.mapbox.maps.viewannotation.geometry
import com.mapbox.maps.viewannotation.viewAnnotationOptions
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import me.matsumo.onenavi.core.model.SearchResultItem
import me.matsumo.onenavi.core.resource.Res
import me.matsumo.onenavi.core.resource.home_map_search_route
import me.matsumo.onenavi.feature.home.map.components.HomeMapControls
import me.matsumo.onenavi.feature.home.map.components.HomeMapTopAppBar
import me.matsumo.onenavi.feature.home.map.components.LocationTrackingMode
import org.jetbrains.compose.resources.stringResource
import java.util.*

private const val FOLLOW_PUCK_ZOOM = 16.0
private const val FOLLOW_PUCK_PITCH = 45.0
private const val ZOOM_STEP = 1.0
private const val TRANSITION_MAX_DURATION_MS = 1000L
private const val CAMERA_PADDING = 100.0
private const val CAMERA_PADDING_TOP = 200.0
private const val CAMERA_PADDING_BOTTOM = 400.0

@Suppress("COMPOSE_APPLIER_CALL_MISMATCH")
@OptIn(ExperimentalMaterial3Api::class, MapboxExperimental::class)
@Composable
internal actual fun HomeMapScreenContent(
    viewModel: HomeMapViewModel,
    modifier: Modifier,
) {
    val suggestions by viewModel.suggestions.collectAsStateWithLifecycle()
    val histories by viewModel.histories.collectAsStateWithLifecycle()
    val searchResults by viewModel.searchResults.collectAsStateWithLifecycle()
    val selectedResult by viewModel.selectedResult.collectAsStateWithLifecycle()

    var mapView by remember { mutableStateOf<MapView?>(null) }
    var showSearchResult by rememberSaveable { mutableStateOf(false) }
    var showSearchResultsSheet by rememberSaveable { mutableStateOf(false) }
    var trackingMode by remember { mutableStateOf<LocationTrackingMode?>(LocationTrackingMode.TiltedHeading) }
    var lastTrackingMode by remember { mutableStateOf(LocationTrackingMode.TiltedHeading) }
    val scope = rememberCoroutineScope()

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

    LaunchedEffect(searchResults, mapView) {
        if (searchResults.isEmpty()) return@LaunchedEffect
        val currentMapView = mapView ?: return@LaunchedEffect
        trackingMode = null
        showSearchResultsSheet = true

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
            MapEffect(locale) { view ->
                mapView = view
                view.location.enabled = true
                view.location.locationPuck = createDefault2DPuck(withBearing = true)
                view.location.puckBearing = PuckBearing.HEADING
                view.location.puckBearingEnabled = true
                view.mapboxMap.style?.localizeLabels(Locale.JAPANESE)
            }

            if (searchResults.isNotEmpty()) {
                searchResults.forEachIndexed { index, result ->
                    HomeMapNumberedPin(
                        point = fromLngLat(result.longitude, result.latitude),
                        number = index + 1,
                    )
                }
            } else {
                selectedResult?.let { result ->
                    Marker(
                        point = fromLngLat(result.longitude, result.latitude),
                    )
                }
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
            onSearchSubmitted = { query ->
                val center = viewportState.cameraState?.center
                viewModel.onSearch(
                    query = query,
                    latitude = center?.latitude(),
                    longitude = center?.longitude(),
                )
                showSearchResult = true
            },
            onSuggestionSelected = { suggestion ->
                viewModel.onSuggestionSelected(suggestion)
                showSearchResult = true
            },
            onHistorySelected = { history ->
                viewModel.onHistorySelected(history)
                showSearchResult = true
            },
            onRemoveHistory = viewModel::onRemoveHistory,
            onBackClicked = {
                showSearchResult = false
                showSearchResultsSheet = false
                viewModel.onDismissSearchResults()
            },
        )

        HomeMapControls(
            modifier = Modifier
                .align(Alignment.BottomEnd)
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
                            else -> LocationTrackingMode.TiltedHeading
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
                onRouteClicked = {}
            )
        }
    } else if (showSearchResultsSheet && searchResults.isNotEmpty()) {
        val sheetState = rememberModalBottomSheetState()

        ModalBottomSheet(
            onDismissRequest = { showSearchResultsSheet = false },
            sheetState = sheetState,
        ) {
            HomeMapSearchResultListContent(
                results = searchResults,
                onResultSelected = viewModel::onSearchResultSelected,
            )
        }
    }
}

@OptIn(MapboxExperimental::class)
@Composable
private fun HomeMapNumberedPin(
    point: Point,
    number: Int,
    modifier: Modifier = Modifier,
) {
    ViewAnnotation(
        options = viewAnnotationOptions {
            geometry(point)
            annotationAnchor {
                anchor(com.mapbox.maps.ViewAnnotationAnchor.BOTTOM)
            }
            allowOverlap(true)
            allowOverlapWithPuck(true)
        },
    ) {
        Surface(
            modifier = modifier.size(32.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            shadowElevation = 4.dp,
        ) {
            Box(
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "$number",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}

@Composable
private fun HomeMapSearchResultListContent(
    results: ImmutableList<SearchResultItem>,
    onResultSelected: (SearchResultItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp),
    ) {
        itemsIndexed(
            items = results,
            key = { index, item -> "${item.id}_$index" },
        ) { index, result ->
            HomeMapSearchResultListItem(
                index = index + 1,
                result = result,
                onClick = { onResultSelected(result) },
            )

            if (index < results.lastIndex) {
                HorizontalDivider(
                    modifier = Modifier.padding(
                        horizontal = 24.dp,
                    ),
                )
            }
        }
    }
}

@Composable
private fun HomeMapSearchResultListItem(
    index: Int,
    result: SearchResultItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(
                horizontal = 24.dp,
                vertical = 12.dp,
            ),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(28.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
        ) {
            Box(
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "$index",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = result.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            val address = result.fullAddress
            if (address != null) {
                Text(
                    text = address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        val distance = result.distanceMeters
        if (distance != null) {
            Text(
                text = formatDistance(distance),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatDistance(meters: Double): String {
    return if (meters < 1000) {
        "${meters.toInt()}m"
    } else {
        "%.1fkm".format(meters / 1000)
    }
}

@Composable
private fun HomeMapResultSheetContent(
    result: SearchResultItem?,
    onRouteClicked: () -> Unit,
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

        val address = result.fullAddress
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

        Button(onRouteClicked) {
            Text(
                text = stringResource(Res.string.home_map_search_route),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
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
