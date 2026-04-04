package me.matsumo.onenavi.feature.home.map

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import com.mapbox.geojson.Point.fromLngLat
import com.mapbox.maps.MapView
import com.mapbox.maps.MapboxExperimental
import com.mapbox.maps.extension.compose.DisposableMapEffect
import com.mapbox.maps.extension.compose.MapEffect
import com.mapbox.maps.extension.compose.MapboxMap
import com.mapbox.maps.extension.compose.animation.viewport.MapViewportState
import com.mapbox.maps.extension.compose.annotation.Marker
import com.mapbox.maps.extension.compose.style.standard.MapboxStandardStyle
import com.mapbox.maps.extension.compose.style.standard.StandardStyleState
import com.mapbox.maps.plugin.PuckBearing
import com.mapbox.maps.plugin.gestures.addOnMapClickListener
import com.mapbox.maps.plugin.gestures.removeOnMapClickListener
import com.mapbox.maps.plugin.locationcomponent.createDefault2DPuck
import com.mapbox.maps.plugin.locationcomponent.location
import com.mapbox.navigation.base.ExperimentalPreviewMapboxNavigationAPI
import com.mapbox.navigation.ui.maps.route.line.api.MapboxRouteLineApi
import com.mapbox.navigation.ui.maps.route.line.api.MapboxRouteLineView
import com.mapbox.navigation.ui.maps.route.line.model.MapboxRouteLineApiOptions
import com.mapbox.navigation.ui.maps.route.line.model.MapboxRouteLineViewOptions
import kotlinx.collections.immutable.ImmutableList
import me.matsumo.onenavi.core.model.RouteWaypoint
import me.matsumo.onenavi.core.model.SearchResultItem
import me.matsumo.onenavi.feature.home.map.components.HomeMapNumberedPin
import me.matsumo.onenavi.feature.home.map.components.HomeMapRouteCalloutAdapter
import me.matsumo.onenavi.feature.home.map.components.HomeMapWaypointPin

private const val ROUTE_CLICK_PADDING = 30f

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
    navigationManager: HomeMapNavigationManager,
    onMapViewChanged: (MapView) -> Unit,
    onUserLocationUpdated: (latitude: Double, longitude: Double) -> Unit,
    onRouteSelected: (index: Int) -> Unit,
    modifier: Modifier = Modifier,
    onBearingChanged: (Double) -> Unit,
) {
    val context = LocalContext.current

    val currentRouteResults = rememberUpdatedState(routeResults)
    val currentSelectedRouteIndex = rememberUpdatedState(selectedRouteIndex)
    val currentOnRouteSelected = rememberUpdatedState(onRouteSelected)

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
                .routeLineBelowLayerId("road-label")
                .displaySoftGradientForTraffic(true)
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
        DisposableMapEffect(Unit) { view ->
            onMapViewChanged(view)

            // NavigationCamera + ViewportDataSource セットアップ
            navigationManager.setupCamera(view)

            // Location puck セットアップ
            view.location.enabled = true
            view.location.locationPuck = createDefault2DPuck(withBearing = true)
            view.location.puckBearing = PuckBearing.HEADING
            view.location.puckBearingEnabled = true

            val positionListener = com.mapbox.maps.plugin.locationcomponent.OnIndicatorPositionChangedListener { point ->
                onUserLocationUpdated(
                    point.latitude(),
                    point.longitude(),
                )
            }
            view.location.addOnIndicatorPositionChangedListener(positionListener)

            val bearingListener = com.mapbox.maps.plugin.locationcomponent.OnIndicatorBearingChangedListener { bearing ->
                onBearingChanged(bearing)
            }
            view.location.addOnIndicatorBearingChangedListener(bearingListener)

            // Route Callout を有効化
            routeLineView.setCalloutAdapter(
                view.viewAnnotationManager,
                routeCalloutAdapter,
            )

            // ルートラインタップで選択切り替え
            val mapClickListener = com.mapbox.maps.plugin.gestures.OnMapClickListener { point ->
                val results = currentRouteResults.value
                if (results.isEmpty()) return@OnMapClickListener false

                routeLineApi.findClosestRoute(point, view.mapboxMap, ROUTE_CLICK_PADDING) { result ->
                    result.onValue { closestRoute ->
                        val clickedRoute = closestRoute.navigationRoute
                        val index = results.indexOfFirst { it.navigationRoute === clickedRoute }
                        if (index >= 0 && index != currentSelectedRouteIndex.value) {
                            currentOnRouteSelected.value(index)
                        }
                    }
                }
                false
            }
            view.mapboxMap.addOnMapClickListener(mapClickListener)

            // 吹き出しタップで選択切り替え
            routeCalloutAdapter.setOnCalloutClickListener { clickedRoute ->
                val results = currentRouteResults.value
                val index = results.indexOfFirst { it.navigationRoute === clickedRoute }
                if (index >= 0 && index != currentSelectedRouteIndex.value) {
                    currentOnRouteSelected.value(index)
                }
            }

            onDispose {
                view.location.removeOnIndicatorPositionChangedListener(positionListener)
                view.location.removeOnIndicatorBearingChangedListener(bearingListener)
                view.mapboxMap.removeOnMapClickListener(mapClickListener)
                routeCalloutAdapter.setOnCalloutClickListener(null)
                navigationManager.teardownCamera()
            }
        }

        // RoutesObserver 駆動: NavigationManager の routes が更新されたら route line を再描画
        // routeLineApi には選択ルートを先頭にした並び順で渡す（Mapbox は先頭をプライマリとして描画する）
        MapEffect(routeResults, selectedRouteIndex) { mapView ->
            val style = mapView.mapboxMap.style ?: return@MapEffect
            val navigationRoutes = navigationManager.routes.value

            if (navigationRoutes.isEmpty()) {
                routeLineApi.clearRouteLine { expected ->
                    routeLineView.renderClearRouteLineValue(style, expected)
                }
                return@MapEffect
            }

            routeCalloutAdapter.updateRouteResults(routeResults)

            val primaryIndex = navigationManager.selectedRouteIndex.value
            val reorderedRoutes = if (primaryIndex in navigationRoutes.indices) {
                listOf(navigationRoutes[primaryIndex]) + navigationRoutes.filterIndexed { index, _ -> index != primaryIndex }
            } else {
                navigationRoutes
            }

            routeLineApi.setNavigationRoutes(reorderedRoutes) { expected ->
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
                    point = fromLngLat(waypoint.longitude, waypoint.latitude),
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
