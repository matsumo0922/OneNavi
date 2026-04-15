package me.matsumo.onenavi.feature.home.map.components.navi

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationEventHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.mapbox.maps.EdgeInsets
import com.mapbox.navigation.ui.maps.camera.state.NavigationCameraState
import me.matsumo.onenavi.core.navigation.CameraManager
import me.matsumo.onenavi.core.navigation.GuidanceSessionManager

@Composable
internal fun HomeMapNaviContent(
    guidanceSessionManager: GuidanceSessionManager,
    cameraManager: CameraManager,
    bottomSheetPeekHeightPx: Float,
    onNavigationStopped: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val navigationState = rememberNavigationEventState(NavigationEventInfo.None)

    val guidanceUiState by guidanceSessionManager.guidanceUiState.collectAsStateWithLifecycle()
    val cameraState by cameraManager.cameraState.collectAsStateWithLifecycle()
    var maneuverPanelBottomPx by remember { mutableFloatStateOf(0f) }

    NavigationEventHandler(navigationState) {
        onNavigationStopped()
    }

    DisposableEffect(cameraManager) {
        onDispose {
            cameraManager.clearNavigationPadding()
        }
    }

    val currentManeuver = guidanceUiState.currentManeuver
    val topOverlayBottomPx = if (currentManeuver != null) maneuverPanelBottomPx else 0f

    LaunchedEffect(topOverlayBottomPx, bottomSheetPeekHeightPx) {
        val followingPadding = EdgeInsets(topOverlayBottomPx.toDouble(), 0.0, bottomSheetPeekHeightPx.toDouble(), 0.0)
        val overviewPadding = EdgeInsets(topOverlayBottomPx.toDouble(), 0.0, bottomSheetPeekHeightPx.toDouble(), 0.0)

        cameraManager.applyNavigationPadding(followingPadding, overviewPadding)
    }

    Box(modifier) {
        if (currentManeuver != null) {
            NaviManeuverPanel(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .fillMaxWidth()
                    .padding(
                        horizontal = 16.dp,
                        vertical = 8.dp,
                    )
                    .onGloballyPositioned { coordinates ->
                        maneuverPanelBottomPx = coordinates.positionInParent().y + coordinates.size.height.toFloat()
                    },
                currentManeuver = currentManeuver,
                nextManeuver = guidanceUiState.nextManeuver,
            )
        }

        if (cameraState == NavigationCameraState.IDLE) {
            NaviReturnButton(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 130.dp),
                onClick = { cameraManager.requestCameraFollowing() },
            )
        }
    }
}
