package me.matsumo.onenavi.feature.map.components.content

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationEventHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import dev.chrisbanes.haze.HazeState
import me.matsumo.onenavi.core.navigation.newguidance.model.GuidanceState
import me.matsumo.onenavi.feature.map.components.navigation.MapNavigationEtaCard
import me.matsumo.onenavi.feature.map.components.navigation.MapNavigationManeuverPanel
import me.matsumo.onenavi.feature.map.components.navigation.MapNavigationReroutingPanel
import me.matsumo.onenavi.feature.map.state.MapUiEvent
import me.matsumo.onenavi.feature.map.state.NavigationGuideImage

/**
 * ナビゲーション中の UI レイヤー。
 *
 * 戻る操作は画面スタックの単純な pop ではなく、ナビゲーション停止イベントとして扱う。
 * これにより UI の戻る操作と案内停止の副作用を [MapUiEvent.OnNavigationStop] に集約する。
 */
@Composable
internal fun MapNavigationContent(
    guidanceState: GuidanceState,
    navigationGuideImage: NavigationGuideImage?,
    hazeState: HazeState,
    onUiEvent: (MapUiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val navigationState = rememberNavigationEventState(NavigationEventInfo.None)
    val guiding = guidanceState as? GuidanceState.Guiding
    val rerouting = guidanceState as? GuidanceState.Rerouting
    val banner = guiding?.presentation?.banner
    val shouldShowTopPanel = (guiding != null && banner != null) || rerouting != null
    val etaRoute = guiding?.route ?: rerouting?.previousRoute
    val etaProgress = guiding?.progress ?: rerouting?.previousProgress

    NavigationEventHandler(
        state = navigationState,
    ) {
        onUiEvent(MapUiEvent.OnNavigationStop)
    }

    LaunchedEffect(banner?.primary?.guidancePointIndex, shouldShowTopPanel) {
        if (!shouldShowTopPanel) {
            onUiEvent(MapUiEvent.OnTopAppBarHeightChanged(0))
        }
    }

    LaunchedEffect(etaProgress == null) {
        if (etaProgress == null) {
            onUiEvent(MapUiEvent.OnNavigationCardHeightChanged(0))
        }
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.TopCenter,
    ) {
        val topPanelModifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .onGloballyPositioned { coordinates ->
                onUiEvent(MapUiEvent.OnTopAppBarHeightChanged(coordinates.size.height))
            }

        when {
            guiding != null && banner != null -> {
                MapNavigationManeuverPanel(
                    modifier = topPanelModifier,
                    banner = banner,
                    listItems = guiding.presentation.listItems,
                    progress = guiding.progress,
                    guideImage = navigationGuideImage,
                    hazeState = hazeState,
                )
            }

            rerouting != null -> {
                MapNavigationReroutingPanel(
                    modifier = topPanelModifier,
                    routePriority = rerouting.previousRoute.priority,
                    roadClass = rerouting.previousProgress.currentRoadClass,
                )
            }
        }

        if (etaProgress != null && etaRoute != null) {
            MapNavigationEtaCard(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .onGloballyPositioned { coordinates ->
                        onUiEvent(MapUiEvent.OnNavigationCardHeightChanged(coordinates.size.height))
                    }
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                progress = etaProgress,
                geometry = etaRoute.geometry,
                roadClassSegments = etaRoute.roadClassSegments,
                congestionSegments = etaRoute.congestionSegments,
                onCloseClicked = {},
                onAlternativesClicked = {},
                onAddWaypointClicked = {},
                onDetourClicked = {},
                onMoreClicked = {},
            )
        }
    }
}
