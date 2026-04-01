package me.matsumo.onenavi.feature.home.map

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import com.mapbox.geojson.Point.fromLngLat
import com.mapbox.maps.MapView
import com.mapbox.maps.MapboxExperimental
import com.mapbox.maps.extension.compose.MapEffect
import com.mapbox.maps.extension.compose.MapboxMap
import com.mapbox.maps.extension.compose.animation.viewport.MapViewportState
import com.mapbox.maps.extension.compose.annotation.Marker
import com.mapbox.maps.extension.compose.style.standard.MapboxStandardStyle
import com.mapbox.maps.extension.compose.style.standard.StandardStyleState
import com.mapbox.maps.extension.localization.localizeLabels
import com.mapbox.maps.plugin.PuckBearing
import com.mapbox.maps.plugin.locationcomponent.createDefault2DPuck
import com.mapbox.maps.plugin.locationcomponent.location
import kotlinx.collections.immutable.ImmutableList
import me.matsumo.onenavi.core.model.SearchResultItem
import me.matsumo.onenavi.feature.home.map.components.HomeMapNumberedPin
import java.util.*

@Suppress("COMPOSE_APPLIER_CALL_MISMATCH")
@OptIn(MapboxExperimental::class)
@Composable
internal fun HomeMapsMapEffectContent(
    viewportState: MapViewportState,
    standardStyleState: StandardStyleState,
    sheetVisibleHeight: Dp,
    searchResults: ImmutableList<SearchResultItem>,
    selectedResult: SearchResultItem?,
    modifier: Modifier = Modifier,
    onMapViewChanged: (MapView) -> Unit,
    onUserLocationUpdated: (latitude: Double, longitude: Double) -> Unit,
    onBearingChanged: (Double) -> Unit,
) {
    MapboxMap(
        modifier = modifier.fillMaxSize(),
        mapViewportState = viewportState,
        compass = {},
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
            onMapViewChanged(view)
            view.location.enabled = true
            view.location.locationPuck = createDefault2DPuck(withBearing = true)
            view.location.puckBearing = PuckBearing.HEADING
            view.location.puckBearingEnabled = true
            view.mapboxMap.style?.localizeLabels(Locale.JAPANESE)
            view.location.addOnIndicatorPositionChangedListener { point ->
                onUserLocationUpdated(
                    point.latitude(),
                    point.longitude(),
                )
            }
            view.location.addOnIndicatorBearingChangedListener { bearing ->
                onBearingChanged(bearing)
            }
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
                    color = Color.Red,
                    innerColor = Color.White,
                    stroke = Color.White,
                )
            }
        }
    }
}
