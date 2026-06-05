package me.matsumo.onenavi.feature.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import com.google.android.gms.maps.GoogleMap
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import me.matsumo.onenavi.core.model.RouteDetail
import me.matsumo.onenavi.core.model.SearchResultItem
import me.matsumo.onenavi.core.navigation.newguidance.model.GuidanceState
import me.matsumo.onenavi.core.navigation.newguidance.model.RoutePreviewState
import me.matsumo.onenavi.feature.map.components.MapGuidanceManeuverArrowEffect
import me.matsumo.onenavi.feature.map.components.MapMarker
import me.matsumo.onenavi.feature.map.components.MapNumberedMarker
import me.matsumo.onenavi.feature.map.components.MapOriginMarker
import me.matsumo.onenavi.feature.map.components.MapPolyline
import me.matsumo.onenavi.feature.map.components.MapPolylineStyle
import me.matsumo.onenavi.feature.map.components.MapVehiclePoseEffect
import me.matsumo.onenavi.feature.map.components.MapWaypointNumberedMarker
import me.matsumo.onenavi.feature.map.components.callout.MapGuidanceManeuverCallOutMarkerEffect
import me.matsumo.onenavi.feature.map.components.callout.MapRoutePreviewCallOutMarkerEffect
import me.matsumo.onenavi.feature.map.state.MapCameraState
import me.matsumo.onenavi.feature.map.state.MapOverlayState
import me.matsumo.onenavi.feature.map.state.MapScreenState
import me.matsumo.onenavi.feature.map.state.VehicleLocationState

/**
 * GoogleMap 上に画面状態に応じた overlay を描画する。
 *
 * @param screenState 現在の地図画面状態
 * @param routePreviewState Preview 期のルート候補状態
 * @param overlayState 地図画面上に重ねるオーバーレイ状態
 * @param guidanceState Guidance 期の案内状態
 * @param vehicleLocationState 最新の自車位置
 * @param googleMap overlay 描画先の GoogleMap
 * @param cameraState 自車 pose を追従カメラへ反映する state holder
 * @param topAppBarHeightPx ルート選択 callout が避ける上部バー高さ
 * @param bottomSheetPeekHeight ルート選択 callout が避ける bottom sheet 高さ
 * @param navigationCardHeightPx 案内中 callout が避ける下部カード高さ
 * @param horizontalViewportPadding callout が避ける左右の画面外・UI 帯 padding
 * @param onRouteSelected ルート候補が選択された時の callback
 * @param modifier callout overlay 用 modifier
 */
