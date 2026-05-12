package me.matsumo.onenavi.feature.map.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import me.matsumo.onenavi.feature.map.state.MapCameraState
import me.matsumo.onenavi.feature.map.state.MapUiEvent
import me.matsumo.onenavi.feature.map.state.MapUiState

@Composable
internal fun MapRoutePreview(
    cameraState: MapCameraState,
    uiState: MapUiState,
    onUiEvent: (MapUiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.TopCenter,
    ) {
    }
}
