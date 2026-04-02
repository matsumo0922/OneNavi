package me.matsumo.onenavi.feature.home.map.components

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
import me.matsumo.onenavi.feature.home.map.HomeMapViewEvent
import me.matsumo.onenavi.feature.home.map.components.topappbar.HomeMapRouteTopAppBarConfirmed
import me.matsumo.onenavi.feature.home.map.components.topappbar.HomeMapRouteTopAppBarEditing


@Composable
internal fun HomeMapRouteTopAppBar(
    waypoints: ImmutableList<RouteWaypoint>,
    modifier: Modifier = Modifier,
    onViewEvent: (HomeMapViewEvent) -> Unit,
) {
    val navigationState = rememberNavigationEventState(NavigationEventInfo.None)
    var isEditing by remember { mutableStateOf(false) }

    NavigationEventHandler(navigationState) {
        if (isEditing) {
            isEditing = false
        } else {
            onViewEvent(HomeMapViewEvent.OnDismissRoutes)
        }
    }

    ElevatedCard(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        )
    ) {
        if (isEditing) {
            HomeMapRouteTopAppBarEditing(
                waypoints = waypoints,
                onConfirmed = { confirmed ->
                    onViewEvent(HomeMapViewEvent.OnRouteWaypointsConfirmed(confirmed))
                    isEditing = false
                },
                onWaypointClicked = { index ->
                    onViewEvent(HomeMapViewEvent.OnWaypointClicked(index))
                },
                onBackClicked = { isEditing = false },
            )
        } else {
            HomeMapRouteTopAppBarConfirmed(
                waypoints = waypoints,
                onEditClicked = { isEditing = true },
                onSwapClicked = {
                    onViewEvent(HomeMapViewEvent.OnSwapOriginDestination)
                },
                onWaypointClicked = { index ->
                    onViewEvent(HomeMapViewEvent.OnWaypointClicked(index))
                },
                onBackClicked = {
                    onViewEvent(HomeMapViewEvent.OnDismissRoutes)
                },
            )
        }
    }
}


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
