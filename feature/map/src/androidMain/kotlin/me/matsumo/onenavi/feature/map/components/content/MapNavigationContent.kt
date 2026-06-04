package me.matsumo.onenavi.feature.map.components.content

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationEventHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import dev.chrisbanes.haze.HazeState
import me.matsumo.onenavi.core.navigation.newguidance.model.GuidanceState
import me.matsumo.onenavi.core.resource.Res
import me.matsumo.onenavi.core.resource.common_cancel
import me.matsumo.onenavi.core.resource.common_ok
import me.matsumo.onenavi.core.resource.home_map_navigation_cancel_dialog_message
import me.matsumo.onenavi.core.resource.home_map_navigation_cancel_dialog_title
import me.matsumo.onenavi.feature.map.components.navigation.MapNavigationAlternativesCard
import me.matsumo.onenavi.feature.map.components.navigation.MapNavigationEtaCard
import me.matsumo.onenavi.feature.map.components.navigation.MapNavigationManeuverPanel
import me.matsumo.onenavi.feature.map.components.navigation.MapNavigationReroutingPanel
import me.matsumo.onenavi.feature.map.components.navigation.MapNavigationSearchResultsCard
import me.matsumo.onenavi.feature.map.components.navigation.MapNavigationSelectedWaypointCard
import me.matsumo.onenavi.feature.map.components.navigation.MapNavigationWaypointEditorCard
import me.matsumo.onenavi.feature.map.state.MapOverlayState
import me.matsumo.onenavi.feature.map.state.MapUiEvent
import me.matsumo.onenavi.feature.map.state.NavigationGuideImage
import org.jetbrains.compose.resources.stringResource

/**
 * ナビゲーション中の UI レイヤー。
 *
 * 戻る操作は画面スタックの単純な pop ではなく、確認後のナビゲーション停止イベントとして扱う。
 * これにより UI の停止操作と案内停止の副作用を [MapUiEvent.OnNavigationStop] に集約する。
 */
