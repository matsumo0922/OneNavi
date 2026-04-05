package me.matsumo.onenavi.feature.home.map.components.navi

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationEventHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.mapbox.maps.EdgeInsets
import com.mapbox.navigation.ui.maps.camera.state.NavigationCameraState
import me.matsumo.onenavi.core.navigation.CameraManager
import me.matsumo.onenavi.core.navigation.GuidanceSessionManager

private const val NAVIGATION_PADDING_HORIZONTAL = 40.0
private const val NAVIGATION_PADDING_EXTRA = 120.0

@Composable
internal fun HomeMapNaviContent(
    guidanceSessionManager: GuidanceSessionManager,
    cameraManager: CameraManager,
    onNavigationStopped: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val navigationState = rememberNavigationEventState(NavigationEventInfo.None)

    val guidanceUiState by guidanceSessionManager.guidanceUiState.collectAsStateWithLifecycle()
    val cameraState by cameraManager.cameraState.collectAsStateWithLifecycle()

    var tripCardY by remember { mutableFloatStateOf(0f) }
    var maneuverPanelHeightPx by remember { mutableFloatStateOf(0f) }

    NavigationEventHandler(navigationState) {
        onNavigationStopped()
    }

    LaunchedEffect(maneuverPanelHeightPx) {
        val topPadding = maneuverPanelHeightPx.toDouble() + NAVIGATION_PADDING_EXTRA
        val bottomPadding = tripCardY.toDouble() + NAVIGATION_PADDING_EXTRA

        val followingPadding =
            EdgeInsets(topPadding, NAVIGATION_PADDING_HORIZONTAL, bottomPadding, NAVIGATION_PADDING_HORIZONTAL)
        val overviewPadding =
            EdgeInsets(topPadding, NAVIGATION_PADDING_HORIZONTAL, bottomPadding, NAVIGATION_PADDING_HORIZONTAL)

        cameraManager.applyNavigationPadding(followingPadding, overviewPadding)
    }

    Box(modifier) {
        val currentManeuver = guidanceUiState.currentManeuver
        if (currentManeuver != null) {
            HomeMapGuidanceManeuverPanel(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .fillMaxWidth()
                    .padding(
                        horizontal = 16.dp,
                        vertical = 8.dp,
                    ),
                currentManeuver = currentManeuver,
                nextManeuver = guidanceUiState.nextManeuver,
            )
        }

        if (cameraState == NavigationCameraState.IDLE) {
            HomeMapGuidanceReturnButton(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 130.dp),
                onClick = { cameraManager.requestCameraFollowing() },
            )
        }
    }
}