@Composable
internal fun MapEffect(
    screenState: MapScreenState,
    routePreviewState: RoutePreviewState,
    overlayState: MapOverlayState,
    guidanceState: GuidanceState,
    vehicleLocationState: VehicleLocationState?,
    googleMap: GoogleMap,
    cameraState: MapCameraState,
    topAppBarHeightPx: Int,
    bottomSheetPeekHeight: Dp,
    navigationCardHeightPx: Int,
    horizontalViewportPadding: Dp,
    onRouteSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val navigationCardHeight = with(density) { navigationCardHeightPx.toDp() }

    when (screenState) {
        is MapScreenState.Browsing -> Unit

        is MapScreenState.PlaceDetails -> {
            PlaceDetailsEffect(
                place = screenState.place,
                googleMap = googleMap,
            )
        }

        is MapScreenState.SearchResultsList -> {
            SearchResultsListEffect(
                screenState = screenState,
                googleMap = googleMap,
            )
        }

        is MapScreenState.RoutePreview -> {
            RoutePreviewEffect(
                modifier = modifier,
                screenState = screenState,
                routePreviewState = routePreviewState,
                googleMap = googleMap,
                topAppBarHeightPx = topAppBarHeightPx,
                bottomSheetPeekHeight = bottomSheetPeekHeight,
                horizontalViewportPadding = horizontalViewportPadding,
                onRouteSelected = onRouteSelected,
            )
        }
        is MapScreenState.Navigating -> {
            val addWaypointSelectedState = overlayState as? MapOverlayState.AddWaypointSelected
            val alternativesRoutePreviewState = overlayState.alternativesRoutePreviewState()
            val shouldShowAddWaypointOverlay = addWaypointSelectedState != null
            val shouldShowAddWaypointRoutePreview = addWaypointSelectedState?.routePreviewState is RoutePreviewState.Ready
            val shouldShowNavigationAlternativesRoutes = alternativesRoutePreviewState is RoutePreviewState.Ready
            val shouldSuppressGuidanceRouteOverlay = shouldShowAddWaypointOverlay || shouldShowNavigationAlternativesRoutes
            val shouldSuppressGuidanceWaypointMarkers = shouldShowAddWaypointRoutePreview || shouldShowNavigationAlternativesRoutes

            NavigationEffect(
                modifier = modifier,
                guidanceState = guidanceState,
                googleMap = googleMap,
                cameraZoom = cameraState.cameraState.zoom,
                topAppBarHeightPx = topAppBarHeightPx,
                bottomSheetPeekHeight = bottomSheetPeekHeight,
                horizontalViewportPadding = horizontalViewportPadding,
                shouldSuppressGuidanceRouteOverlay = shouldSuppressGuidanceRouteOverlay,
                shouldSuppressGuidanceWaypointMarkers = shouldSuppressGuidanceWaypointMarkers,
                shouldSuppressGuidanceEndPointMarkers = shouldShowNavigationAlternativesRoutes,
            )
        }
        is MapScreenState.Arrived -> Unit
    }

    if (overlayState is MapOverlayState.AddWaypointSearchResults) {
        SearchResultsMarkersEffect(
            results = overlayState.results,
            googleMap = googleMap,
        )
    }

    if (overlayState is MapOverlayState.PlaceDetails) {
        PlaceDetailsEffect(
            place = overlayState.place,
            googleMap = googleMap,
        )
    }

    if (overlayState is MapOverlayState.SearchResults) {
        SearchResultsMarkersEffect(
            results = overlayState.results,
            googleMap = googleMap,
        )
    }

    if (overlayState is MapOverlayState.AddWaypointSelected) {
        val guidanceWaypointCount = guidanceState
            .routeForMapOverlay()
            ?.route
            ?.intermediateWaypoints
            ?.size
            ?: 0

        AddWaypointSelectedEffect(
            overlayState = overlayState,
            googleMap = googleMap,
            guidanceWaypointCount = guidanceWaypointCount,
        )
    }

    val navigationAlternativesReady = overlayState.alternativesRoutePreviewState() as? RoutePreviewState.Ready
    if (navigationAlternativesReady != null) {
        NavigationAlternativesEffect(
            modifier = modifier,
            routePreviewState = navigationAlternativesReady,
            googleMap = googleMap,
            topAppBarHeightPx = topAppBarHeightPx,
            bottomCardHeight = navigationCardHeight,
            horizontalViewportPadding = horizontalViewportPadding,
        )
    }

    if (vehicleLocationState != null) {
        val guidanceRoute = guidanceState.routeForMapOverlay()
        val routeGeometry = guidanceRoute
            ?.route
            ?.geometry
            ?: persistentListOf()

        MapVehiclePoseEffect(
            googleMap = googleMap,
            cameraState = cameraState,
            vehicleLocationState = vehicleLocationState,
            routeKey = guidanceRoute?.route?.id,
            routeGeometry = routeGeometry,
            zIndex = VEHICLE_PUCK_Z_INDEX,
        )
    }
}

/**
 * ナビゲーション中に選択した waypoint 候補の仮ルートを描画する。
 *
 * @param overlayState 選択地点と仮ルート探索状態
 * @param googleMap overlay 描画先の GoogleMap
 * @param guidanceWaypointCount 現在案内中ルートが持つ経由地数
 */
@Composable
private fun AddWaypointSelectedEffect(
    overlayState: MapOverlayState.AddWaypointSelected,
    googleMap: GoogleMap,
    guidanceWaypointCount: Int,
) {
    val routePreviewState = overlayState.routePreviewState as? RoutePreviewState.Ready

    if (routePreviewState == null) {
        MapWaypointNumberedMarker(
            googleMap = googleMap,
            latitude = overlayState.place.latitude,
            longitude = overlayState.place.longitude,
            number = guidanceWaypointCount + 1,
            title = overlayState.place.name,
            zIndex = WAYPOINT_CANDIDATE_MARKER_Z_INDEX,
        )
        return
    }

    RouteIntermediateWaypointMarkersEffect(
        googleMap = googleMap,
        route = routePreviewState.selectedRoute,
        zIndex = WAYPOINT_CANDIDATE_MARKER_Z_INDEX,
    )

    routePreviewState.routes.forEachIndexed { routeIndex, route ->
        RoutePolylineEffect(
            googleMap = googleMap,
            route = route,
            isSelected = routeIndex == routePreviewState.selectedIndex,
        )
    }
}

