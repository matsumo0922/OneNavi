package me.matsumo.onenavi.feature.home.map

import android.Manifest
import android.animation.ValueAnimator
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.view.animation.LinearInterpolator
import androidx.appcompat.content.res.AppCompatResources
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.MapsInitializer
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions
import io.github.aakira.napier.Napier
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toPersistentList
import me.matsumo.onenavi.core.common.formatDuration
import me.matsumo.onenavi.core.common.formatYen
import me.matsumo.onenavi.core.model.CongestionSegment
import me.matsumo.onenavi.core.model.CongestionSeverity
import me.matsumo.onenavi.core.model.RoutePoint
import me.matsumo.onenavi.core.model.RouteWaypoint
import me.matsumo.onenavi.core.navigation.CameraManager
import me.matsumo.onenavi.core.resource.Res
import me.matsumo.onenavi.core.resource.common_unit_day
import me.matsumo.onenavi.core.resource.common_unit_hour
import me.matsumo.onenavi.core.resource.common_unit_minute
import me.matsumo.onenavi.core.resource.home_map_route_result_general_road
import me.matsumo.onenavi.core.resource.home_map_route_result_toll_road
import me.matsumo.onenavi.core.ui.callout.Callout
import me.matsumo.onenavi.core.ui.callout.CalloutAnchor
import me.matsumo.onenavi.core.ui.callout.CalloutLayer
import me.matsumo.onenavi.core.ui.callout.CalloutPlacementStrategy
import me.matsumo.onenavi.core.ui.callout.CalloutTailDirection
import me.matsumo.onenavi.core.ui.navigation.ManeuverIcon
import me.matsumo.onenavi.feature.home.R
import me.matsumo.onenavi.feature.home.map.state.HomeMapScreenState
import org.jetbrains.compose.resources.stringResource

private const val TAG = "HomeMapsMap"

private const val PRIMARY_ROUTE_OUTER_WIDTH = 24f
private const val PRIMARY_ROUTE_INNER_WIDTH = 16f
private const val SECONDARY_ROUTE_OUTER_WIDTH = 16f
private const val SECONDARY_ROUTE_INNER_WIDTH = 10f

private const val PRIMARY_ROUTE_OUTER_COLOR = 0xFF1A56C7
private const val PRIMARY_ROUTE_INNER_COLOR = 0xFF4285F4
private const val SECONDARY_ROUTE_OUTER_COLOR = 0xFF7986CB
private const val SECONDARY_ROUTE_INNER_COLOR = 0xFFB0BEC5

private const val CONGESTION_SLOW_COLOR = 0xFFFBBC04
private const val CONGESTION_JAM_COLOR = 0xFFE53935

private const val ROUTE_OUTER_PRIMARY_Z = 2f
private const val ROUTE_INNER_PRIMARY_Z = 3f
private const val ROUTE_OUTER_SECONDARY_Z = 0f
private const val ROUTE_INNER_SECONDARY_Z = 1f
private const val ROUTE_CONGESTION_OVERLAY_Z = 4f

private const val ROUTE_CALLOUT_PRIMARY_BG = 0xFF4285F4
private const val ROUTE_CALLOUT_SECONDARY_BG = 0xFFFFFFFF
private const val ROUTE_CALLOUT_SECONDARY_FG = 0xFF202124

private val ROUTE_CALLOUT_CANDIDATE_FRACTIONS = doubleArrayOf(0.3, 0.7, 0.5, 0.15, 0.85, 0.05, 0.95)

