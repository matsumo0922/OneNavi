package me.matsumo.onenavi.feature.home.map

import android.app.Activity
import android.view.WindowManager
import androidx.compose.material3.BottomSheetScaffoldState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import me.matsumo.onenavi.core.model.RoutePoint
import me.matsumo.onenavi.core.model.RouteWaypoint
import me.matsumo.onenavi.core.navigation.CameraManager
import me.matsumo.onenavi.core.navigation.CameraState
import me.matsumo.onenavi.core.navigation.MapPadding
import me.matsumo.onenavi.core.navigation.RouteManager
import me.matsumo.onenavi.feature.home.map.components.LocationTrackingMode
import me.matsumo.onenavi.feature.home.map.state.HomeMapEffect
import me.matsumo.onenavi.feature.home.map.state.HomeMapScreenState
import kotlin.time.Duration.Companion.milliseconds

private const val FOLLOW_PUCK_ZOOM = 16f
private const val ROUTE_BOUNDS_EXTRA_PADDING = 96

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeMapScreenSheetEffect(
    shouldShowSheet: Boolean,
    scaffoldState: BottomSheetScaffoldState,
    onSheetShowing: () -> Unit,
    onAllowSheetHide: (Boolean) -> Unit,
) {
    LaunchedEffect(shouldShowSheet) {
        if (shouldShowSheet) {
            onSheetShowing()
            scaffoldState.bottomSheetState.partialExpand()
        } else {
            onAllowSheetHide(true)
            scaffoldState.bottomSheetState.hide()
            onAllowSheetHide(false)
        }
    }
}

@Composable
internal fun HomeMapScreenViewportTrackingEffect(
    viewportState: HomeMapViewportState,
    onTrackingModeCleared: () -> Unit,
) {
    LaunchedEffect(viewportState) {
        snapshotFlow { viewportState.isGestureInProgress }
            .distinctUntilChanged()
            .collect { isGestureInProgress ->
                if (isGestureInProgress) {
                    onTrackingModeCleared()
                }
            }
    }
}

@Composable
internal fun HomeMapScreenCameraEffect(
    screenState: HomeMapScreenState,
    effects: Flow<HomeMapEffect>,
    routeManager: RouteManager,
    cameraManager: CameraManager,
    viewportState: HomeMapViewportState,
    sheetPeekHeightPx: Double,
    topOverlayBottomPx: Float,
    navigationCameraState: CameraState,
    mapPadding: MapPadding,
    activity: Activity?,
    onTrackingModeChanged: (LocationTrackingMode?) -> Unit,
) {
    val currentActivity = rememberUpdatedState(activity)
    val currentSheetPeekHeightPx = rememberUpdatedState(sheetPeekHeightPx)
    val currentTopOverlayBottomPx = rememberUpdatedState(topOverlayBottomPx)
    val currentScreenState = rememberUpdatedState(screenState)

    LaunchedEffect(
        viewportState,
        screenState,
        sheetPeekHeightPx,
        topOverlayBottomPx,
        navigationCameraState,
        mapPadding,
    ) {
        restoreCamera(
            screenState = screenState,
            cameraManager = cameraManager,
            viewportState = viewportState,
            sheetPeekHeightPx = sheetPeekHeightPx,
            topOverlayBottomPx = topOverlayBottomPx,
            navigationCameraState = navigationCameraState,
            mapPadding = mapPadding,
        )
    }

    // Navigating への遷移時に一度だけ FOLLOWING を張る。
    // 上の LaunchedEffect は navigationCameraState を key に含むため、
    // ジェスチャで IDLE に落ちた瞬間に再発火 → FOLLOWING に戻してしまう。
    // ユーザー操作で IDLE を維持できるよう、Navigating エントリだけをここに分離する。
    LaunchedEffect(screenState is HomeMapScreenState.Navigating) {
        if (screenState is HomeMapScreenState.Navigating) {
            cameraManager.requestCameraFollowing(pitch3D = true)
        }
    }

    LaunchedEffect(viewportState) {
        effects.collect { effect ->
            handleEffect(
                effect = effect,
                routeManager = routeManager,
                cameraManager = cameraManager,
                viewportState = viewportState,
                sheetPeekHeightPx = currentSheetPeekHeightPx.value,
                topOverlayBottomPx = currentTopOverlayBottomPx.value,
                screenState = currentScreenState.value,
                activity = currentActivity.value,
                onTrackingModeChanged = onTrackingModeChanged,
            )
        }
    }
}

