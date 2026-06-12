package me.matsumo.onenavi.feature.map.components.content

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationEventHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import me.matsumo.onenavi.core.datasource.location.VehicleSpeedState
import me.matsumo.onenavi.core.navigation.newguidance.model.GuidanceState
import me.matsumo.onenavi.core.navigation.voice.debug.VoiceAnnouncementDebugSnapshot
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
import me.matsumo.onenavi.feature.map.components.navigation.MapNavigationTtsDebugCard
import me.matsumo.onenavi.feature.map.components.navigation.MapNavigationWaypointEditorCard
import me.matsumo.onenavi.feature.map.state.MapHostInsets
import me.matsumo.onenavi.feature.map.state.MapOverlayState
import me.matsumo.onenavi.feature.map.state.MapPanelLayout
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
    vehicleSpeedState: VehicleSpeedState,
    navigationGuideImage: NavigationGuideImage?,
    overlayState: MapOverlayState,
    ttsDebugSnapshot: VoiceAnnouncementDebugSnapshot?,
    panelLayout: MapPanelLayout,
    navigationCardHeight: Dp,
    contentInsets: MapHostInsets,
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
    val isPlaceDetailsSheetOverlay = overlayState is MapOverlayState.PlaceDetails
    val isSearchResultsSheetOverlay = overlayState is MapOverlayState.SearchResults
    val hasSheetOverlay = isPlaceDetailsSheetOverlay || isSearchResultsSheetOverlay
    val alternativesRoutePreviewState = addWaypointAlternatives?.routePreviewState
        ?: navigationAlternatives?.routePreviewState
    val hasAddWaypointOverlayCard = addWaypointSearchResults != null || addWaypointSelected != null
    val hasAlternativesOverlayCard = alternativesRoutePreviewState != null
    val hasWaypointEditorOverlayCard = navigationWaypointEditor != null
    val hasNavigationRouteOverlayCard = hasAddWaypointOverlayCard || hasAlternativesOverlayCard
    val hasNavigationOverlayCard = hasNavigationRouteOverlayCard || hasWaypointEditorOverlayCard
    val hasNavigationBottomCardContent = etaProgress != null || hasNavigationOverlayCard
    val hasNavigationBottomCard = !hasSheetOverlay && hasNavigationBottomCardContent

    fun cancelNavigation() {
        onUiEvent(MapUiEvent.OnNavigationStop)
    }

    NavigationEventHandler(
        state = navigationState,
        isBackEnabled = !hasSheetOverlay,
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
        val bottomFloatingCardHeight = if (panelLayout.isSplit) {
            (maxHeight / 2f).coerceAtLeast(MAP_NAVIGATION_SPLIT_BOTTOM_CARD_MIN_HEIGHT)
        } else {
            maxHeight / 3f
        }
        val horizontalContentPadding = if (panelLayout.isSplit) 0.dp else 16.dp
        val topPanelMaxHeight = if (panelLayout.isSplit && hasNavigationBottomCard) {
            (maxHeight - navigationCardHeight).coerceAtLeast(0.dp)
        } else {
            maxHeight
        }
        val topPanelModifier = Modifier
            .fillMaxWidth()
            .heightIn(max = topPanelMaxHeight)
            .padding(
                start = contentInsets.start,
                top = contentInsets.top,
                end = contentInsets.end,
            )
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
                    isSplit = panelLayout.isSplit,
                    availableHeight = topPanelMaxHeight,
                    horizontalPadding = horizontalContentPadding,
                )
            }

            rerouting != null -> {
                MapNavigationReroutingPanel(
                    modifier = topPanelModifier,
                    routePriority = rerouting.previousRoute.priority,
                    roadClass = rerouting.previousProgress.currentRoadClass,
                    horizontalPadding = horizontalContentPadding,
                )
            }
        }

        if (!hasSheetOverlay) {
            if (addWaypointSelected != null) {
                MapNavigationSelectedWaypointCard(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .onGloballyPositioned { coordinates ->
                            onUiEvent(MapUiEvent.OnNavigationCardHeightChanged(coordinates.size.height))
                        }
                        .navigationBottomCardPadding(
                            contentInsets = contentInsets,
                            horizontalPadding = horizontalContentPadding,
                        )
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
                        .navigationBottomCardPadding(
                            contentInsets = contentInsets,
                            horizontalPadding = horizontalContentPadding,
                        )
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
                        .navigationBottomCardPadding(
                            contentInsets = contentInsets,
                            horizontalPadding = horizontalContentPadding,
                        )
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
                        .navigationBottomCardPadding(
                            contentInsets = contentInsets,
                            horizontalPadding = horizontalContentPadding,
                        )
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
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .onGloballyPositioned { coordinates ->
                            onUiEvent(MapUiEvent.OnNavigationCardHeightChanged(coordinates.size.height))
                        }
                        .navigationBottomCardPadding(
                            contentInsets = contentInsets,
                            horizontalPadding = horizontalContentPadding,
                        ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ttsDebugSnapshot?.let { snapshot ->
                        MapNavigationTtsDebugCard(
                            modifier = Modifier.fillMaxWidth(),
                            snapshot = snapshot,
                        )
                    }

                    MapNavigationEtaCard(
                        modifier = Modifier.fillMaxWidth(),
                        progress = etaProgress,
                        congestionSegments = etaRoute.congestionSegments,
                        displaySpeedKmh = vehicleSpeedState.displaySpeedKmh,
                        speedLimitKmh = etaProgress.currentSpeedLimitKmh,
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

/** 分割レイアウトで下部 overlay カードへ最低限確保する高さ。 */
private val MAP_NAVIGATION_SPLIT_BOTTOM_CARD_MIN_HEIGHT = 240.dp

/** 下部 navigation card が system / host inset なしでも確保する下余白。 */
private val MAP_NAVIGATION_BOTTOM_CARD_DEFAULT_PADDING = 8.dp

private fun Modifier.navigationBottomCardPadding(
    contentInsets: MapHostInsets,
    horizontalPadding: Dp,
): Modifier {
    return padding(
        start = contentInsets.start + horizontalPadding,
        end = contentInsets.end + horizontalPadding,
        bottom = contentInsets.bottom.coerceAtLeast(MAP_NAVIGATION_BOTTOM_CARD_DEFAULT_PADDING),
    )
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
