package me.matsumo.onenavi.feature.map.components.topappbar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import me.matsumo.onenavi.core.model.RouteWaypoint
import me.matsumo.onenavi.feature.map.state.MapUiEvent

@Composable
internal fun MapRoutePreviewTopAppBarConfirmed(
    waypoints: ImmutableList<RouteWaypoint>,
    waypointEditResult: Pair<Int, RouteWaypoint.Place>?,
    isInteractionEnabled: Boolean,
    onUiEvent: (MapUiEvent) -> Unit,
    onEditClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(waypointEditResult) {
        val (index, place) = waypointEditResult ?: return@LaunchedEffect
        if (index !in waypoints.indices) return@LaunchedEffect

        val updated = waypoints.toMutableList().apply { set(index, place) }.toImmutableList()
        onUiEvent(MapUiEvent.OnRouteWaypointsConfirmed(updated))
        onUiEvent(MapUiEvent.OnWaypointEditResultConsumed)
    }

    val hasWaypoints = waypoints.size > 2

    Row(
        modifier = modifier.padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        IconButton(
            modifier = Modifier.size(48.dp),
            enabled = isInteractionEnabled,
            onClick = { onUiEvent(MapUiEvent.OnRoutePreviewDismissed) },
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = null,
            )
        }

        Column(
            modifier = Modifier
                .padding(vertical = 8.dp)
                .weight(1f),
        ) {
            waypoints.forEachIndexed { index, waypoint ->
                MapRoutePreviewWaypointRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(32.dp),
                    waypoint = waypoint,
                    position = resolvePosition(
                        index = index,
                        totalCount = waypoints.size,
                    ),
                    isEditing = false,
                    waypointLabel = if (!hasWaypoints) null else null,
                    isEnabled = isInteractionEnabled,
                    onClicked = { onUiEvent(MapUiEvent.OnWaypointEditRequested(index)) },
                )

                if (index < waypoints.lastIndex) {
                    HomeMapRouteWaypointDivider(
                        modifier = Modifier.height(16.dp),
                    )
                }
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            IconButton(
                modifier = Modifier.size(48.dp),
                enabled = isInteractionEnabled,
                onClick = onEditClicked,
            ) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = null,
                )
            }

            if (!hasWaypoints) {
                IconButton(
                    modifier = Modifier.size(48.dp),
                    enabled = isInteractionEnabled,
                    onClick = { onUiEvent(MapUiEvent.OnSwapWaypoints) },
                ) {
                    Icon(
                        imageVector = Icons.Filled.SwapVert,
                        contentDescription = null,
                    )
                }
            }
        }
    }
}