private suspend fun restoreCamera(
    screenState: HomeMapScreenState,
    cameraManager: CameraManager,
    viewportState: HomeMapViewportState,
    sheetPeekHeightPx: Double,
    topOverlayBottomPx: Float,
    navigationCameraState: CameraState,
    mapPadding: MapPadding,
) {
    when (val state = screenState) {
        is HomeMapScreenState.SearchResultsList -> {
            viewportState.moveToBounds(
                points = state.results.map { RoutePoint(it.latitude, it.longitude) },
                paddingPx = buildBoundsPadding(topOverlayBottomPx, sheetPeekHeightPx),
            )
        }

        is HomeMapScreenState.PlaceDetails -> {
            viewportState.moveTo(RoutePoint(state.place.latitude, state.place.longitude), FOLLOW_PUCK_ZOOM)
        }

        is HomeMapScreenState.RoutePreview -> {
            val overviewPadding = buildOverlayPadding(topOverlayBottomPx, sheetPeekHeightPx)
            cameraManager.applyNavigationPadding(
                followingPadding = MapPadding(),
                overviewPadding = overviewPadding,
            )
            if (navigationCameraState != CameraState.OVERVIEW || mapPadding != overviewPadding) {
                cameraManager.requestCameraOverview()
                return
            }
            moveToRouteOverview(
                viewportState = viewportState,
                routeResults = state.routes,
                waypoints = state.waypoints.map { it.toRoutePoint() },
            )
        }

        is HomeMapScreenState.Navigating -> {
            // Navigating への初回遷移は上位の LaunchedEffect が担う。
            // ここで requestCameraFollowing を呼ぶと IDLE → FOLLOWING にループで戻ってしまう。
        }

        else -> {
            /* Browsing / Arrived */
        }
    }
}

private suspend fun handleEffect(
    effect: HomeMapEffect,
    routeManager: RouteManager,
    cameraManager: CameraManager,
    viewportState: HomeMapViewportState,
    sheetPeekHeightPx: Double,
    topOverlayBottomPx: Float,
    screenState: HomeMapScreenState,
    activity: Activity?,
    onTrackingModeChanged: (LocationTrackingMode?) -> Unit,
) {
    when (effect) {
        is HomeMapEffect.MoveCameraToSearchResults -> {
            onTrackingModeChanged(null)
            viewportState.moveToBounds(
                points = effect.results.map { RoutePoint(it.latitude, it.longitude) },
                paddingPx = buildBoundsPadding(topOverlayBottomPx, sheetPeekHeightPx),
            )
        }

        is HomeMapEffect.MoveCameraToPlace -> {
            onTrackingModeChanged(null)
            viewportState.moveTo(RoutePoint(effect.place.latitude, effect.place.longitude), FOLLOW_PUCK_ZOOM)
        }

        is HomeMapEffect.MoveCameraToRouteOverview -> {
            onTrackingModeChanged(null)
            val hasRoutes = withTimeoutOrNull(3_000.milliseconds) {
                routeManager.routes.first { it.isNotEmpty() }
            } != null
            if (hasRoutes && screenState is HomeMapScreenState.RoutePreview) {
                cameraManager.applyNavigationPadding(
                    followingPadding = MapPadding(),
                    overviewPadding = buildOverlayPadding(topOverlayBottomPx, sheetPeekHeightPx),
                )
                cameraManager.requestCameraOverview()
            }
        }

        is HomeMapEffect.EnterGuidanceFollowing -> {
            cameraManager.requestCameraFollowing(pitch3D = true)
        }

        is HomeMapEffect.RestoreTracking -> {
            onTrackingModeChanged(LocationTrackingMode.TiltedHeading)
        }

        is HomeMapEffect.SetKeepScreenOn -> {
            if (effect.enabled) {
                activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }
}

private suspend fun moveToRouteOverview(
    viewportState: HomeMapViewportState,
    routeResults: ImmutableList<RouteResult>,
    waypoints: List<RoutePoint>,
) {
    val points = buildRouteOverviewPoints(routeResults, waypoints)
    if (points.isNotEmpty()) {
        viewportState.moveToBounds(
            points = points,
            paddingPx = ROUTE_BOUNDS_EXTRA_PADDING,
        )
    }
}

private fun buildRouteOverviewPoints(
    routeResults: ImmutableList<RouteResult>,
    waypoints: List<RoutePoint>,
): List<RoutePoint> {
    return buildList {
        routeResults.forEach { routeResult ->
            addAll(routeResult.item.geometry)
        }
        addAll(waypoints)
    }
}

private fun RouteWaypoint.toRoutePoint(): RoutePoint {
    return when (this) {
        is RouteWaypoint.CurrentLocation -> RoutePoint(latitude, longitude)
        is RouteWaypoint.Place -> RoutePoint(latitude, longitude)
    }
}

private fun buildOverlayPadding(
    topOverlayBottomPx: Float,
    bottomSheetPeekHeightPx: Double,
): MapPadding {
    return MapPadding(
        top = topOverlayBottomPx.toInt(),
        bottom = bottomSheetPeekHeightPx.toInt(),
    )
}

private fun buildBoundsPadding(
    topOverlayBottomPx: Float,
    bottomSheetPeekHeightPx: Double,
): Int {
    return (topOverlayBottomPx + bottomSheetPeekHeightPx).toInt() / 2 + ROUTE_BOUNDS_EXTRA_PADDING
}