/**
 * ナビゲーション中に再探索した代替ルート候補を描画する。
 *
 * @param routePreviewState 代替ルート候補
 * @param googleMap overlay 描画先の GoogleMap
 * @param topAppBarHeightPx callout が避ける上部バー高さ
 * @param bottomCardHeight callout が避ける下部カード高さ
 * @param horizontalViewportPadding callout が避ける左右の画面外・UI 帯 padding
 * @param modifier callout overlay 用 modifier
 */
@Composable
private fun NavigationAlternativesEffect(
    routePreviewState: RoutePreviewState.Ready,
    googleMap: GoogleMap,
    topAppBarHeightPx: Int,
    bottomCardHeight: Dp,
    horizontalViewportPadding: Dp,
    modifier: Modifier = Modifier,
) {
    val selectedRoute = routePreviewState.selectedRoute

    MapOriginMarker(
        googleMap = googleMap,
        latitude = selectedRoute.origin.latitude,
        longitude = selectedRoute.origin.longitude,
        zIndex = ORIGIN_MARKER_Z_INDEX,
    )

    RouteIntermediateWaypointMarkersEffect(
        googleMap = googleMap,
        route = selectedRoute,
        zIndex = ROUTE_WAYPOINT_MARKER_Z_INDEX,
    )

    MapMarker(
        googleMap = googleMap,
        latitude = selectedRoute.destination.latitude,
        longitude = selectedRoute.destination.longitude,
        zIndex = DESTINATION_MARKER_Z_INDEX,
    )

    routePreviewState.routes.forEachIndexed { routeIndex, route ->
        RoutePolylineEffect(
            googleMap = googleMap,
            route = route,
            isSelected = routeIndex == routePreviewState.selectedIndex,
        )
    }

    MapRoutePreviewCallOutMarkerEffect(
        modifier = modifier,
        googleMap = googleMap,
        routePreviewState = routePreviewState,
        topAppBarHeightPx = topAppBarHeightPx,
        bottomSheetPeekHeight = bottomCardHeight,
        horizontalViewportPadding = horizontalViewportPadding,
        onRouteSelected = {},
    )
}

/**
 * 地点詳細画面の marker を描画する。
 *
 * @param place 地点詳細の地点
 * @param googleMap marker 描画先の GoogleMap
 */
@Composable
private fun PlaceDetailsEffect(
    place: SearchResultItem,
    googleMap: GoogleMap,
) {
    MapMarker(
        googleMap = googleMap,
        latitude = place.latitude,
        longitude = place.longitude,
        title = place.name,
    )
}

/**
 * 検索結果一覧の numbered marker を描画する。
 *
 * @param screenState 検索結果一覧画面 state
 * @param googleMap marker 描画先の GoogleMap
 */
@Composable
private fun SearchResultsListEffect(
    screenState: MapScreenState.SearchResultsList,
    googleMap: GoogleMap,
) {
    SearchResultsMarkersEffect(
        results = screenState.results,
        googleMap = googleMap,
    )
}

/**
 * 検索結果一覧の numbered marker を描画する。
 *
 * @param results 検索結果一覧
 * @param googleMap marker 描画先の GoogleMap
 */
@Composable
private fun SearchResultsMarkersEffect(
    results: ImmutableList<SearchResultItem>,
    googleMap: GoogleMap,
) {
    results.forEachIndexed { index, result ->
        MapNumberedMarker(
            googleMap = googleMap,
            latitude = result.latitude,
            longitude = result.longitude,
            number = index + 1,
            title = result.name,
            zIndex = SEARCH_RESULT_MARKER_Z_INDEX + index,
        )
    }
}

/**
 * ルートが持つ中間経由地 marker を番号付きで描画する。
 *
 * @param googleMap marker 描画先の GoogleMap
 * @param route 描画対象 route
 * @param zIndex marker の zIndex
 */
