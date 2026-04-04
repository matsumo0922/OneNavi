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
import com.mapbox.geojson.Point
import com.mapbox.geojson.Point.fromLngLat
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.MapView
import com.mapbox.maps.MapboxExperimental
import com.mapbox.maps.MapboxMap
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
import me.matsumo.onenavi.feature.home.map.components.HomeMapRouteCallout
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

    // 吹き出しの動的配置位置。カメラ移動やルート変更のたびに再計算される。
    var calloutPoints by remember { mutableStateOf<List<Point?>>(emptyList()) }

    val routeLineApi = remember {
        MapboxRouteLineApi(
            MapboxRouteLineApiOptions.Builder()
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

            // カメラ変更のたびに吹き出し位置をリアルタイム再計算
            val cameraChangeCancelable = view.mapboxMap.subscribeCameraChanged {
                calloutPoints = computeCalloutPoints(
                    mapboxMap = view.mapboxMap,
                    routeResults = currentRouteResults.value,
                )
            }

            onDispose {
                view.location.removeOnIndicatorPositionChangedListener(positionListener)
                view.location.removeOnIndicatorBearingChangedListener(bearingListener)
                view.mapboxMap.removeOnMapClickListener(mapClickListener)
                cameraChangeCancelable.cancel()
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
                calloutPoints = emptyList()
                return@MapEffect
            }

            val primaryIndex = navigationManager.selectedRouteIndex.value
            val reorderedRoutes = if (primaryIndex in navigationRoutes.indices) {
                listOf(navigationRoutes[primaryIndex]) + navigationRoutes.filterIndexed { index, _ -> index != primaryIndex }
            } else {
                navigationRoutes
            }

            routeLineApi.setNavigationRoutes(reorderedRoutes) { expected ->
                routeLineView.renderRouteDrawData(style, expected)
            }

            // ルート変更時も吹き出し位置を計算
            calloutPoints = computeCalloutPoints(
                mapboxMap = mapView.mapboxMap,
                routeResults = routeResults.toList(),
            )
        }

        // ルート吹き出し: ビューポート内の可視中間地点に ViewAnnotation で配置
        // primary を最後に描画して z-order 最上位に配置する
        if (routeResults.isNotEmpty()) {
            // 非プライマリを先に描画
            routeResults.forEachIndexed { index, result ->
                if (index != selectedRouteIndex) {
                    calloutPoints.getOrNull(index)?.let { point ->
                        HomeMapRouteCallout(
                            point = point,
                            routeResult = result,
                            isPrimary = false,
                            onClick = { onRouteSelected(index) },
                        )
                    }
                }
            }
            // プライマリを最後に描画（最上位）
            calloutPoints.getOrNull(selectedRouteIndex)?.let { point ->
                HomeMapRouteCallout(
                    point = point,
                    routeResult = routeResults[selectedRouteIndex],
                    isPrimary = true,
                    onClick = { },
                )
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

/** 吹き出し本体の推定サイズ（px）。実測値に基づく近似。 */
private const val CALLOUT_WIDTH_PX = 180.0
private const val CALLOUT_HEIGHT_PX = 80.0

/** 矩形の重なり判定に加えるマージン（px）。 */
private const val CALLOUT_MARGIN_PX = 16.0

/**
 * 重なり回避で試行する、可視区間上のオフセット候補。
 * 0.5（中央）から始め、前後に広げて空きスペースを探す。
 */
private val OFFSET_CANDIDATES = doubleArrayOf(0.15, 0.85, 0.05, 0.95)

/**
 * アンカー位置から吹き出し本体の矩形（left, top, right, bottom）を推定する。
 *
 * アンカーの命名は「吹き出しのどこがポイントに接するか」を示す:
 * - BOTTOM_LEFT: 吹き出しの左下が座標点 → 本体は右上に展開
 * - BOTTOM_RIGHT: 吹き出しの右下が座標点 → 本体は左上に展開
 * - TOP_LEFT: 吹き出しの左上が座標点 → 本体は右下に展開
 * - TOP_RIGHT: 吹き出しの右上が座標点 → 本体は左下に展開
 */
private fun estimateCalloutRect(
    screenX: Double,
    screenY: Double,
): DoubleArray {
    // 吹き出しは annotationAnchors で 4 方向のフォールバックが設定されている。
    // 実際にどのアンカーが選ばれるかは描画時に決まるため、ここでは
    // 座標を中心とした上下左右に余裕を持った矩形で近似する。
    val halfW = CALLOUT_WIDTH_PX / 2.0 + CALLOUT_MARGIN_PX
    val halfH = CALLOUT_HEIGHT_PX / 2.0 + CALLOUT_MARGIN_PX
    return doubleArrayOf(
        screenX - halfW,
        screenY - CALLOUT_HEIGHT_PX - CALLOUT_MARGIN_PX,
        screenX + halfW,
        screenY + CALLOUT_MARGIN_PX,
    )
}

/**
 * 2 つの矩形 [a], [b] (left, top, right, bottom) が重なるかを返す。
 */
private fun rectsOverlap(a: DoubleArray, b: DoubleArray): Boolean {
    return a[0] < b[2] && a[2] > b[0] && a[1] < b[3] && a[3] > b[1]
}

/**
 * 各ルートの吹き出し配置位置を、現在のビューポートに基づいて計算する。
 *
 * 1. ビューポート内に見えているルート座標を抽出
 * 2. 可視区間の中間地点を候補にする
 * 3. 吹き出し本体の推定矩形同士で重なりを検査し、重なる場合はオフセットをずらす
 * 4. ルートが完全にビューポート外の場合は null を返す
 */
private fun computeCalloutPoints(
    mapboxMap: MapboxMap,
    routeResults: List<RouteResult>,
): List<Point?> {
    if (routeResults.isEmpty()) return emptyList()

    val cameraState = mapboxMap.cameraState
    val bounds = mapboxMap.coordinateBoundsForCamera(
        CameraOptions.Builder()
            .center(cameraState.center)
            .zoom(cameraState.zoom)
            .bearing(cameraState.bearing)
            .pitch(cameraState.pitch)
            .padding(cameraState.padding)
            .build(),
    )

    val south = bounds.southwest.latitude()
    val north = bounds.northeast.latitude()
    val west = bounds.southwest.longitude()
    val east = bounds.northeast.longitude()

    // 各ルートの可視座標列を事前計算
    val visibleSegments = routeResults.map { result ->
        result.item.geometry.filter { point ->
            point.latitude in south..north && point.longitude in west..east
        }
    }

    val placedRects = mutableListOf<DoubleArray>()
    val resultPoints = mutableListOf<Point?>()

    for (visiblePoints in visibleSegments) {
        if (visiblePoints.isEmpty()) {
            resultPoints.add(null)
            continue
        }

        var bestPoint: Point? = null
        var bestRect: DoubleArray? = null

        for (offset in OFFSET_CANDIDATES) {
            val index = (visiblePoints.size * offset).toInt()
                .coerceIn(0, visiblePoints.lastIndex)
            val candidate = fromLngLat(
                visiblePoints[index].longitude,
                visiblePoints[index].latitude,
            )
            val screenPos = mapboxMap.pixelForCoordinate(candidate)
            val candidateRect = estimateCalloutRect(screenPos.x, screenPos.y)

            val overlaps = placedRects.any { placed ->
                rectsOverlap(candidateRect, placed)
            }

            if (!overlaps) {
                bestPoint = candidate
                bestRect = candidateRect
                break
            }
        }

        // 全候補が重なる場合はフォールバックとして中央を使用
        if (bestPoint == null) {
            val mid = visiblePoints[visiblePoints.size / 2]
            bestPoint = fromLngLat(mid.longitude, mid.latitude)
            val screenPos = mapboxMap.pixelForCoordinate(bestPoint)
            bestRect = estimateCalloutRect(screenPos.x, screenPos.y)
        }

        resultPoints.add(bestPoint)
        placedRects.add(bestRect!!)
    }

    return resultPoints
}
