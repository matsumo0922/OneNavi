package me.matsumo.onenavi.feature.map

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.google.android.gms.maps.GoogleMap
import kotlinx.collections.immutable.persistentListOf
import me.matsumo.onenavi.core.model.RouteDetail
import me.matsumo.onenavi.core.navigation.newguidance.model.GuidanceState
import me.matsumo.onenavi.core.navigation.newguidance.model.RoutePreviewState
import me.matsumo.onenavi.feature.map.components.MapMarker
import me.matsumo.onenavi.feature.map.components.MapNumberedMarker
import me.matsumo.onenavi.feature.map.components.MapPolyline
import me.matsumo.onenavi.feature.map.components.MapPolylineStyle
import me.matsumo.onenavi.feature.map.components.MapVehiclePoseEffect
import me.matsumo.onenavi.feature.map.components.callout.MapGuidanceManeuverCallOutMarkerEffect
import me.matsumo.onenavi.feature.map.components.callout.MapRoutePreviewCallOutMarkerEffect
import me.matsumo.onenavi.feature.map.state.MapCameraState
import me.matsumo.onenavi.feature.map.state.MapScreenState
import me.matsumo.onenavi.feature.map.state.VehicleLocationState

/**
 * GoogleMap 上に画面状態に応じた overlay を描画する。
 *
 * @param screenState 現在の地図画面状態
 * @param routePreviewState Preview 期のルート候補状態
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
                topAppBarHeightPx = topAppBarHeightPx,
                bottomSheetPeekHeight = bottomSheetPeekHeight,
            )
        }
        is MapScreenState.Arrived -> Unit
    }

    if (vehicleLocationState != null) {
        val guiding = guidanceState as? GuidanceState.Guiding
        val routeGeometry = guiding
            ?.route
            ?.geometry
            ?: persistentListOf()

        MapVehiclePoseEffect(
            googleMap = googleMap,
            cameraState = cameraState,
            vehicleLocationState = vehicleLocationState,
            routeKey = guiding?.route?.id,
            routeGeometry = routeGeometry,
            zIndex = VEHICLE_PUCK_Z_INDEX,
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
    screenState.results.forEachIndexed { index, result ->
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
    for (waypoint in screenState.waypoints.drop(1)) {
        MapMarker(
            googleMap = googleMap,
            latitude = waypoint.latitude,
            longitude = waypoint.longitude,
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
 * @param topAppBarHeightPx callout が避ける上部バー高さ
 * @param bottomSheetPeekHeight callout が避ける bottom sheet 高さ
 * @param modifier callout overlay 用 modifier
 */
@Composable
private fun NavigationEffect(
    guidanceState: GuidanceState,
    googleMap: GoogleMap,
    topAppBarHeightPx: Int,
    bottomSheetPeekHeight: Dp,
    modifier: Modifier = Modifier,
) {
    val guiding = guidanceState as? GuidanceState.Guiding ?: return

    RoutePolylineEffect(
        googleMap = googleMap,
        route = guiding.route,
        isSelected = true,
    )

    MapGuidanceManeuverCallOutMarkerEffect(
        modifier = modifier,
        googleMap = googleMap,
        guidanceState = guidanceState,
        topAppBarHeightPx = topAppBarHeightPx,
        bottomSheetPeekHeight = bottomSheetPeekHeight,
    )
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

/** 検索結果 marker の zIndex 起点。 */
private const val SEARCH_RESULT_MARKER_Z_INDEX = 11_000f

/** 自車 marker の zIndex。 */
private const val VEHICLE_PUCK_Z_INDEX = 12_000f
