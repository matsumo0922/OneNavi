package me.matsumo.onenavi.feature.map

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import me.matsumo.onenavi.core.model.RouteDetail
import me.matsumo.onenavi.core.model.RoutePoint
import me.matsumo.onenavi.core.navigation.newguidance.model.GuidanceProgress
import me.matsumo.onenavi.core.navigation.newguidance.model.GuidanceState
import me.matsumo.onenavi.core.navigation.newguidance.model.RouteMatchState
import me.matsumo.onenavi.core.navigation.newguidance.model.RoutePreviewState
import me.matsumo.onenavi.feature.map.state.MapCameraState
import me.matsumo.onenavi.feature.map.state.MapScreenState
import me.matsumo.onenavi.feature.map.state.MapUiState
import me.matsumo.onenavi.feature.map.state.RouteMeterIndex
import me.matsumo.onenavi.feature.map.state.VehicleLocationState

/**
 * 画面状態の変化を GoogleMap カメラ操作へ変換する。
 *
 * @param uiState map screen の UI state
 * @param screenState 現在の地図画面状態
 * @param routePreviewState Preview 期のルート候補状態
 * @param guidanceState Guidance 期の案内状態
 * @param vehicleLocationState 最新の自車位置
 * @param cameraState カメラ操作を保持する state holder
 */
