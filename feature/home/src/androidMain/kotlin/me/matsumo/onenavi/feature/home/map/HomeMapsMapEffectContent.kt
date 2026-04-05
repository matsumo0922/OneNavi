package me.matsumo.onenavi.feature.home.map

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mapbox.geojson.Point
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
import me.matsumo.onenavi.core.navigation.CameraManager
import me.matsumo.onenavi.core.navigation.RouteManager
import me.matsumo.onenavi.feature.home.map.components.HomeMapNumberedPin
import me.matsumo.onenavi.feature.home.map.components.HomeMapRouteCallout
import me.matsumo.onenavi.feature.home.map.components.HomeMapWaypointPin
import me.matsumo.onenavi.feature.home.map.components.RouteCalloutStyle
import me.matsumo.onenavi.feature.home.map.state.HomeMapScreenState
import me.matsumo.onenavi.feature.home.map.util.CalloutSize
import me.matsumo.onenavi.feature.home.map.util.MapCalloutPositioner

private const val ROUTE_CLICK_PADDING = 30f

private val ROUTE_CALLOUT_SIZE = CalloutSize(
    widthPx = 180.0,
    heightPx = 80.0,
)

@Suppress("COMPOSE_APPLIER_CALL_MISMATCH")
@OptIn(MapboxExperimental::class, ExperimentalPreviewMapboxNavigationAPI::class)
@Composable
internal fun HomeMapsMapEffectContent(
    viewportState: MapViewportState,
    standardStyleState: StandardStyleState,
    sheetVisibleHeight: Dp,
    screenState: HomeMapScreenState,
    routeResults: ImmutableList<RouteResult>,
    selectedRouteIndex: Int,
    routeManager: RouteManager,
    cameraManager: CameraManager,
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

    val navigationRoutes by routeManager.routes.collectAsStateWithLifecycle()

    var calloutPoints by remember { mutableStateOf<List<Point?>>(emptyList()) }

    val primaryStyle = remember { RouteCalloutStyle.forRoute(isPrimary = true) }
    val secondaryStyle = remember { RouteCalloutStyle.forRoute(isPrimary = false) }

    val routeLineApi = remember {
        MapboxRouteLineApi(
            MapboxRouteLineApiOptions.Builder()
                .build(),
        )
    }

    val routeLineView = remember {
        MapboxRouteLineView(
            MapboxRouteLineViewOptions.Builder(context)
                .displaySoftGradientForTraffic(true)
                .build(),
        )
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

            cameraManager.setupCamera(view)

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

            // カメラ変更のたびに吹き出し位置をリアルタイム再計算
            val cameraChangeCancelable = view.mapboxMap.subscribeCameraChanged {
                calloutPoints = MapCalloutPositioner.computePositions(
                    mapboxMap = view.mapboxMap,
                    geometries = currentRouteResults.value.map { it.item.geometry },
                    calloutSize = ROUTE_CALLOUT_SIZE,
                )
            }

            onDispose {
                view.location.removeOnIndicatorPositionChangedListener(positionListener)
                view.location.removeOnIndicatorBearingChangedListener(bearingListener)
                view.mapboxMap.removeOnMapClickListener(mapClickListener)
                cameraChangeCancelable.cancel()
                cameraManager.teardownCamera()
            }
        }

        // ルートライン描画: routeManager.routes（SDK の RoutesObserver 経由）を source of truth とする
        MapEffect(navigationRoutes, selectedRouteIndex) { mapView ->
            val style = mapView.mapboxMap.style ?: return@MapEffect

            if (navigationRoutes.isEmpty()) {
                routeLineApi.clearRouteLine { expected ->
                    routeLineView.renderClearRouteLineValue(style, expected)
                }
                calloutPoints = emptyList()
                return@MapEffect
            }

            routeLineApi.setNavigationRoutes(routeManager.reorderedRoutes()) { expected ->
                routeLineView.renderRouteDrawData(style, expected)
            }

            calloutPoints = MapCalloutPositioner.computePositions(
                mapboxMap = mapView.mapboxMap,
                geometries = routeResults.map { it.item.geometry },
                calloutSize = ROUTE_CALLOUT_SIZE,
            )
        }

        // ルート吹き出し: ナビ中は非表示、プレビュー時のみ表示
        if (screenState is HomeMapScreenState.RoutePreview) {
            routeResults.forEachIndexed { index, result ->
                if (index != selectedRouteIndex) {
                    calloutPoints.getOrNull(index)?.let { point ->
                        HomeMapRouteCallout(
                            point = point,
                            routeResult = result,
                            isPrimary = false,
                            style = secondaryStyle,
                            onClick = { onRouteSelected(index) },
                        )
                    }
                }
            }

            calloutPoints.getOrNull(selectedRouteIndex)?.let { point ->
                HomeMapRouteCallout(
                    point = point,
                    routeResult = routeResults[selectedRouteIndex],
                    isPrimary = true,
                    style = primaryStyle,
                    onClick = { },
                )
            }
        }

        // マーカー表示: screenState ベース
        when (val state = screenState) {
            is HomeMapScreenState.Browsing -> { /* マーカーなし */ }
            is HomeMapScreenState.SearchResultsList -> {
                state.results.forEachIndexed { index, result ->
                    HomeMapNumberedPin(
                        point = Point.fromLngLat(result.longitude, result.latitude),
                        number = index + 1,
                    )
                }
            }
            is HomeMapScreenState.PlaceDetails -> {
                Marker(
                    point = Point.fromLngLat(state.place.longitude, state.place.latitude),
                    color = Color.Red,
                    innerColor = Color.White,
                    stroke = Color.White,
                )
            }
            is HomeMapScreenState.RoutePreview -> {
                state.waypoints.lastOrNull()?.let { waypoint ->
                    Marker(
                        point = Point.fromLngLat(waypoint.longitude, waypoint.latitude),
                        color = Color.Red,
                        innerColor = Color.White,
                        stroke = Color.White,
                    )
                }
                if (state.waypoints.size > 2) {
                    state.waypoints.drop(1).dropLast(1).forEachIndexed { index, waypoint ->
                        val point = when (waypoint) {
                            is RouteWaypoint.CurrentLocation -> Point.fromLngLat(waypoint.longitude, waypoint.latitude)
                            is RouteWaypoint.Place -> Point.fromLngLat(waypoint.longitude, waypoint.latitude)
                        }
                        HomeMapWaypointPin(
                            point = point,
                            label = "K${index + 1}",
                        )
                    }
                }
            }
            is HomeMapScreenState.Navigating,
            is HomeMapScreenState.Arrived,
            -> { /* ナビ中はマーカー非表示 */ }
        }
    }
}
