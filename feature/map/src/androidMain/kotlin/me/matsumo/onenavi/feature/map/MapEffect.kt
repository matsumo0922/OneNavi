package me.matsumo.onenavi.feature.map

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
    onRouteSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (screenState) {
        is MapScreenState.Browsing -> Unit

        is MapScreenState.PlaceDetails -> {
            PlaceDetailsEffect(
                screenState = screenState,
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
                onRouteSelected = onRouteSelected,
            )
        }
        is MapScreenState.Navigating -> {
            NavigationEffect(
                modifier = modifier,
                guidanceState = guidanceState,
                googleMap = googleMap,
                cameraZoom = cameraState.cameraState.zoom,
                topAppBarHeightPx = topAppBarHeightPx,
                bottomSheetPeekHeight = bottomSheetPeekHeight,
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

    if (overlayState is MapOverlayState.AddWaypointSelected) {
        AddWaypointSelectedEffect(
            overlayState = overlayState,
            googleMap = googleMap,
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
 */
@Composable
private fun AddWaypointSelectedEffect(
    overlayState: MapOverlayState.AddWaypointSelected,
    googleMap: GoogleMap,
) {
    MapMarker(
        googleMap = googleMap,
        latitude = overlayState.place.latitude,
        longitude = overlayState.place.longitude,
        title = overlayState.place.name,
        zIndex = WAYPOINT_CANDIDATE_MARKER_Z_INDEX,
    )

    val routePreviewState = overlayState.routePreviewState as? RoutePreviewState.Ready ?: return

    routePreviewState.routes.forEachIndexed { routeIndex, route ->
        RoutePolylineEffect(
            googleMap = googleMap,
            route = route,
            isSelected = routeIndex == routePreviewState.selectedIndex,
        )
    }
}

/**
 * 地点詳細画面の marker を描画する。
 *
 * @param screenState 地点詳細画面 state
 * @param googleMap marker 描画先の GoogleMap
 */
@Composable
private fun PlaceDetailsEffect(
    screenState: MapScreenState.PlaceDetails,
    googleMap: GoogleMap,
) {
    MapMarker(
        googleMap = googleMap,
        latitude = screenState.place.latitude,
        longitude = screenState.place.longitude,
        title = screenState.place.name,
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
 * ルート Preview 画面の waypoint marker、route polyline、callout を描画する。
 *
 * @param screenState ルート Preview 画面 state
 * @param routePreviewState Preview 期のルート候補状態
 * @param googleMap overlay 描画先の GoogleMap
 * @param topAppBarHeightPx callout が避ける上部バー高さ
 * @param bottomSheetPeekHeight callout が避ける bottom sheet 高さ
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

    for (waypoint in screenState.waypoints.drop(1)) {
        MapMarker(
            googleMap = googleMap,
            latitude = waypoint.latitude,
            longitude = waypoint.longitude,
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
 * @param modifier callout overlay 用 modifier
 */
@Composable
private fun NavigationEffect(
    guidanceState: GuidanceState,
    googleMap: GoogleMap,
    cameraZoom: Float,
    topAppBarHeightPx: Int,
    bottomSheetPeekHeight: Dp,
    modifier: Modifier = Modifier,
) {
    val guidanceRoute = guidanceState.routeForMapOverlay() ?: return

    RoutePolylineEffect(
        googleMap = googleMap,
        route = guidanceRoute.route,
        isSelected = true,
    )

    MapOriginMarker(
        googleMap = googleMap,
        latitude = guidanceRoute.route.origin.latitude,
        longitude = guidanceRoute.route.origin.longitude,
        zIndex = ORIGIN_MARKER_Z_INDEX,
    )

    MapMarker(
        googleMap = googleMap,
        latitude = guidanceRoute.route.destination.latitude,
        longitude = guidanceRoute.route.destination.longitude,
        zIndex = DESTINATION_MARKER_Z_INDEX,
    )

    if (guidanceState is GuidanceState.Guiding) {
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

/** 出発地 marker の zIndex。 */
private const val ORIGIN_MARKER_Z_INDEX = 10_500f

/** 目的地 marker の zIndex。 */
private const val DESTINATION_MARKER_Z_INDEX = 10_500f

/** 検索結果 marker の zIndex 起点。 */
private const val SEARCH_RESULT_MARKER_Z_INDEX = 11_000f

/** waypoint 候補 marker の zIndex。 */
private const val WAYPOINT_CANDIDATE_MARKER_Z_INDEX = 11_500f

/** 自車 marker の zIndex。 */
private const val VEHICLE_PUCK_Z_INDEX = 12_000f
