package me.matsumo.onenavi.feature.map

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.aakira.napier.Napier
import me.matsumo.onenavi.core.model.RouteDetail
import me.matsumo.onenavi.core.model.RoutePoint
import me.matsumo.onenavi.core.model.SearchResultItem
import me.matsumo.onenavi.core.navigation.newguidance.model.GuidanceProgress
import me.matsumo.onenavi.core.navigation.newguidance.model.GuidanceState
import me.matsumo.onenavi.core.navigation.newguidance.model.RouteMatchState
import me.matsumo.onenavi.core.navigation.newguidance.model.RoutePreviewState
import me.matsumo.onenavi.feature.map.state.MapCameraState
import me.matsumo.onenavi.feature.map.state.MapOverlayState
import me.matsumo.onenavi.feature.map.state.MapPanelLayout
import me.matsumo.onenavi.feature.map.state.MapScreenState
import me.matsumo.onenavi.feature.map.state.MapUiState
import me.matsumo.onenavi.feature.map.state.RouteMeterIndex
import me.matsumo.onenavi.feature.map.state.VehicleLocationState
import kotlin.math.roundToInt

/**
 * 画面状態の変化を GoogleMap カメラ操作へ変換する。
 *
 * @param uiState map screen の UI state
 * @param screenState 現在の地図画面状態
 * @param routePreviewState Preview 期のルート候補状態
 * @param overlayState 地図画面上に重ねるオーバーレイ状態
 * @param guidanceState Guidance 期の案内状態
 * @param vehicleLocationState 最新の自車位置
 * @param cameraState カメラ操作を保持する state holder
 * @param panelLayout 地図 UI 帯の配置情報
 * @param viewportWidthPx 地図 viewport の幅
 * @param viewportHeightPx 地図 viewport の高さ
 * @param shouldLogDiagnostics MapView / Camera の診断ログを出力するか
 */
