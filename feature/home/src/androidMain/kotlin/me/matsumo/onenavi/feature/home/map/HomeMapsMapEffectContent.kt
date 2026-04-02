package me.matsumo.onenavi.feature.home.map

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import com.mapbox.navigation.base.ExperimentalPreviewMapboxNavigationAPI
import com.mapbox.navigation.base.route.NavigationRoute
import com.mapbox.navigation.ui.maps.route.line.api.MapboxRouteLineApi
import com.mapbox.navigation.ui.maps.route.line.api.MapboxRouteLineView
import com.mapbox.navigation.ui.maps.route.line.model.MapboxRouteLineApiOptions
import com.mapbox.navigation.ui.maps.route.line.model.MapboxRouteLineViewOptions
import com.mapbox.navigation.ui.maps.route.line.model.RouteLineColorResources
import kotlinx.collections.immutable.ImmutableList
import me.matsumo.onenavi.core.model.RouteResult
import me.matsumo.onenavi.core.model.RouteWaypoint
import me.matsumo.onenavi.core.model.SearchResultItem
import me.matsumo.onenavi.feature.home.map.components.HomeMapNumberedPin
import me.matsumo.onenavi.feature.home.map.components.HomeMapRouteCalloutAdapter
import me.matsumo.onenavi.feature.home.map.components.HomeMapWaypointPin
import java.util.*
import android.graphics.Color as AndroidColor

@Suppress("COMPOSE_APPLIER_CALL_MISMATCH")
@OptIn(MapboxExperimental::class, ExperimentalPreviewMapboxNavigationAPI::class)
@Composable
internal fun HomeMapsMapEffectContent(
    viewportState: MapViewportState,
    standardStyleState: StandardStyleState,
    sheetVisibleHeight: Dp,
    searchResults: ImmutableList<SearchResultItem>,
    selectedResult: SearchResultItem?,
    routeResults: ImmutableList<RouteResult>,
    selectedRouteIndex: Int,
    waypoints: ImmutableList<RouteWaypoint>,
    modifier: Modifier = Modifier,
    onMapViewChanged: (MapView) -> Unit,
    onUserLocationUpdated: (latitude: Double, longitude: Double) -> Unit,
    onBearingChanged: (Double) -> Unit,
) {
    val context = LocalContext.current

    val routeLineApi = remember {
        MapboxRouteLineApi(
            MapboxRouteLineApiOptions.Builder()
                .isRouteCalloutsEnabled(true)
                .build(),
        )
    }

    val routeLineView = remember {
        MapboxRouteLineView(
            MapboxRouteLineViewOptions.Builder(context)
                .routeLineColorResources(
                    RouteLineColorResources.Builder()
                        .routeDefaultColor(AndroidColor.parseColor("#4285F4"))
                        .routeLowCongestionColor(AndroidColor.parseColor("#4CAF50"))
                        .routeModerateCongestionColor(AndroidColor.parseColor("#FFC107"))
                        .routeHeavyCongestionColor(AndroidColor.parseColor("#F44336"))
                        .routeSevereCongestionColor(AndroidColor.parseColor("#880E4F"))
                        .routeUnknownCongestionColor(AndroidColor.parseColor("#4285F4"))
                        .alternativeRouteDefaultColor(AndroidColor.parseColor("#B0B0B0"))
                        .alternativeRouteUnknownCongestionColor(AndroidColor.parseColor("#B0B0B0"))
                        .build(),
                )
                .routeLineBelowLayerId("road-label")
                .displaySoftGradientForTraffic(true)
                .softGradientTransition(30.0)
                .build(),
        )
    }

    val routeCalloutAdapter = remember {
        HomeMapRouteCalloutAdapter(context)
    }

    DisposableEffect(Unit) {
        onDispose {
            routeLineApi.cancel()
            routeLineView.cancel()
        }
    }

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

            // Route Callout を有効化
            routeLineView.setCalloutAdapter(
                view.viewAnnotationManager,
                routeCalloutAdapter,
            )
        }

        MapEffect(routeResults, selectedRouteIndex) { mapView ->
            val style = mapView.mapboxMap.style ?: return@MapEffect

            if (routeResults.isEmpty()) {
                routeLineApi.clearRouteLine { expected ->
                    routeLineView.renderClearRouteLineValue(style, expected)
                }
                return@MapEffect
            }

            routeCalloutAdapter.updateRouteResults(routeResults)

            val navigationRoutes = routeResults.mapNotNull { it.platformRoute as? NavigationRoute }
            if (navigationRoutes.isEmpty()) return@MapEffect

            val reordered = if (selectedRouteIndex in navigationRoutes.indices) {
                val selected = navigationRoutes[selectedRouteIndex]
                val others = navigationRoutes.filterIndexed { index, _ -> index != selectedRouteIndex }
                listOf(selected) + others
            } else {
                navigationRoutes
            }

            routeLineApi.setNavigationRoutes(reordered) { expected ->
                routeLineView.renderRouteDrawData(style, expected)
            }
        }

        if (searchResults.isNotEmpty()) {
            searchResults.forEachIndexed { index, result ->
                HomeMapNumberedPin(
                    point = fromLngLat(result.longitude, result.latitude),
                    number = index + 1,
                )
            }
        } else if (waypoints.isNotEmpty()) {
            waypoints.lastOrNull()?.let { waypoint ->
                Marker(
                    point = fromLngLat(waypoint.longitude , waypoint.latitude),
                    color = Color.Red,
                    innerColor = Color.White,
                    stroke = Color.White,
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

        if (waypoints.size > 2) {
            val intermediateWaypoints = waypoints.drop(1).dropLast(1)
            intermediateWaypoints.forEachIndexed { index, waypoint ->
                val point = when (waypoint) {
                    is RouteWaypoint.CurrentLocation -> fromLngLat(waypoint.longitude, waypoint.latitude)
                    is RouteWaypoint.Place -> fromLngLat(waypoint.longitude, waypoint.latitude)
                }
                HomeMapWaypointPin(
                    point = point,
                    label = "K${index + 1}",
                )
            }
        }
    }
}