@Composable
internal fun MapCameraEffect(
    uiState: MapUiState,
    screenState: MapScreenState,
    routePreviewState: RoutePreviewState,
    guidanceState: GuidanceState,
    vehicleLocationState: VehicleLocationState?,
    cameraState: MapCameraState,
) {
    val density = LocalDensity.current
    val statusBarHeightPadding = WindowInsets.statusBars
        .asPaddingValues()
        .calculateTopPadding()
    val isGuidanceCameraActive = screenState is MapScreenState.Navigating

    LaunchedEffect(isGuidanceCameraActive) {
        cameraState.setGuidanceCameraActive(isGuidanceCameraActive)
    }

    val guiding = guidanceState as? GuidanceState.Guiding
    val nextManeuver = guiding?.presentation?.nextManeuver
    val guidancePointIndex = nextManeuver?.guidancePointIndex
    val distanceToManeuverMeters = nextManeuver?.distanceToManeuverMeters
    val isOnRoute = guiding?.progress?.routeMatchState == RouteMatchState.ON_ROUTE
    val isManeuverPassed = distanceToManeuverMeters != null &&
        distanceToManeuverMeters <= GUIDANCE_MANEUVER_PASSED_DISTANCE_METERS
    val shouldStartManeuverFocus = isGuidanceCameraActive &&
        isOnRoute &&
        guidancePointIndex != null &&
        distanceToManeuverMeters != null &&
        distanceToManeuverMeters > GUIDANCE_MANEUVER_PASSED_DISTANCE_METERS &&
        distanceToManeuverMeters <= GUIDANCE_MANEUVER_FOCUS_DISTANCE_METERS

    LaunchedEffect(guiding?.route?.id) {
        cameraState.clearGuidanceManeuverFocus()
    }

    LaunchedEffect(guidancePointIndex, shouldStartManeuverFocus) {
        cameraState.updateGuidanceManeuverFocusTarget(
            guidancePointIndex = guidancePointIndex,
            restoreCamera = !shouldStartManeuverFocus,
        )
    }

    LaunchedEffect(
        isGuidanceCameraActive,
        guiding?.route?.id,
        guidancePointIndex,
        isOnRoute,
        isManeuverPassed,
        shouldStartManeuverFocus,
    ) {
        if (!isGuidanceCameraActive || guiding == null || guidancePointIndex == null) {
            cameraState.clearGuidanceManeuverFocus()
            return@LaunchedEffect
        }

        if (!isOnRoute) {
            cameraState.finishGuidanceManeuverFocusForRouteMismatch(guidancePointIndex)
            return@LaunchedEffect
        }

        if (isManeuverPassed) {
            cameraState.finishGuidanceManeuverFocusIfPassed(guidancePointIndex)
            return@LaunchedEffect
        }

        if (shouldStartManeuverFocus) {
            cameraState.startGuidanceManeuverFocusIfNeeded(guidancePointIndex)
        }
    }

    LaunchedEffect(
        uiState.bottomSheetPeekHeight,
        uiState.topAppBarHeight,
        uiState.navigationCardHeight,
        screenState,
    ) {
        val top = uiState.topAppBarHeight + with(density) { statusBarHeightPadding.toPx() }
        val bottom = if (isGuidanceCameraActive) {
            uiState.navigationCardHeight.toFloat()
        } else {
            with(density) { uiState.bottomSheetPeekHeight.toPx() }
        }
        val horizontal = with(density) { 24.dp.toPx() }

        cameraState.updatePadding(
            top = top.toInt(),
            bottom = bottom.toInt(),
            start = horizontal.toInt(),
            end = horizontal.toInt(),
        )
    }

    val routeOverviewPoints = remember(
        screenState,
        routePreviewState,
        uiState.isNavigationRoutePreviewing,
        guidanceState.routeOverviewKey(),
    ) {
        val ready = routePreviewState as? RoutePreviewState.Ready
        when (screenState) {
            is MapScreenState.RoutePreview if ready != null -> ready.routes.flatMap { it.geometry }
            is MapScreenState.Navigating if uiState.isNavigationRoutePreviewing -> when (guidanceState) {
                is GuidanceState.Guiding -> remainingRouteOverviewPoints(
                    route = guidanceState.route,
                    progress = guidanceState.progress,
                )

                is GuidanceState.Rerouting -> remainingRouteOverviewPoints(
                    route = guidanceState.previousRoute,
                    progress = guidanceState.previousProgress,
                )

                is GuidanceState.Arrived,
                is GuidanceState.Failed,
                is GuidanceState.Idle,
                -> null
            }

            else -> null
        }
    }

    val routeOverviewTopPaddingKey = if (screenState is MapScreenState.RoutePreview) uiState.topAppBarHeight else 0
    val routeOverviewBottomPaddingKey = if (screenState is MapScreenState.RoutePreview) uiState.bottomSheetPeekHeight else 0.dp

    // RoutePreview
    LaunchedEffect(routeOverviewPoints, routeOverviewTopPaddingKey, routeOverviewBottomPaddingKey) {
        routeOverviewPoints?.let { cameraState.showRouteOverview(it) }
    }

    LaunchedEffect(screenState) {
        when (screenState) {
            is MapScreenState.Browsing -> {
                cameraState.followVehicleLocation(vehicleLocationState)
            }

            is MapScreenState.PlaceDetails -> {
                cameraState.moveTo(
                    latitude = screenState.place.latitude,
                    longitude = screenState.place.longitude,
                    zoom = 18f,
                )
            }

            is MapScreenState.SearchResultsList -> {
                val points = screenState.results.map { result ->
                    RoutePoint(
                        latitude = result.latitude,
                        longitude = result.longitude,
                    )
                }

                when (points.size) {
                    0 -> Unit
                    1 -> cameraState.moveTo(
                        latitude = points.first().latitude,
                        longitude = points.first().longitude,
                        zoom = 16f,
                    )

                    else -> cameraState.showRouteOverview(points)
                }
            }

            is MapScreenState.RoutePreview -> {
                // ルートが揃ったタイミングで下の LaunchedEffect がカメラをフィットさせる
            }

            is MapScreenState.Navigating -> {
                cameraState.startGuidanceCamera(vehicleLocationState)
            }

            is MapScreenState.Arrived -> {
                // TODO
            }
        }
    }
}

/** 案内地点フォーカスを開始する残距離（m）。 */
private const val GUIDANCE_MANEUVER_FOCUS_DISTANCE_METERS = 100

/** 案内地点を通過済みと扱う残距離（m）。 */
private const val GUIDANCE_MANEUVER_PASSED_DISTANCE_METERS = 0

private fun GuidanceState.routeOverviewKey(): String? = when (this) {
    is GuidanceState.Guiding -> route.id
    is GuidanceState.Rerouting -> previousRoute.id
    is GuidanceState.Arrived,
    is GuidanceState.Failed,
    is GuidanceState.Idle,
    -> null
}

private fun remainingRouteOverviewPoints(
    route: RouteDetail,
    progress: GuidanceProgress,
): List<RoutePoint> {
    val meterIndex = RouteMeterIndex.from(route.geometry) ?: return route.geometry
    return meterIndex.pointsBetween(
        startDistanceMeters = progress.currentCumulativeMeters,
        endDistanceMeters = Double.MAX_VALUE,
        fallbackBearingDegrees = progress.bearingDegrees,
    )
}
