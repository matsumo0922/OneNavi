package me.matsumo.onenavi.feature.map.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import me.matsumo.onenavi.feature.map.components.topappbar.MapTopAppBar
import me.matsumo.onenavi.feature.map.state.MapCameraState
import me.matsumo.onenavi.feature.map.state.MapUiEvent
import me.matsumo.onenavi.feature.map.state.MapUiState

@Composable
internal fun MapBrowsingContent(
    cameraState: MapCameraState,
    uiState: MapUiState,
    onUiEvent: (MapUiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.TopCenter,
    ) {
        MapTopAppBar(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .onGloballyPositioned {
                    onUiEvent(MapUiEvent.OnTopAppBarHeightChanged(it.size.height))
                },
            cameraState = cameraState,
            suggestions = uiState.suggestions,
            histories = uiState.histories,
            selectedResult = uiState.selectedResult,
            onUiEvent = onUiEvent,
        )
    }
}
