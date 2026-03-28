package me.matsumo.onenavi.feature.home.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import com.mapbox.common.MapboxOptions
import com.mapbox.geojson.Point
import com.mapbox.maps.extension.compose.MapboxMap
import com.mapbox.maps.extension.compose.animation.viewport.rememberMapViewportState

@Composable
internal actual fun HomeMapScreen(
    mapBoxToken: String,
    modifier: Modifier,
) {
    LaunchedEffect(mapBoxToken) {
        MapboxOptions.accessToken = mapBoxToken
    }

    val viewportState = rememberMapViewportState {
        setCameraOptions {
            center(Point.fromLngLat(139.6917, 35.6895))
            zoom(12.0)
        }
    }

    MapboxMap(
        modifier = modifier,
        mapViewportState = viewportState,
    )
}
