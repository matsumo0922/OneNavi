package me.matsumo.onenavi.feature.map

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import me.matsumo.onenavi.core.model.RoutePoint
import me.matsumo.onenavi.core.navigation.newguidance.model.RoutePreviewState
import me.matsumo.onenavi.feature.map.state.MapCameraState
import me.matsumo.onenavi.feature.map.state.MapScreenState
import me.matsumo.onenavi.feature.map.state.MapUiState
import me.matsumo.onenavi.feature.map.state.VehicleLocationState

/**
 * 画面状態の変化を GoogleMap カメラ操作へ変換する。
 *
 * @param uiState map screen の UI state
 * @param screenState 現在の地図画面状態
 * @param routePreviewState Preview 期のルート候補状態
 * @param vehicleLocationState 最新の自車位置
 * @param cameraState カメラ操作を保持する state holder
 */
@Composable
internal fun MapCameraEffect(
    uiState: MapUiState,
    screenState: MapScreenState,
    routePreviewState: RoutePreviewState,
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

    LaunchedEffect(uiState.bottomSheetPeekHeight, uiState.topAppBarHeight, screenState) {
        val top = uiState.topAppBarHeight + with(density) { statusBarHeightPadding.toPx() }
        val bottom = with(density) { uiState.bottomSheetPeekHeight.toPx() }
        val horizontal = with(density) { 24.dp.toPx() }

        cameraState.updatePadding(
            top = top.toInt(),
            bottom = bottom.toInt(),
            start = horizontal.toInt(),
            end = horizontal.toInt(),
        )
    }

    val routeOverviewPoints = remember(screenState, routePreviewState) {
        val ready = routePreviewState as? RoutePreviewState.Ready
        if (screenState is MapScreenState.RoutePreview && ready != null) {
            ready.routes.flatMap { it.geometry }
        } else {
            null
        }
    }

    // RoutePreview
    LaunchedEffect(routeOverviewPoints, uiState.topAppBarHeight, uiState.bottomSheetPeekHeight) {
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