@Composable
internal fun MapNavigationContent(
    guidanceState: GuidanceState,
    navigationGuideImage: NavigationGuideImage?,
    overlayState: MapOverlayState,
    hazeState: HazeState,
    onUiEvent: (MapUiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val navigationState = rememberNavigationEventState(NavigationEventInfo.None)
    var showCancelDialog by remember { mutableStateOf(false) }
    val guiding = guidanceState as? GuidanceState.Guiding
    val rerouting = guidanceState as? GuidanceState.Rerouting
    val banner = guiding?.presentation?.banner
    val shouldShowTopPanel = (guiding != null && banner != null) || rerouting != null
    val etaRoute = guiding?.route ?: rerouting?.previousRoute
    val etaProgress = guiding?.progress ?: rerouting?.previousProgress
    val addWaypointSearchResults = overlayState as? MapOverlayState.AddWaypointSearchResults
    val addWaypointSelected = overlayState as? MapOverlayState.AddWaypointSelected
    val addWaypointAlternatives = overlayState as? MapOverlayState.AddWaypointAlternatives
    val navigationAlternatives = overlayState as? MapOverlayState.NavigationAlternatives
    val navigationWaypointEditor = overlayState as? MapOverlayState.NavigationWaypointEditor
    val alternativesRoutePreviewState = addWaypointAlternatives?.routePreviewState
        ?: navigationAlternatives?.routePreviewState
    val hasAddWaypointOverlayCard = addWaypointSearchResults != null || addWaypointSelected != null
    val hasAlternativesOverlayCard = alternativesRoutePreviewState != null
    val hasWaypointEditorOverlayCard = navigationWaypointEditor != null
    val hasNavigationRouteOverlayCard = hasAddWaypointOverlayCard || hasAlternativesOverlayCard
    val hasNavigationOverlayCard = hasNavigationRouteOverlayCard || hasWaypointEditorOverlayCard
    val hasNavigationBottomCard = etaProgress != null || hasNavigationOverlayCard

    fun cancelNavigation() {
        onUiEvent(MapUiEvent.OnNavigationStop)
    }

    NavigationEventHandler(
        state = navigationState,
    ) {
        showCancelDialog = true
    }

    LaunchedEffect(banner?.primary?.guidancePointIndex, shouldShowTopPanel) {
        if (!shouldShowTopPanel) {
            onUiEvent(MapUiEvent.OnTopAppBarHeightChanged(0))
        }
    }

    LaunchedEffect(hasNavigationBottomCard) {
        if (!hasNavigationBottomCard) {
            onUiEvent(MapUiEvent.OnNavigationCardHeightChanged(0))
        }
    }

    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.TopCenter,
    ) {
        val bottomFloatingCardHeight = maxHeight / 3f
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
                    route = guiding.route,
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

        if (addWaypointSelected != null) {
            MapNavigationSelectedWaypointCard(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .onGloballyPositioned { coordinates ->
                        onUiEvent(MapUiEvent.OnNavigationCardHeightChanged(coordinates.size.height))
                    }
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 16.dp)
                    .height(bottomFloatingCardHeight),
                place = addWaypointSelected.place,
                routePreviewState = addWaypointSelected.routePreviewState,
                onCloseClicked = {
                    onUiEvent(MapUiEvent.OnWaypointSearchDismissed)
                },
                onAddWaypointClicked = {
                    onUiEvent(MapUiEvent.OnAddWaypointConfirmed)
                },
                onAlternativesClicked = {
                    onUiEvent(MapUiEvent.OnAddWaypointAlternativesClicked)
                },
            )
        } else if (addWaypointSearchResults != null) {
            MapNavigationSearchResultsCard(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .onGloballyPositioned { coordinates ->
                        onUiEvent(MapUiEvent.OnNavigationCardHeightChanged(coordinates.size.height))
                    }
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 16.dp)
                    .height(bottomFloatingCardHeight),
                query = addWaypointSearchResults.query,
                results = addWaypointSearchResults.results,
                onCloseClicked = {
                    onUiEvent(MapUiEvent.OnWaypointSearchDismissed)
                },
                onResultClicked = { result ->
                    onUiEvent(MapUiEvent.OnAddWaypointCandidateSelected(result))
                },
            )
        } else if (alternativesRoutePreviewState != null) {
            MapNavigationAlternativesCard(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .onGloballyPositioned { coordinates ->
                        onUiEvent(MapUiEvent.OnNavigationCardHeightChanged(coordinates.size.height))
                    }
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 16.dp)
                    .height(bottomFloatingCardHeight),
                routePreviewState = alternativesRoutePreviewState,
                onCloseClicked = {
                    onUiEvent(MapUiEvent.OnNavigationAlternativesDismissed)
                },
                onRouteClicked = { index ->
                    onUiEvent(MapUiEvent.OnNavigationAlternativeRouteSelected(index))
                },
            )
        } else if (navigationWaypointEditor != null) {
            MapNavigationWaypointEditorCard(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .onGloballyPositioned { coordinates ->
                        onUiEvent(MapUiEvent.OnNavigationCardHeightChanged(coordinates.size.height))
                    }
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 16.dp)
                    .height(bottomFloatingCardHeight),
                originWaypoint = navigationWaypointEditor.originWaypoint,
                waypoints = navigationWaypointEditor.waypoints,
                routePreviewState = navigationWaypointEditor.routePreviewState,
                onCloseClicked = {
                    onUiEvent(MapUiEvent.OnNavigationRoutePreviewDismissed)
                },
                onDoneClicked = { waypoints ->
                    onUiEvent(MapUiEvent.OnNavigationWaypointEditConfirmed(waypoints))
                },
            )
        } else if (etaProgress != null && etaRoute != null) {
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
                onCloseClicked = ::cancelNavigation,
                onAlternativesClicked = {
                    onUiEvent(MapUiEvent.OnNavigationAlternativesClicked)
                },
                onAddWaypointClicked = {
                    onUiEvent(MapUiEvent.OnAddWaypointRequested)
                },
                onRoutePreviewClicked = {
                    onUiEvent(MapUiEvent.OnNavigationRoutePreviewClicked)
                },
            )
        }
    }

    if (showCancelDialog) {
        MapNavigationCancelDialog(
            onConfirmed = {
                showCancelDialog = false
                cancelNavigation()
            },
            onDismissRequest = {
                showCancelDialog = false
            },
        )
    }
}

@Composable
private fun MapNavigationCancelDialog(
    onConfirmed: () -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AlertDialog(
        modifier = modifier,
        onDismissRequest = onDismissRequest,
        title = {
            Text(text = stringResource(Res.string.home_map_navigation_cancel_dialog_title))
        },
        text = {
            Text(text = stringResource(Res.string.home_map_navigation_cancel_dialog_message))
        },
        confirmButton = {
            TextButton(
                onClick = onConfirmed,
            ) {
                Text(text = stringResource(Res.string.common_ok))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismissRequest,
            ) {
                Text(text = stringResource(Res.string.common_cancel))
            }
        },
    )
}