@Composable
internal fun HomeMapsMapEffectContent(
    viewportState: HomeMapViewportState,
    screenState: HomeMapScreenState,
    routeResults: ImmutableList<RouteResult>,
    selectedRouteIndex: Int,
    currentLocation: RoutePoint?,
    currentBearing: Float,
    upcomingNavigationCallouts: ImmutableList<NavigationCalloutInfo>,
    cameraManager: CameraManager,
    cameraFollowSpec: CameraFollowSpec?,
    onMapLandmarkSelected: (name: String?, latitude: Double, longitude: Double) -> Unit,
    onRouteSelected: (index: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val hasLocationPermission = remember(context) {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
    }
    val mapPadding by cameraManager.mapPadding.collectAsStateWithLifecycle()
    val vehiclePuckBitmap = remember(context) {
        context.createBitmap(R.drawable.ic_vehicle_puck)
    }
    val mapView = rememberMapViewWithLifecycle()
    val overlayObjects = remember { HomeMapOverlayObjects() }
    val currentOnMapLandmarkSelected = rememberUpdatedState(onMapLandmarkSelected)
    val currentOnRouteSelected = rememberUpdatedState(onRouteSelected)
    val currentSelectedRouteIndex = rememberUpdatedState(selectedRouteIndex)
    val currentScreenState = rememberUpdatedState(screenState)
    val currentCameraManager = rememberUpdatedState(cameraManager)

    var googleMap by remember { mutableStateOf<GoogleMap?>(null) }
    var vehiclePuckIcon by remember { mutableStateOf<BitmapDescriptor?>(null) }

    LaunchedEffect(context, vehiclePuckBitmap) {
        runCatching {
            MapsInitializer.initialize(context.applicationContext)
            BitmapDescriptorFactory.fromBitmap(vehiclePuckBitmap)
        }.onSuccess { icon ->
            vehiclePuckIcon = icon
        }.onFailure { error ->
            Napier.e(error, tag = TAG) { "Failed to create vehicle puck icon." }
        }
    }

    LaunchedEffect(googleMap, mapPadding, hasLocationPermission, vehiclePuckIcon) {
        val map = googleMap ?: return@LaunchedEffect
        map.setPadding(
            mapPadding.left,
            mapPadding.top,
            mapPadding.right,
            mapPadding.bottom,
        )
        map.isBuildingsEnabled = true
        map.isMyLocationEnabled = hasLocationPermission && vehiclePuckIcon == null
        map.uiSettings.apply {
            isCompassEnabled = false
            isMapToolbarEnabled = false
            isMyLocationButtonEnabled = false
            isZoomControlsEnabled = false
        }
    }

    LaunchedEffect(googleMap, routeResults, selectedRouteIndex, screenState) {
        val map = googleMap ?: return@LaunchedEffect
        overlayObjects.replaceRoutePolylines(
            googleMap = map,
            routeResults = routeResults,
            selectedRouteIndex = selectedRouteIndex,
        )
        overlayObjects.replaceStaticMarkers(
            googleMap = map,
            screenState = screenState,
        )
    }

    LaunchedEffect(googleMap, currentLocation, currentBearing, vehiclePuckIcon, hasLocationPermission, cameraFollowSpec) {
        val map = googleMap ?: return@LaunchedEffect
        overlayObjects.updateVehicleMarker(
            googleMap = map,
            currentLocation = currentLocation,
            currentBearing = currentBearing,
            vehiclePuckIcon = vehiclePuckIcon,
            hasLocationPermission = hasLocationPermission,
            cameraFollowSpec = cameraFollowSpec,
        )
    }

    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            googleMap?.let(viewportState::clearMap)
            googleMap = null
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                mapView.apply {
                    getMapAsync { map ->
                        googleMap = map
                        viewportState.attachMap(map)
                        map.setOnMapLongClickListener { latLng ->
                            currentOnMapLandmarkSelected.value(null, latLng.latitude, latLng.longitude)
                        }
                        map.setOnPoiClickListener { poi ->
                            currentOnMapLandmarkSelected.value(poi.name, poi.latLng.latitude, poi.latLng.longitude)
                        }
                        map.setOnPolylineClickListener { polyline ->
                            val routeIndex = polyline.tag as? Int ?: return@setOnPolylineClickListener
                            if (routeIndex != currentSelectedRouteIndex.value) {
                                currentOnRouteSelected.value(routeIndex)
                            }
                        }
                        map.setOnCameraMoveStartedListener { reason ->
                            viewportState.setCameraMoving(true)
                            val isGesture = reason == GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE
                            viewportState.setGestureInProgress(isGesture)
                            // ナビ中のジェスチャーでは、自車移動アニメの moveCamera との
                            // 取り合いを避けるためカメラ追従を IDLE に切り替える。
                            // NaviReturnButton で再度 FOLLOWING へ戻せる。
                            if (isGesture && currentScreenState.value is HomeMapScreenState.Navigating) {
                                currentCameraManager.value.requestCameraIdle()
                            }
                        }
                        map.setOnCameraMoveListener {
                            viewportState.updateCameraPosition(map.cameraPosition)
                        }
                        map.setOnCameraIdleListener {
                            viewportState.updateCameraPosition(map.cameraPosition)
                            viewportState.setGestureInProgress(false)
                            viewportState.setCameraMoving(false)
                            viewportState.notifyCameraSettled()
                        }
                        map.setOnMapLoadedCallback {
                            val camera = map.cameraPosition
                            Napier.d(tag = TAG) {
                                "Map loaded: target=${camera.target}, zoom=${camera.zoom}, tilt=${camera.tilt}, bearing=${camera.bearing}"
                            }
                        }
                    }
                }
            },
        )

        if (screenState is HomeMapScreenState.RoutePreview) {
            val routeAnchors = remember(
                routeResults,
                screenState,
                googleMap,
                viewportState.cameraState,
            ) {
                val map = googleMap
                if (map == null) {
                    persistentListOf()
                } else {
                    buildRouteCalloutAnchors(map, routeResults)
                }
            }

            CalloutLayer(
                anchors = routeAnchors,
                placementStrategy = CalloutPlacementStrategy.AvoidOverlap,
                isCameraMoving = viewportState.isCameraMoving,
                cameraSettleEpoch = viewportState.cameraSettleEpoch,
                modifier = Modifier.fillMaxSize(),
            ) { index, tailDirection ->
                val routeResult = routeResults[index]
                val isPrimary = index == selectedRouteIndex

                HomeMapRouteCallout(
                    tailDirection = tailDirection,
                    routeResult = routeResult,
                    isPrimary = isPrimary,
                    onClick = {
                        if (!isPrimary) {
                            onRouteSelected(index)
                        }
                    },
                )
            }
        }

        if (screenState is HomeMapScreenState.Navigating && upcomingNavigationCallouts.isNotEmpty()) {
            val navigationAnchors = remember(
                upcomingNavigationCallouts,
                googleMap,
                viewportState.cameraState,
            ) {
                val map = googleMap
                if (map == null) {
                    persistentListOf()
                } else {
                    buildNavigationCalloutAnchors(map, upcomingNavigationCallouts)
                }
            }

            CalloutLayer(
                anchors = navigationAnchors,
                placementStrategy = CalloutPlacementStrategy.AnchorFirst,
                isCameraMoving = viewportState.isGestureInProgress,
                cameraSettleEpoch = viewportState.cameraSettleEpoch,
                modifier = Modifier.fillMaxSize(),
                isContinuousTracking = true,
            ) { index, tailDirection ->
                val info = upcomingNavigationCallouts[index]
                HomeMapNavigationCallout(
                    tailDirection = tailDirection,
                    maneuverType = info.maneuverType,
                    maneuverModifier = info.maneuverModifier,
                    intersectionName = info.intersectionName,
                )
            }
        }
    }
}