@Composable
internal fun MapCameraEffect(
    uiState: MapUiState,
    screenState: MapScreenState,
    routePreviewState: RoutePreviewState,
    overlayState: MapOverlayState,
    guidanceState: GuidanceState,
    vehicleLocationState: VehicleLocationState?,
    cameraState: MapCameraState,
    panelLayout: MapPanelLayout,
    viewportWidthPx: Int,
    viewportHeightPx: Int,
    shouldLogDiagnostics: Boolean,
) {
    val mapRenderScale = LocalMapRenderScale.current
    val displayDensity = LocalDensity.current
    val density = Density(
        density = displayDensity.density * mapRenderScale,
        fontScale = displayDensity.fontScale,
    )
    val layoutDirection = LocalLayoutDirection.current
    val statusBarHeightPadding = WindowInsets.statusBars
        .asPaddingValues()
        .calculateTopPadding()
    val navigationBarBottomPadding = WindowInsets.navigationBars
        .asPaddingValues()
        .calculateBottomPadding()
    val safeDrawingPadding = WindowInsets.safeDrawing.asPaddingValues()
    val safeDrawingLeftPadding = safeDrawingPadding.calculateLeftPadding(layoutDirection)
    val safeDrawingRightPadding = safeDrawingPadding.calculateRightPadding(layoutDirection)
    val addWaypointSearchResults = overlayState as? MapOverlayState.AddWaypointSearchResults
    val addWaypointSelected = overlayState as? MapOverlayState.AddWaypointSelected
    val navigationWaypointEditor = overlayState as? MapOverlayState.NavigationWaypointEditor
    val placeDetailsOverlay = overlayState as? MapOverlayState.PlaceDetails
    val searchResultsOverlay = overlayState as? MapOverlayState.SearchResults
    val hasNavigationAlternativesOverlay = overlayState.hasNavigationAlternativesOverlay()
    val navigationAlternativesReady = overlayState.alternativesRoutePreviewState() as? RoutePreviewState.Ready
    val hasAddWaypointOverlay = addWaypointSearchResults != null || addWaypointSelected != null
    val hasRouteEditOverlay = navigationWaypointEditor != null
    val hasSheetOverlay = placeDetailsOverlay != null || searchResultsOverlay != null
    val hasNavigationRouteOverlay = hasAddWaypointOverlay || hasNavigationAlternativesOverlay
    val hasNavigationPreviewOverlay = hasNavigationRouteOverlay || hasRouteEditOverlay || hasSheetOverlay
    val isGuidanceCameraActive = screenState is MapScreenState.Navigating && !hasNavigationPreviewOverlay

    SideEffect {
        cameraState.updateDiagnosticsLogging(shouldLogDiagnostics)
    }

    LaunchedEffect(isGuidanceCameraActive) {
        cameraState.setGuidanceCameraActive(isGuidanceCameraActive)
    }

    val guiding = guidanceState as? GuidanceState.Guiding
    val nextManeuver = guiding?.presentation?.nextManeuver
    val guidancePointIndex = nextManeuver?.guidancePointIndex
    val distanceToManeuverMeters = nextManeuver?.distanceToManeuverMeters
    val maneuverDistanceMeters = distanceToManeuverMeters?.toDouble() ?: Double.MAX_VALUE
    val isOnRoute = guiding?.progress?.routeMatchState == RouteMatchState.ON_ROUTE
    val isManeuverTargetKnown = guidancePointIndex != null && distanceToManeuverMeters != null
    val isManeuverPassed = isManeuverTargetKnown && maneuverDistanceMeters <= GUIDANCE_MANEUVER_PASSED_DISTANCE_METERS
    val isManeuverAhead = maneuverDistanceMeters > GUIDANCE_MANEUVER_PASSED_DISTANCE_METERS
    val isManeuverWithinFocusRange = maneuverDistanceMeters <= GUIDANCE_MANEUVER_FOCUS_DISTANCE_METERS
    val canStartManeuverFocus = isGuidanceCameraActive && isOnRoute && isManeuverTargetKnown
    val shouldStartManeuverFocus = canStartManeuverFocus && isManeuverAhead && isManeuverWithinFocusRange

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
        addWaypointSearchResults,
        addWaypointSelected,
        overlayState,
        panelLayout.widthSizeClass,
        panelLayout.panelWidth,
        panelLayout.panelSide,
        viewportWidthPx,
        viewportHeightPx,
        statusBarHeightPadding,
        navigationBarBottomPadding,
        safeDrawingLeftPadding,
        safeDrawingRightPadding,
        mapRenderScale,
    ) {
        val horizontalBasePadding = maxOf(
            MAP_CAMERA_HORIZONTAL_BASE_PADDING,
            safeDrawingLeftPadding,
            safeDrawingRightPadding,
        )
        val horizontalBasePaddingPx = with(density) { horizontalBasePadding.toPx() }.toInt()
        val splitInsetPx = with(density) { panelLayout.splitHorizontalInset.toPx() }.toInt()
        val panelWidthPx = with(density) { panelLayout.panelWidth.roundToPx() }
        val visibleMapWidthPx = (viewportWidthPx - splitInsetPx).coerceAtLeast(0)
        val (startPaddingPx, endPaddingPx) = panelLayout.resolveHorizontalCameraPaddingPx(
            basePaddingPx = horizontalBasePaddingPx,
            splitInsetPx = splitInsetPx,
        )
        val statusBarHeightPaddingPx = with(density) { statusBarHeightPadding.toPx() }.toInt()
        val navigationBarBottomPaddingPx = with(density) { navigationBarBottomPadding.toPx() }.toInt()
        val splitVerticalPaddingPx = maxOf(
            statusBarHeightPaddingPx,
            navigationBarBottomPaddingPx,
        )
        val topAppBarHeightPx = (uiState.topAppBarHeight * mapRenderScale).roundToInt()
        val navigationCardHeightPx = (uiState.navigationCardHeight * mapRenderScale).roundToInt()
        val topPaddingPx = if (panelLayout.isSplit) {
            splitVerticalPaddingPx
        } else {
            topAppBarHeightPx + statusBarHeightPaddingPx
        }
        val bottomPaddingPx = if (panelLayout.isSplit) {
            splitVerticalPaddingPx
        } else {
            resolveCompactBottomPaddingPx(
                hasSheetOverlay = hasSheetOverlay,
                isNavigating = screenState is MapScreenState.Navigating,
                bottomSheetPeekHeightPx = with(density) { uiState.bottomSheetPeekHeight.toPx() }.toInt(),
                navigationCardHeightPx = navigationCardHeightPx,
            )
        }

        cameraState.updatePadding(
            top = topPaddingPx,
            bottom = bottomPaddingPx,
            start = startPaddingPx,
            end = endPaddingPx,
            guidanceAnchorFraction = if (panelLayout.isSplit) {
                SPLIT_GUIDANCE_ANCHOR_FRACTION_FROM_BOTTOM
            } else {
                null
            },
        )
        if (shouldLogDiagnostics) {
            Napier.i(tag = MAP_CAMERA_LOG_TAG) {
                "Camera padding updated. screen=${screenState.javaClass.simpleName} " +
                    "split=${panelLayout.isSplit} side=${panelLayout.panelSide} " +
                    "viewport=${viewportWidthPx}x$viewportHeightPx panelWidth=$panelWidthPx " +
                    "splitInset=$splitInsetPx visibleMapWidth=$visibleMapWidthPx " +
                    "padding=$startPaddingPx,$topPaddingPx,$endPaddingPx,$bottomPaddingPx"
            }
        }
    }

    LaunchedEffect(
        panelLayout.widthSizeClass,
        panelLayout.panelWidth,
        panelLayout.panelSide,
        viewportWidthPx,
        viewportHeightPx,
    ) {
        cameraState.onPanelLayoutChanged()
    }

    val routeOverviewPoints = remember(
        screenState,
        routePreviewState,
        uiState.isNavigationRoutePreviewing,
        guidanceState.routeOverviewKey(),
        navigationAlternativesReady,
        hasSheetOverlay,
    ) {
        val ready = routePreviewState as? RoutePreviewState.Ready
        when {
            hasSheetOverlay -> null

            screenState is MapScreenState.RoutePreview && ready != null -> ready.routes.flatMap { route ->
                route.overviewPoints()
            }

            screenState is MapScreenState.Navigating && navigationAlternativesReady != null -> {
                navigationAlternativesReady.routes.flatMap { route ->
                    route.overviewPoints()
                }
            }

            screenState is MapScreenState.Navigating && uiState.isNavigationRoutePreviewing -> when (guidanceState) {
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

    val routeOverviewTopPaddingKey = when {
        hasSheetOverlay -> 0
        screenState is MapScreenState.RoutePreview -> uiState.topAppBarHeight
        screenState is MapScreenState.Navigating && navigationAlternativesReady != null -> uiState.topAppBarHeight
        screenState is MapScreenState.Navigating && uiState.isNavigationRoutePreviewing -> uiState.topAppBarHeight
        else -> 0
    }
    val routeOverviewBottomPaddingKey = if (!hasSheetOverlay && screenState is MapScreenState.RoutePreview) {
        uiState.bottomSheetPeekHeight
    } else {
        0.dp
    }
    val shouldUseAlternativesCardPadding = screenState is MapScreenState.Navigating && navigationAlternativesReady != null
    val shouldUseRoutePreviewCardPadding = screenState is MapScreenState.Navigating && uiState.isNavigationRoutePreviewing
    val shouldUseNavigationCardPadding = !hasSheetOverlay && (shouldUseAlternativesCardPadding || shouldUseRoutePreviewCardPadding)
    val routeOverviewNavigationCardHeightKey = if (shouldUseNavigationCardPadding) {
        uiState.navigationCardHeight
    } else {
        0
    }

    // RoutePreview
    LaunchedEffect(
        routeOverviewPoints,
        routeOverviewTopPaddingKey,
        routeOverviewBottomPaddingKey,
        routeOverviewNavigationCardHeightKey,
        panelLayout.widthSizeClass,
        panelLayout.panelWidth,
        panelLayout.panelSide,
        viewportWidthPx,
        viewportHeightPx,
    ) {
        val points = routeOverviewPoints ?: return@LaunchedEffect
        if (shouldLogDiagnostics) {
            Napier.i(tag = MAP_CAMERA_LOG_TAG) {
                "Route overview requested. screen=${screenState.javaClass.simpleName} " +
                    "points=${points.size} viewport=${viewportWidthPx}x$viewportHeightPx " +
                    "topKey=$routeOverviewTopPaddingKey bottomKey=$routeOverviewBottomPaddingKey " +
                    "navCardKey=$routeOverviewNavigationCardHeightKey"
            }
        }
        cameraState.showRouteOverview(points)
    }

    LaunchedEffect(screenState, addWaypointSearchResults, addWaypointSelected, overlayState) {
        when {
            placeDetailsOverlay != null -> {
                cameraState.moveTo(
                    latitude = placeDetailsOverlay.place.latitude,
                    longitude = placeDetailsOverlay.place.longitude,
                    zoom = 18f,
                )
            }

            searchResultsOverlay != null -> {
                cameraState.showSearchResultsOverview(searchResultsOverlay.results)
            }

            else -> {
                when (screenState) {
                    is MapScreenState.Browsing -> {
                        // 復元した手動閲覧位置がある初回のみ自動追従を抑制し、復元位置を維持する
                        if (!cameraState.consumeInitialBrowsingRestore()) {
                            cameraState.followVehicleLocation(vehicleLocationState)
                        }
                    }

                    is MapScreenState.PlaceDetails -> {
                        cameraState.moveTo(
                            latitude = screenState.place.latitude,
                            longitude = screenState.place.longitude,
                            zoom = 18f,
                        )
                    }

                    is MapScreenState.SearchResultsList -> {
                        cameraState.showSearchResultsOverview(screenState.results)
                    }

                    is MapScreenState.RoutePreview -> {
                        // ルートが揃ったタイミングで上の LaunchedEffect がカメラをフィットさせる
                    }

                    is MapScreenState.Navigating -> {
                        when {
                            addWaypointSelected != null -> {
                                cameraState.showSelectedWaypointRouteOverview(addWaypointSelected)
                            }

                            navigationAlternativesReady != null -> {
                                cameraState.showNavigationAlternativesRouteOverview(navigationAlternativesReady)
                            }

                            navigationWaypointEditor != null -> Unit

                            addWaypointSearchResults != null -> {
                                cameraState.showSearchResultsOverview(addWaypointSearchResults.results)
                            }

                            else -> {
                                cameraState.startGuidanceCamera(vehicleLocationState)
                            }
                        }
                    }

                    is MapScreenState.Arrived -> {
                        // TODO
                    }
                }
            }
        }
    }
}

private fun MapCameraState.showSelectedWaypointRouteOverview(selected: MapOverlayState.AddWaypointSelected) {
    val routePreviewState = selected.routePreviewState as? RoutePreviewState.Ready ?: return
    val points = routePreviewState.routes.flatMap { route ->
        route.overviewPoints()
    }
    showRouteOverview(points)
}

private fun MapCameraState.showNavigationAlternativesRouteOverview(routePreviewState: RoutePreviewState.Ready) {
    val points = routePreviewState.routes.flatMap { route ->
        route.overviewPoints()
    }
    showRouteOverview(points)
}

private fun MapCameraState.showSearchResultsOverview(results: List<SearchResultItem>) {
    val points = results.map { result ->
        RoutePoint(
            latitude = result.latitude,
            longitude = result.longitude,
        )
    }

    when (points.size) {
        0 -> Unit
        1 -> moveTo(
            latitude = points.first().latitude,
            longitude = points.first().longitude,
            zoom = 16f,
        )

        else -> showRouteOverview(points)
    }
}

private fun resolveCompactBottomPaddingPx(
    hasSheetOverlay: Boolean,
    isNavigating: Boolean,
    bottomSheetPeekHeightPx: Int,
    navigationCardHeightPx: Int,
): Int {
    return when {
        hasSheetOverlay -> bottomSheetPeekHeightPx
        isNavigating -> navigationCardHeightPx
        else -> bottomSheetPeekHeightPx
    }
}

private fun RouteDetail.overviewPoints(): List<RoutePoint> = buildList {
    addAll(geometry)
    add(origin)
    addAll(intermediateWaypoints)
    add(destination)
}

private fun MapOverlayState.hasNavigationAlternativesOverlay(): Boolean {
    return when (this) {
        is MapOverlayState.AddWaypointAlternatives,
        is MapOverlayState.NavigationAlternatives,
        -> true

        MapOverlayState.AddWaypointSearch,
        is MapOverlayState.AddWaypointSearchResults,
        is MapOverlayState.AddWaypointSelected,
        is MapOverlayState.NavigationWaypointEditor,
        is MapOverlayState.PlaceDetails,
        is MapOverlayState.SearchResults,
        MapOverlayState.None,
        is MapOverlayState.WaypointSearch,
        -> false
    }
}

private fun MapOverlayState.alternativesRoutePreviewState(): RoutePreviewState? {
    return when (this) {
        is MapOverlayState.AddWaypointAlternatives -> routePreviewState
        is MapOverlayState.NavigationAlternatives -> routePreviewState
        MapOverlayState.AddWaypointSearch,
        is MapOverlayState.AddWaypointSearchResults,
        is MapOverlayState.AddWaypointSelected,
        is MapOverlayState.NavigationWaypointEditor,
        is MapOverlayState.PlaceDetails,
        is MapOverlayState.SearchResults,
        MapOverlayState.None,
        is MapOverlayState.WaypointSearch,
        -> null
    }
}

/** 案内地点フォーカスを開始する残距離（m）。 */
private const val GUIDANCE_MANEUVER_FOCUS_DISTANCE_METERS = 100

/** 案内地点を通過済みと扱う残距離（m）。 */
private const val GUIDANCE_MANEUVER_PASSED_DISTANCE_METERS = 0

/** 地図カメラ padding の左右に最低限確保する余白。 */
private val MAP_CAMERA_HORIZONTAL_BASE_PADDING = 24.dp

/** 分割案内で自車を画面下端から置く割合。 */
private const val SPLIT_GUIDANCE_ANCHOR_FRACTION_FROM_BOTTOM = 0.25f

/** Map camera 周辺の検証ログ用タグ。 */
private const val MAP_CAMERA_LOG_TAG = "OneNaviMapCamera"

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