@Composable
private fun RouteIntermediateWaypointMarkersEffect(
    googleMap: GoogleMap,
    route: RouteDetail,
    zIndex: Float,
) {
    route.intermediateWaypoints.forEachIndexed { index, waypoint ->
        MapWaypointNumberedMarker(
            googleMap = googleMap,
            latitude = waypoint.latitude,
            longitude = waypoint.longitude,
            number = index + 1,
            zIndex = zIndex,
        )
    }
}

/**
 * ルート Preview 画面の waypoint marker、route polyline、callout を描画する。
 *
 * @param screenState ルート Preview 画面 state
 * @param routePreviewState Preview 期のルート候補状態
 * @param googleMap overlay 描画先の GoogleMap
 * @param topAppBarHeightPx callout が避ける上部バー高さ
 * @param bottomSheetPeekHeight callout が避ける bottom sheet 高さ
 * @param horizontalViewportPadding callout が避ける左右の画面外・UI 帯 padding
 * @param onRouteSelected ルート候補が選択された時の callback
 * @param modifier callout overlay 用 modifier
 */
@Composable
private fun RoutePreviewEffect(
    screenState: MapScreenState.RoutePreview,
    routePreviewState: RoutePreviewState,
    googleMap: GoogleMap,
    topAppBarHeightPx: Int,
    bottomSheetPeekHeight: Dp,
    horizontalViewportPadding: Dp,
    onRouteSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val originWaypoint = screenState.waypoints.firstOrNull()

    if (originWaypoint != null) {
        MapOriginMarker(
            googleMap = googleMap,
            latitude = originWaypoint.latitude,
            longitude = originWaypoint.longitude,
            zIndex = ORIGIN_MARKER_Z_INDEX,
        )
    }

    val routeWaypoints = screenState.waypoints.drop(1)
    val intermediateWaypoints = routeWaypoints.dropLast(1)

    intermediateWaypoints.forEachIndexed { index, waypoint ->
        MapWaypointNumberedMarker(
            googleMap = googleMap,
            latitude = waypoint.latitude,
            longitude = waypoint.longitude,
            number = index + 1,
            zIndex = ROUTE_WAYPOINT_MARKER_Z_INDEX,
        )
    }

    val destinationWaypoint = routeWaypoints.lastOrNull()
    if (destinationWaypoint != null) {
        MapMarker(
            googleMap = googleMap,
            latitude = destinationWaypoint.latitude,
            longitude = destinationWaypoint.longitude,
            zIndex = DESTINATION_MARKER_Z_INDEX,
        )
    }

    if (routePreviewState is RoutePreviewState.Ready) {
        for ((routeIndex, route) in routePreviewState.routes.withIndex()) {
            RoutePolylineEffect(
                googleMap = googleMap,
                route = route,
                isSelected = routeIndex == routePreviewState.selectedIndex,
            )
        }
    }

    MapRoutePreviewCallOutMarkerEffect(
        modifier = modifier,
        googleMap = googleMap,
        routePreviewState = routePreviewState as? RoutePreviewState.Ready,
        topAppBarHeightPx = topAppBarHeightPx,
        bottomSheetPeekHeight = bottomSheetPeekHeight,
        horizontalViewportPadding = horizontalViewportPadding,
        onRouteSelected = onRouteSelected,
    )
}

/**
 * Navigation 画面の route polyline と案内地点 callout を描画する。
 *
 * @param guidanceState Guidance 期の案内状態
 * @param googleMap overlay 描画先の GoogleMap
 * @param cameraZoom 現在の GoogleMap zoom
 * @param topAppBarHeightPx callout が避ける上部バー高さ
 * @param bottomSheetPeekHeight callout が避ける bottom sheet 高さ
 * @param horizontalViewportPadding callout が避ける左右の画面外・UI 帯 padding
 * @param shouldSuppressGuidanceRouteOverlay 案内中 route overlay を一時的に非表示にするか
 * @param shouldSuppressGuidanceWaypointMarkers 案内中 waypoint marker を一時的に非表示にするか
 * @param shouldSuppressGuidanceEndPointMarkers 案内中の出発地・目的地 marker を一時的に非表示にするか
 * @param modifier callout overlay 用 modifier
 */