private fun buildRouteCalloutAnchors(
    googleMap: GoogleMap,
    routeResults: ImmutableList<RouteResult>,
): ImmutableList<CalloutAnchor> {
    val visibleBounds = googleMap.projection.visibleRegion.latLngBounds
    return routeResults.mapIndexed { index, routeResult ->
        val visiblePoints = routeResult.item.geometry.filter { point ->
            visibleBounds.contains(LatLng(point.latitude, point.longitude))
        }
        val candidates = if (visiblePoints.isEmpty()) {
            persistentListOf()
        } else {
            ROUTE_CALLOUT_CANDIDATE_FRACTIONS
                .map { fraction ->
                    val pointIndex = (visiblePoints.size * fraction).toInt()
                        .coerceIn(0, visiblePoints.lastIndex)
                    val point = visiblePoints[pointIndex]
                    val screen = googleMap.projection.toScreenLocation(
                        LatLng(point.latitude, point.longitude),
                    )
                    Offset(screen.x.toFloat(), screen.y.toFloat())
                }
                .distinct()
                .toPersistentList()
        }
        CalloutAnchor.Flexible(
            id = index,
            primaryPoint = candidates.firstOrNull() ?: Offset.Zero,
            candidates = candidates,
        )
    }.toImmutableList()
}

private fun buildNavigationCalloutAnchors(
    googleMap: GoogleMap,
    callouts: ImmutableList<NavigationCalloutInfo>,
): ImmutableList<CalloutAnchor> {
    return callouts.mapIndexed { index, info ->
        val screen = googleMap.projection.toScreenLocation(
            LatLng(info.maneuverLocation.latitude, info.maneuverLocation.longitude),
        )
        CalloutAnchor.Fixed(
            id = "nav-$index",
            primaryPoint = Offset(screen.x.toFloat(), screen.y.toFloat()),
        )
    }.toImmutableList()
}

