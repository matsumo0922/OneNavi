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
import com.mapbox.geojson.Point
import com.mapbox.maps.extension.compose.MapboxMap
import com.mapbox.maps.extension.compose.animation.viewport.rememberMapViewportState
import me.matsumo.onenavi.feature.home.map.components.HomeMapTopAppBar

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

    val viewportState = rememberMapViewportState {
        setCameraOptions {
            center(Point.fromLngLat(139.6917, 35.6895))
            zoom(12.0)
        }
    }

    Box(modifier) {
        MapboxMap(
            modifier = Modifier.fillMaxSize(),
            mapViewportState = viewportState,
        )

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
