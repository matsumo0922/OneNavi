package me.matsumo.onenavi.feature.home.map

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.mapbox.common.MapboxOptions
import com.mapbox.maps.extension.compose.MapEffect
import com.mapbox.maps.extension.compose.MapboxMap
import com.mapbox.maps.extension.compose.animation.viewport.rememberMapViewportState
import com.mapbox.maps.plugin.PuckBearing
import com.mapbox.maps.plugin.locationcomponent.createDefault2DPuck
import com.mapbox.maps.plugin.locationcomponent.location
import com.mapbox.maps.plugin.viewport.data.FollowPuckViewportStateBearing
import com.mapbox.maps.plugin.viewport.data.FollowPuckViewportStateOptions
import me.matsumo.onenavi.feature.home.map.components.HomeMapTopAppBar

private const val FOLLOW_PUCK_ZOOM = 16.0
private const val FOLLOW_PUCK_PITCH = 45.0

@Composable
internal actual fun HomeMapScreen(
    mapBoxToken: String,
    modifier: Modifier,
) {
    var showSearchResult by rememberSaveable { mutableStateOf(false) }

    @Suppress("DEPRECATION")
    BackHandler(showSearchResult) {
        showSearchResult = false
    }

    LaunchedEffect(mapBoxToken) {
        MapboxOptions.accessToken = mapBoxToken
    }

    val viewportState = rememberMapViewportState()

    LaunchedEffect(Unit) {
        viewportState.transitionToFollowPuckState(
            followPuckViewportStateOptions = FollowPuckViewportStateOptions.Builder()
                .zoom(FOLLOW_PUCK_ZOOM)
                .pitch(FOLLOW_PUCK_PITCH)
                .bearing(FollowPuckViewportStateBearing.Constant(0.0))
                .build(),
        )
    }

    Box(modifier) {
        MapboxMap(
            modifier = Modifier.fillMaxSize(),
            mapViewportState = viewportState,
            scaleBar = {},
        ) {
            MapEffect(Unit) { mapView ->
                mapView.location.enabled = true
                mapView.location.locationPuck = createDefault2DPuck(withBearing = true)
                mapView.location.puckBearing = PuckBearing.HEADING
                mapView.location.puckBearingEnabled = true
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
    }
}
