package me.matsumo.onenavi.feature.map.components.topappbar

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationEventHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import kotlinx.collections.immutable.ImmutableList
import me.matsumo.onenavi.core.model.RouteWaypoint
import me.matsumo.onenavi.feature.map.state.MapUiEvent

@Composable
internal fun MapRoutePreviewTopAppBar(
    waypoints: ImmutableList<RouteWaypoint>,
    waypointEditResult: Pair<Int, RouteWaypoint.Place>?,
    isInteractionEnabled: Boolean,
    onUiEvent: (MapUiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val navigationState = rememberNavigationEventState(NavigationEventInfo.None)
    var isEditing by remember { mutableStateOf(false) }

    NavigationEventHandler(
        state = navigationState,
        isBackEnabled = isInteractionEnabled,
    ) {
        if (isEditing) {
            isEditing = false
        } else {
            onUiEvent(MapUiEvent.OnRoutePreviewDismissed)
        }
    }

    ElevatedCard(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 4.dp,
        ),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        if (isEditing) {
            MapRoutePreviewTopAppBarEditing(
                waypoints = waypoints,
                waypointEditResult = waypointEditResult,
                isInteractionEnabled = isInteractionEnabled,
                onUiEvent = onUiEvent,
                onEditingFinished = { isEditing = false },
            )
        } else {
            MapRoutePreviewTopAppBarConfirmed(
                waypoints = waypoints,
                waypointEditResult = waypointEditResult,
                isInteractionEnabled = isInteractionEnabled,
                onUiEvent = onUiEvent,
                onEditClicked = { isEditing = true },
            )
        }
    }
}

/** waypoint 行の位置種別。先頭は出発地、末尾は目的地、それ以外は経由地を表す。 */
internal enum class WaypointPosition {
    First,
    Middle,
    Last,
}

internal fun resolvePosition(index: Int, totalCount: Int): WaypointPosition {
    return when {
        index == 0 -> WaypointPosition.First
        index == totalCount - 1 -> WaypointPosition.Last
        else -> WaypointPosition.Middle
    }
}
