package me.matsumo.onenavi.feature.home.map

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
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
import me.matsumo.onenavi.feature.home.map.components.HomeMapControls
import me.matsumo.onenavi.feature.home.map.components.HomeMapTopAppBar
import me.matsumo.onenavi.feature.home.map.components.LocationTrackingMode
import java.util.*

private const val FOLLOW_PUCK_ZOOM = 16.0
private const val FOLLOW_PUCK_PITCH = 45.0
private const val ZOOM_STEP = 1.0
private const val TRANSITION_MAX_DURATION_MS = 1000L

@Composable
internal actual fun HomeMapScreen(
    mapBoxToken: String,
    modifier: Modifier,
) {
    var showSearchResult by rememberSaveable { mutableStateOf(false) }
    var trackingMode by remember { mutableStateOf<LocationTrackingMode?>(LocationTrackingMode.TiltedHeading) }
    val scope = rememberCoroutineScope()

    @Suppress("DEPRECATION")
    BackHandler(showSearchResult) {
        showSearchResult = false
    }

    LaunchedEffect(mapBoxToken) {
        MapboxOptions.accessToken = mapBoxToken
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
                mapView.mapboxMap.style?.localizeLabels(locale)
            }
        }

        HomeMapTopAppBar(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .fillMaxWidth(),
            showSearchResult = showSearchResult,
            onSearchClicked = { showSearchResult = true },
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
                    val nextMode = when (trackingMode) {
                        null -> LocationTrackingMode.TiltedHeading
                        LocationTrackingMode.TiltedHeading -> LocationTrackingMode.TopDownHeading
                        LocationTrackingMode.TopDownHeading -> LocationTrackingMode.TopDownNorth
                        LocationTrackingMode.TopDownNorth -> LocationTrackingMode.TiltedHeading
                    }
                    trackingMode = nextMode
                    viewportState.transitionToFollowPuckState(
                        followPuckViewportStateOptions = buildFollowPuckOptions(nextMode),
                        defaultTransitionOptions = transitionOptions,
                    )
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
}

private fun buildFollowPuckOptions(mode: LocationTrackingMode): FollowPuckViewportStateOptions {
    return when (mode) {
        LocationTrackingMode.TiltedHeading -> FollowPuckViewportStateOptions.Builder()
            .zoom(FOLLOW_PUCK_ZOOM)
            .pitch(FOLLOW_PUCK_PITCH)
            .bearing(FollowPuckViewportStateBearing.SyncWithLocationPuck)
            .build()

        LocationTrackingMode.TopDownHeading -> FollowPuckViewportStateOptions.Builder()
            .zoom(FOLLOW_PUCK_ZOOM)
            .pitch(0.0)
            .bearing(FollowPuckViewportStateBearing.SyncWithLocationPuck)
            .build()

        LocationTrackingMode.TopDownNorth -> FollowPuckViewportStateOptions.Builder()
            .zoom(FOLLOW_PUCK_ZOOM)
            .pitch(0.0)
            .bearing(FollowPuckViewportStateBearing.Constant(0.0))
            .build()
    }
}
