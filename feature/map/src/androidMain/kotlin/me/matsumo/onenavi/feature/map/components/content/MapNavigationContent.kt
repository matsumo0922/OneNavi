package me.matsumo.onenavi.feature.map.components.content

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationEventHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import dev.chrisbanes.haze.HazeState
import me.matsumo.onenavi.core.navigation.newguidance.model.GuidanceState
import me.matsumo.onenavi.feature.map.components.navigation.MapNavigationManeuverPanel
import me.matsumo.onenavi.feature.map.state.MapUiEvent

/**
 * ナビゲーション中の UI レイヤー。
 *
 * 戻る操作は画面スタックの単純な pop ではなく、ナビゲーション停止イベントとして扱う。
 * これにより UI の戻る操作と案内停止の副作用を [MapUiEvent.OnNavigationStop] に集約する。
 */
@Composable
internal fun MapNavigationContent(
    guidanceState: GuidanceState,
    hazeState: HazeState,
    onUiEvent: (MapUiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val navigationState = rememberNavigationEventState(NavigationEventInfo.None)
    val guiding = guidanceState as? GuidanceState.Guiding
    val banner = guiding?.presentation?.banner

    NavigationEventHandler(
        state = navigationState,
    ) {
        onUiEvent(MapUiEvent.OnNavigationStop)
    }

    LaunchedEffect(banner?.primary?.guidancePointIndex) {
        if (banner == null) {
            onUiEvent(MapUiEvent.OnTopAppBarHeightChanged(0))
        }
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.TopCenter,
    ) {
        if (guiding != null && banner != null) {
            MapNavigationManeuverPanel(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .onGloballyPositioned { coordinates ->
                        onUiEvent(MapUiEvent.OnTopAppBarHeightChanged(coordinates.size.height))
                    },
                banner = banner,
                listItems = guiding.presentation.listItems,
                progress = guiding.progress,
                hazeState = hazeState,
            )
        }
    }
}