@Composable
private fun NavigationEffect(
    guidanceState: GuidanceState,
    googleMap: GoogleMap,
    cameraZoom: Float,
    topAppBarHeightPx: Int,
    bottomSheetPeekHeight: Dp,
    horizontalViewportPadding: Dp,
    shouldSuppressGuidanceRouteOverlay: Boolean,
    shouldSuppressGuidanceWaypointMarkers: Boolean,
    shouldSuppressGuidanceEndPointMarkers: Boolean,
    modifier: Modifier = Modifier,
) {
    val guidanceRoute = guidanceState.routeForMapOverlay() ?: return

    if (!shouldSuppressGuidanceRouteOverlay) {
        RoutePolylineEffect(
            googleMap = googleMap,
            route = guidanceRoute.route,
            isSelected = true,
        )
    }

    if (!shouldSuppressGuidanceEndPointMarkers) {
        MapOriginMarker(
            googleMap = googleMap,
            latitude = guidanceRoute.route.origin.latitude,
            longitude = guidanceRoute.route.origin.longitude,
            zIndex = ORIGIN_MARKER_Z_INDEX,
        )
    }

    if (!shouldSuppressGuidanceWaypointMarkers) {
        RouteIntermediateWaypointMarkersEffect(
            googleMap = googleMap,
            route = guidanceRoute.route,
            zIndex = ROUTE_WAYPOINT_MARKER_Z_INDEX,
        )
    }

    if (!shouldSuppressGuidanceEndPointMarkers) {
        MapMarker(
            googleMap = googleMap,
            latitude = guidanceRoute.route.destination.latitude,
            longitude = guidanceRoute.route.destination.longitude,
            zIndex = DESTINATION_MARKER_Z_INDEX,
        )
    }

    if (guidanceState is GuidanceState.Guiding && !shouldSuppressGuidanceRouteOverlay) {
        MapGuidanceManeuverArrowEffect(
            googleMap = googleMap,
            guidanceState = guidanceState,
            cameraZoom = cameraZoom,
        )

        MapGuidanceManeuverCallOutMarkerEffect(
            modifier = modifier,
            googleMap = googleMap,
            guidanceState = guidanceState,
            topAppBarHeightPx = topAppBarHeightPx,
            bottomSheetPeekHeight = bottomSheetPeekHeight,
            horizontalViewportPadding = horizontalViewportPadding,
        )
    }
}

/**
 * route geometry を road class / congestion ごとに塗り分けて描画する。
 *
 * @param googleMap polyline 描画先の GoogleMap
 * @param route 描画対象 route
 * @param isSelected 選択中 route として描画するか
 */
@Composable
private fun RoutePolylineEffect(
    googleMap: GoogleMap,
    route: RouteDetail,
    isSelected: Boolean,
) {
    MapPolyline(
        googleMap = googleMap,
        points = route.geometry,
        style = if (isSelected) MapPolylineStyle.Selected else MapPolylineStyle.Unselected,
        roadClassSegments = if (isSelected) route.roadClassSegments else persistentListOf(),
        congestionSegments = if (isSelected) route.congestionSegments else persistentListOf(),
    )
}

/**
 * 地図 overlay に使う案内ルート。
 *
 * @param route 描画対象 route
 */
@Immutable
private data class GuidanceOverlayRoute(
    val route: RouteDetail,
)

/**
 * 案内中または再探索中に地図へ表示すべきルートを返す。
 *
 * @return 表示対象 route。案内していなければ null
 */
private fun GuidanceState.routeForMapOverlay(): GuidanceOverlayRoute? = when (this) {
    is GuidanceState.Guiding -> GuidanceOverlayRoute(route = route)
    is GuidanceState.Rerouting -> GuidanceOverlayRoute(route = previousRoute)
    GuidanceState.Arrived,
    is GuidanceState.Failed,
    GuidanceState.Idle,
    -> null
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

/** 出発地 marker の zIndex。 */
private const val ORIGIN_MARKER_Z_INDEX = 10_500f

/** 目的地 marker の zIndex。 */
private const val DESTINATION_MARKER_Z_INDEX = 10_500f

/** ルート Preview の経由地 marker の zIndex。 */
private const val ROUTE_WAYPOINT_MARKER_Z_INDEX = 10_500f

/** 検索結果 marker の zIndex 起点。 */
private const val SEARCH_RESULT_MARKER_Z_INDEX = 11_000f

/** waypoint 候補 marker の zIndex。 */
private const val WAYPOINT_CANDIDATE_MARKER_Z_INDEX = 11_500f

/** 自車 marker の zIndex。 */
private const val VEHICLE_PUCK_Z_INDEX = 12_000f
