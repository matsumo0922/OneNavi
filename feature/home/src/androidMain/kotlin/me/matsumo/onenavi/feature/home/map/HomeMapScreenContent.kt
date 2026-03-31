package me.matsumo.onenavi.feature.home.map

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mapbox.common.MapboxOptions
import com.mapbox.geojson.Point.fromLngLat
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.EdgeInsets
import com.mapbox.maps.MapView
import com.mapbox.maps.MapboxExperimental
import com.mapbox.maps.extension.compose.MapEffect
import com.mapbox.maps.extension.compose.MapboxMap
import com.mapbox.maps.extension.compose.animation.viewport.rememberMapViewportState
import com.mapbox.maps.extension.compose.annotation.Marker
import com.mapbox.maps.extension.compose.style.standard.LightPresetValue
import com.mapbox.maps.extension.compose.style.standard.MapboxStandardStyle
import com.mapbox.maps.extension.compose.style.standard.rememberStandardStyleState
import com.mapbox.maps.extension.localization.localizeLabels
import com.mapbox.maps.plugin.PuckBearing
import com.mapbox.maps.plugin.animation.MapAnimationOptions
import com.mapbox.maps.plugin.locationcomponent.createDefault2DPuck
import com.mapbox.maps.plugin.locationcomponent.location
import com.mapbox.maps.plugin.viewport.ViewportStatus
import kotlinx.coroutines.flow.distinctUntilChanged
import me.matsumo.onenavi.feature.home.map.components.HomeMapControls
import me.matsumo.onenavi.feature.home.map.components.HomeMapNumberedPin
import me.matsumo.onenavi.feature.home.map.components.HomeMapSelectedResultSheet
import me.matsumo.onenavi.feature.home.map.components.HomeMapTopAppBar
import me.matsumo.onenavi.feature.home.map.components.LocationTrackingMode
import java.util.*

private const val FOLLOW_PUCK_ZOOM = 16.0
private const val CAMERA_PADDING = 100.0
private const val CAMERA_PADDING_TOP = 200.0
private const val CAMERA_PADDING_BOTTOM = 400.0
private val SHEET_PEEK_HEIGHT_DEFAULT = 200.dp
private val SHEET_DRAG_HANDLE_HEIGHT = 48.dp