@Composable
private fun HomeMapNavigationCallout(
    tailDirection: CalloutTailDirection,
    maneuverType: String,
    maneuverModifier: String?,
    intersectionName: String?,
    modifier: Modifier = Modifier,
) {
    Callout(
        tailDirection = tailDirection,
        modifier = modifier,
        backgroundColor = Color(ROUTE_CALLOUT_PRIMARY_BG),
        contentColor = Color.White,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ManeuverIcon(
                modifier = Modifier.size(20.dp),
                type = maneuverType,
                maneuverModifier = maneuverModifier,
                contentDescription = null,
                tint = Color.White,
            )
            intersectionName?.takeIf { it.isNotBlank() }?.let { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun HomeMapRouteCallout(
    tailDirection: CalloutTailDirection,
    routeResult: RouteResult,
    isPrimary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dayLabel = stringResource(Res.string.common_unit_day)
    val hourLabel = stringResource(Res.string.common_unit_hour)
    val minuteLabel = stringResource(Res.string.common_unit_minute)
    val tollRoadLabel = stringResource(Res.string.home_map_route_result_toll_road)
    val generalRoadLabel = stringResource(Res.string.home_map_route_result_general_road)

    val durationText = formatDuration(
        totalSeconds = routeResult.item.durationSeconds,
        dayLabel = dayLabel,
        hourLabel = hourLabel,
        minuteLabel = minuteLabel,
    )
    val tollFee = routeResult.item.tollFee
    val tollText = when {
        tollFee != null -> formatYen(tollFee)
        routeResult.item.hasTolls -> tollRoadLabel
        else -> generalRoadLabel
    }

    val backgroundColor = if (isPrimary) {
        Color(ROUTE_CALLOUT_PRIMARY_BG)
    } else {
        Color(ROUTE_CALLOUT_SECONDARY_BG)
    }
    val contentColor = if (isPrimary) {
        Color.White
    } else {
        Color(ROUTE_CALLOUT_SECONDARY_FG)
    }

    Callout(
        tailDirection = tailDirection,
        modifier = modifier,
        backgroundColor = backgroundColor,
        contentColor = contentColor,
        onClick = onClick,
    ) {
        Text(
            text = durationText,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
        )

        Text(
            text = tollText,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

private fun RoutePoint.toLatLng(): LatLng {
    return LatLng(latitude, longitude)
}

private fun RouteWaypoint.toRoutePoint(): RoutePoint {
    return when (this) {
        is RouteWaypoint.CurrentLocation -> RoutePoint(latitude, longitude)
        is RouteWaypoint.Place -> RoutePoint(latitude, longitude)
    }
}

private fun Context.createBitmap(drawableRes: Int): Bitmap {
    val drawable = checkNotNull(AppCompatResources.getDrawable(this, drawableRes))
    val width = drawable.intrinsicWidth.coerceAtLeast(1)
    val height = drawable.intrinsicHeight.coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, canvas.width, canvas.height)
    drawable.draw(canvas)
    return bitmap
}

@Composable
private fun rememberMapViewWithLifecycle(): MapView {
    val context = LocalContext.current
    return remember(context) {
        MapView(context).apply {
            onCreate(Bundle())
        }
    }
}

private class HomeMapOverlayObjects {

    private val routePolylines = mutableListOf<Polyline>()
    private val staticMarkers = mutableListOf<Marker>()
    private var vehicleMarker: Marker? = null
    private var vehicleAnimator: ValueAnimator? = null
    private var lastAppliedPosition: LatLng? = null
    private var lastAppliedBearing: Float = 0f

    fun replaceRoutePolylines(
        googleMap: GoogleMap,
        routeResults: ImmutableList<RouteResult>,
        selectedRouteIndex: Int,
    ) {
        routePolylines.forEach(Polyline::remove)
        routePolylines.clear()

        routeResults.forEachIndexed { index, routeResult ->
            if (index == selectedRouteIndex) return@forEachIndexed
            addRoutePolylinePair(
                googleMap = googleMap,
                routeIndex = index,
                geometry = routeResult.item.geometry,
                outerColor = SECONDARY_ROUTE_OUTER_COLOR,
                innerColor = SECONDARY_ROUTE_INNER_COLOR,
                outerWidth = SECONDARY_ROUTE_OUTER_WIDTH,
                innerWidth = SECONDARY_ROUTE_INNER_WIDTH,
                outerZIndex = ROUTE_OUTER_SECONDARY_Z,
                innerZIndex = ROUTE_INNER_SECONDARY_Z,
            )
        }

        val primaryRoute = routeResults.getOrNull(selectedRouteIndex) ?: return
        addRoutePolylinePair(
            googleMap = googleMap,
            routeIndex = selectedRouteIndex,
            geometry = primaryRoute.item.geometry,
            outerColor = PRIMARY_ROUTE_OUTER_COLOR,
            innerColor = PRIMARY_ROUTE_INNER_COLOR,
            outerWidth = PRIMARY_ROUTE_OUTER_WIDTH,
            innerWidth = PRIMARY_ROUTE_INNER_WIDTH,
            outerZIndex = ROUTE_OUTER_PRIMARY_Z,
            innerZIndex = ROUTE_INNER_PRIMARY_Z,
        )

        addCongestionOverlays(
            googleMap = googleMap,
            routeIndex = selectedRouteIndex,
            geometry = primaryRoute.item.geometry,
            congestionSegments = primaryRoute.item.congestionSegments,
        )
    }

    private fun addRoutePolylinePair(
        googleMap: GoogleMap,
        routeIndex: Int,
        geometry: ImmutableList<RoutePoint>,
        outerColor: Long,
        innerColor: Long,
        outerWidth: Float,
        innerWidth: Float,
        outerZIndex: Float,
        innerZIndex: Float,
    ) {
        if (geometry.size < 2) return
        val latLngs = geometry.map(RoutePoint::toLatLng)

        val outer = googleMap.addPolyline(
            PolylineOptions()
                .addAll(latLngs)
                .color(Color(outerColor).toArgb())
                .width(outerWidth)
                .clickable(true)
                .zIndex(outerZIndex),
        )
        outer.tag = routeIndex
        routePolylines += outer

        val inner = googleMap.addPolyline(
            PolylineOptions()
                .addAll(latLngs)
                .color(Color(innerColor).toArgb())
                .width(innerWidth)
                .clickable(true)
                .zIndex(innerZIndex),
        )
        inner.tag = routeIndex
        routePolylines += inner
    }

    private fun addCongestionOverlays(
        googleMap: GoogleMap,
        routeIndex: Int,
        geometry: ImmutableList<RoutePoint>,
        congestionSegments: ImmutableList<CongestionSegment>,
    ) {
        if (geometry.isEmpty() || congestionSegments.isEmpty()) return

        congestionSegments.forEach { segment ->
            val color = when (segment.severity) {
                CongestionSeverity.SLOW -> CONGESTION_SLOW_COLOR
                CongestionSeverity.TRAFFIC_JAM -> CONGESTION_JAM_COLOR
                CongestionSeverity.NORMAL,
                CongestionSeverity.UNKNOWN,
                -> return@forEach
            }

            val fromIndex = segment.startPolylinePointIndex.coerceIn(0, geometry.lastIndex)
            val toIndex = (segment.endPolylinePointIndex + 1).coerceIn(fromIndex + 1, geometry.size)
            val slice = geometry
                .subList(fromIndex, toIndex)
                .map(RoutePoint::toLatLng)
            if (slice.size < 2) return@forEach

            val polyline = googleMap.addPolyline(
                PolylineOptions()
                    .addAll(slice)
                    .color(Color(color).toArgb())
                    .width(PRIMARY_ROUTE_INNER_WIDTH)
                    .clickable(true)
                    .zIndex(ROUTE_CONGESTION_OVERLAY_Z),
            )
            polyline.tag = routeIndex
            routePolylines += polyline
        }
    }

    fun replaceStaticMarkers(
        googleMap: GoogleMap,
        screenState: HomeMapScreenState,
    ) {
        staticMarkers.forEach(Marker::remove)
        staticMarkers.clear()

        when (screenState) {
            is HomeMapScreenState.Browsing,
            is HomeMapScreenState.Navigating,
            is HomeMapScreenState.Arrived,
            -> Unit

            is HomeMapScreenState.SearchResultsList -> {
                screenState.results.forEachIndexed { index, result ->
                    googleMap.addMarker(
                        MarkerOptions()
                            .position(LatLng(result.latitude, result.longitude))
                            .title(result.name)
                            .snippet((index + 1).toString())
                            .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)),
                    )?.let(staticMarkers::add)
                }
            }

            is HomeMapScreenState.PlaceDetails -> {
                googleMap.addMarker(
                    MarkerOptions()
                        .position(LatLng(screenState.place.latitude, screenState.place.longitude))
                        .title(screenState.place.name)
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)),
                )?.let(staticMarkers::add)
            }

            is HomeMapScreenState.RoutePreview -> {
                screenState.waypoints.lastOrNull()?.let { waypoint ->
                    googleMap.addMarker(
                        MarkerOptions()
                            .position(waypoint.toRoutePoint().toLatLng())
                            .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)),
                    )?.let(staticMarkers::add)
                }
                screenState.waypoints.drop(1).dropLast(1).forEachIndexed { index, waypoint ->
                    googleMap.addMarker(
                        MarkerOptions()
                            .position(waypoint.toRoutePoint().toLatLng())
                            .title("K${index + 1}"),
                    )?.let(staticMarkers::add)
                }
            }
        }
    }

    fun updateVehicleMarker(
        googleMap: GoogleMap,
        currentLocation: RoutePoint?,
        currentBearing: Float,
        vehiclePuckIcon: BitmapDescriptor?,
        hasLocationPermission: Boolean,
        cameraFollowSpec: CameraFollowSpec?,
    ) {
        googleMap.isMyLocationEnabled = hasLocationPermission && vehiclePuckIcon == null

        if (vehiclePuckIcon == null || currentLocation == null) {
            vehicleAnimator?.cancel()
            vehicleAnimator = null
            vehicleMarker?.remove()
            vehicleMarker = null
            lastAppliedPosition = null
            return
        }

        val targetPosition = currentLocation.toLatLng()
        val marker = vehicleMarker
        if (marker == null) {
            vehicleMarker = googleMap.addMarker(
                MarkerOptions()
                    .position(targetPosition)
                    .icon(vehiclePuckIcon)
                    .flat(true)
                    .anchor(0.5f, 0.5f)
                    .rotation(currentBearing)
                    .zIndex(4f),
            )
            lastAppliedPosition = targetPosition
            lastAppliedBearing = currentBearing
            return
        }

        marker.setIcon(vehiclePuckIcon)

        val fromPosition = lastAppliedPosition ?: targetPosition
        val fromBearing = lastAppliedBearing
        val bearingDelta = shortestBearingDelta(fromBearing, currentBearing)
        val toBearing = fromBearing + bearingDelta

        vehicleAnimator?.cancel()
        vehicleAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = VEHICLE_ANIMATION_DURATION_MS
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                val fraction = animator.animatedValue as Float
                val latitude = fromPosition.latitude +
                    (targetPosition.latitude - fromPosition.latitude) * fraction
                val longitude = fromPosition.longitude +
                    (targetPosition.longitude - fromPosition.longitude) * fraction
                val bearing = fromBearing + (toBearing - fromBearing) * fraction
                val latLng = LatLng(latitude, longitude)

                marker.position = latLng
                marker.rotation = bearing

                cameraFollowSpec?.let { spec ->
                    googleMap.moveCamera(
                        CameraUpdateFactory.newCameraPosition(
                            CameraPosition.Builder()
                                .target(latLng)
                                .zoom(spec.zoom)
                                .tilt(spec.tilt)
                                .bearing(if (spec.useLocationBearing) bearing else 0f)
                                .build(),
                        ),
                    )
                }
            }
            start()
        }

        lastAppliedPosition = targetPosition
        lastAppliedBearing = currentBearing
    }

    companion object {
        private const val VEHICLE_ANIMATION_DURATION_MS = 1000L
    }
}

/**
 * カメラ追従の指定仕様。null ならカメラ追従せず、マーカー補間のみを行う。
 *
 * @param zoom カメラのズーム
 * @param tilt カメラのチルト
 * @param useLocationBearing true の場合は自車の進行方向、false の場合は常に北を向ける
 */
@Immutable
internal data class CameraFollowSpec(
    val zoom: Float,
    val tilt: Float,
    val useLocationBearing: Boolean,
)

private fun shortestBearingDelta(from: Float, to: Float): Float {
    return ((to - from + 540f) % 360f) - 180f
}
