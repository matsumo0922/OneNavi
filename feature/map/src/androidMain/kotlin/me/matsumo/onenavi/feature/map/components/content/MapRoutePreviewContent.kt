package me.matsumo.onenavi.feature.map.components.content

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import me.matsumo.onenavi.feature.map.components.topappbar.MapRoutePreviewTopAppBar
import me.matsumo.onenavi.feature.map.state.MapScreenState
import me.matsumo.onenavi.feature.map.state.MapUiEvent
import me.matsumo.onenavi.feature.map.state.MapUiState

@Composable
internal fun MapRoutePreviewContent(
    screenState: MapScreenState.RoutePreview,
    uiState: MapUiState,
    isSheetOverlayVisible: Boolean,
    onUiEvent: (MapUiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.TopCenter,
    ) {
        MapRoutePreviewTopAppBar(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp)
                .onGloballyPositioned {
                    onUiEvent(MapUiEvent.OnTopAppBarHeightChanged(it.size.height))
                },
            waypoints = screenState.waypoints,
            waypointEditResult = uiState.routeWaypointEditResult,
            isInteractionEnabled = !isSheetOverlayVisible,
            onUiEvent = onUiEvent,
        )
    }
}