@Suppress("COMPOSE_APPLIER_CALL_MISMATCH", "ParamsComparedByRef")
@OptIn(ExperimentalMaterial3Api::class, MapboxExperimental::class)
@Composable
internal actual fun HomeMapScreenContent(
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

    var mapView by remember { mutableStateOf<MapView?>(null) }
    var showSearchResultsSheet by rememberSaveable { mutableStateOf(false) }
    var trackingMode by remember { mutableStateOf<LocationTrackingMode?>(LocationTrackingMode.TiltedHeading) }

    val isDarkTheme = isSystemInDarkTheme()
    val viewportState = rememberMapViewportState()
    val standardStyleState = rememberStandardStyleState {
        configurationsState.lightPreset = if (isDarkTheme) LightPresetValue.NIGHT else LightPresetValue.DAY
    }

    val scaffoldState = rememberBottomSheetScaffoldState(
        bottomSheetState = rememberStandardBottomSheetState(
            initialValue = SheetValue.Hidden,
            skipHiddenState = false,
            confirmValueChange = { it != SheetValue.Hidden },
        ),
    )

    var contentHeight by remember { mutableFloatStateOf(0f) }
    var sheetPeekHeight by remember { mutableStateOf(SHEET_PEEK_HEIGHT_DEFAULT) }

    val sheetVisibleHeight by remember {
        derivedStateOf {
            val offset = runCatching {
                scaffoldState.bottomSheetState.requireOffset()
            }.getOrDefault(contentHeight)
            with(density) { (contentHeight - offset).coerceAtLeast(0f).toDp() }
        }
    }

    LaunchedEffect(viewModel.mapBoxToken) {
        MapboxOptions.accessToken = viewModel.mapBoxToken
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
        if (searchResults.isEmpty()) return@LaunchedEffect
        val currentMapView = mapView ?: return@LaunchedEffect

        trackingMode = null
        showSearchResultsSheet = true

        val points = searchResults.map { fromLngLat(it.effectiveLongitude, it.effectiveLatitude) }
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
            scaffoldState.bottomSheetState.hide()
            return@LaunchedEffect
        }

        trackingMode = null
        scaffoldState.bottomSheetState.partialExpand()

        viewportState.easeTo(
            cameraOptions = CameraOptions.Builder()
                .center(fromLngLat(result.effectiveLongitude, result.effectiveLatitude))
                .zoom(FOLLOW_PUCK_ZOOM)
                .pitch(0.0)
                .bearing(0.0)
                .build(),
            animationOptions = MapAnimationOptions.Builder()
                .duration(1500)
                .build(),
        )
    }

    BottomSheetScaffold(
        modifier = modifier,
        scaffoldState = scaffoldState,
        sheetPeekHeight = sheetPeekHeight,
        sheetContent = {
            if (searchResults.isNotEmpty()) {
                searchResults.forEachIndexed { index, result ->
                    // TODO
                }
            } else {
                selectedResult?.let { result ->
                    HomeMapSelectedResultSheet(
                        selectedResult = result,
                        onPeekHeightMeasured = { heightPx ->
                            val measuredHeight = with(density) { heightPx.toDp() } + SHEET_DRAG_HANDLE_HEIGHT
                            sheetPeekHeight = measuredHeight
                        },
                    )
                }
            }
        },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { contentHeight = it.size.height.toFloat() },
        ) {
            MapboxMap(
                modifier = Modifier.fillMaxSize(),
                mapViewportState = viewportState,
                scaleBar = {},
                logo = {
                    Logo(
                        modifier = Modifier.padding(
                            bottom = sheetVisibleHeight,
                        ),
                    )
                },
                attribution = {
                    Attribution(
                        modifier = Modifier.padding(
                            bottom = sheetVisibleHeight,
                        ),
                    )
                },
                style = {
                    MapboxStandardStyle(
                        standardStyleState = standardStyleState,
                    )
                },
            ) {
                MapEffect { view ->
                    mapView = view
                    view.location.enabled = true
                    view.location.locationPuck = createDefault2DPuck(withBearing = true)
                    view.location.puckBearing = PuckBearing.HEADING
                    view.location.puckBearingEnabled = true
                    view.mapboxMap.style?.localizeLabels(Locale.JAPANESE)
                    view.location.addOnIndicatorPositionChangedListener { point ->
                        viewModel.onUserLocationUpdated(
                            latitude = point.latitude(),
                            longitude = point.longitude(),
                        )
                    }
                }

                if (searchResults.isNotEmpty()) {
                    searchResults.forEachIndexed { index, result ->
                        HomeMapNumberedPin(
                            point = fromLngLat(result.effectiveLongitude, result.effectiveLatitude),
                            number = index + 1,
                        )
                    }
                } else {
                    selectedResult?.let { result ->
                        Marker(
                            point = fromLngLat(result.effectiveLongitude, result.effectiveLatitude),
                            color = Color.Red,
                            innerColor = Color.White,
                            stroke = Color.White,
                        )
                    }
                }
            }

            HomeMapTopAppBar(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .fillMaxWidth(),
                suggestions = suggestions,
                histories = histories,
                viewportState = viewportState,
                onViewEvent = viewModel::onViewEvent,
            )

            HomeMapControls(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(
                        bottom = sheetVisibleHeight + 16.dp,
                        end = 16.dp,
                    ),
                trackingMode = trackingMode,
                viewportState = viewportState,
                onTrackingModeChanged = { trackingMode = it },
            )
        }
    }
}
