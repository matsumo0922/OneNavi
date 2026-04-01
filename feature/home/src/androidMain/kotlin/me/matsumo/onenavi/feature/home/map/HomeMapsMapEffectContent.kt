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
import com.mapbox.geojson.Point
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
import com.mapbox.navigation.base.route.NavigationRoute
import com.mapbox.navigation.ui.maps.route.line.api.MapboxRouteLineApi
import com.mapbox.navigation.ui.maps.route.line.api.MapboxRouteLineView
import com.mapbox.navigation.ui.maps.route.line.model.MapboxRouteLineApiOptions
import com.mapbox.navigation.ui.maps.route.line.model.MapboxRouteLineViewOptions
import com.mapbox.navigation.ui.maps.route.line.model.RouteLineColorResources
import kotlinx.collections.immutable.ImmutableList
import me.matsumo.onenavi.core.model.RoutePoint
import me.matsumo.onenavi.core.model.RouteResult
import me.matsumo.onenavi.core.model.SearchResultItem
import me.matsumo.onenavi.feature.home.map.components.HomeMapNumberedPin
import me.matsumo.onenavi.feature.home.map.components.HomeMapRouteBubble
import java.util.*
import android.graphics.Color as AndroidColor

/**
 * 各ルートの吹き出しを配置するポリライン上の割合。
 * ルートごとに異なる割合を使い、吹き出し同士の重なりを軽減する。
 */
private val BUBBLE_FRACTIONS = listOf(0.5, 0.25, 0.75)

@Suppress("COMPOSE_APPLIER_CALL_MISMATCH")
@OptIn(MapboxExperimental::class)
@Composable
internal fun HomeMapsMapEffectContent(
    viewportState: MapViewportState,
    standardStyleState: StandardStyleState,
    sheetVisibleHeight: Dp,
    searchResults: ImmutableList<SearchResultItem>,
    selectedResult: SearchResultItem?,
    routeResults: ImmutableList<RouteResult>,
    selectedRouteIndex: Int,
    modifier: Modifier = Modifier,
    onMapViewChanged: (MapView) -> Unit,
    onUserLocationUpdated: (latitude: Double, longitude: Double) -> Unit,
    onBearingChanged: (Double) -> Unit,
) {
    val context = LocalContext.current

    val routeLineApi = remember {
        MapboxRouteLineApi(
            MapboxRouteLineApiOptions.Builder().build(),
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
        }

        MapEffect(routeResults, selectedRouteIndex) { mapView ->
            val style = mapView.mapboxMap.style ?: return@MapEffect

            if (routeResults.isEmpty()) {
                routeLineApi.clearRouteLine { expected ->
                    routeLineView.renderClearRouteLineValue(style, expected)
                }
                return@MapEffect
            }

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

        if (routeResults.isNotEmpty()) {
            HomeMapsRouteBubbles(
                routeResults = routeResults,
                selectedRouteIndex = selectedRouteIndex,
            )
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

@OptIn(MapboxExperimental::class)
@Composable
private fun HomeMapsRouteBubbles(
    routeResults: ImmutableList<RouteResult>,
    selectedRouteIndex: Int,
    modifier: Modifier = Modifier,
) {
    routeResults.forEachIndexed { index, result ->
        val fraction = BUBBLE_FRACTIONS.getOrElse(index) { 0.5 }
        val bubblePoint = remember(result) {
            pointAlongRoute(result.item.geometry, fraction)
        } ?: return@forEachIndexed

        val item = result.item
        val durationMinutes = (item.durationSeconds / 60).toInt()
        val durationText = "${durationMinutes} 分"

        val tollLabel = when {
            item.tollFee != null -> "¥${item.tollFee}"
            item.hasTolls -> "有料"
            else -> "一般道"
        }

        HomeMapRouteBubble(
            modifier = modifier,
            point = bubblePoint,
            durationText = durationText,
            tollLabel = tollLabel,
            isSelected = index == selectedRouteIndex,
        )
    }
}

/**
 * ポリラインの総距離に対する [fraction] (0.0〜1.0) の位置にある座標を返す。
 * 2点間のハバーサイン距離を用いて線形補間する。
 */
private fun pointAlongRoute(
    geometry: List<RoutePoint>,
    fraction: Double,
): Point? {
    if (geometry.size < 2) return null

    val distances = mutableListOf<Double>()
    var totalDistance = 0.0

    for (index in 1 until geometry.size) {
        val distance = haversineDistance(geometry[index - 1], geometry[index])
        distances.add(distance)
        totalDistance += distance
    }

    if (totalDistance == 0.0) return null

    val targetDistance = totalDistance * fraction.coerceIn(0.0, 1.0)
    var accumulated = 0.0

    for (index in distances.indices) {
        val segmentDistance = distances[index]

        if (accumulated + segmentDistance >= targetDistance) {
            val segmentFraction = if (segmentDistance > 0.0) {
                (targetDistance - accumulated) / segmentDistance
            } else {
                0.0
            }

            val from = geometry[index]
            val to = geometry[index + 1]
            val lat = from.latitude + (to.latitude - from.latitude) * segmentFraction
            val lng = from.longitude + (to.longitude - from.longitude) * segmentFraction

            return Point.fromLngLat(lng, lat)
        }

        accumulated += segmentDistance
    }

    val last = geometry.last()
    return Point.fromLngLat(last.longitude, last.latitude)
}

private fun haversineDistance(from: RoutePoint, to: RoutePoint): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(to.latitude - from.latitude)
    val dLng = Math.toRadians(to.longitude - from.longitude)
    val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
        Math.cos(Math.toRadians(from.latitude)) * Math.cos(Math.toRadians(to.latitude)) *
        Math.sin(dLng / 2) * Math.sin(dLng / 2)
    return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
}
