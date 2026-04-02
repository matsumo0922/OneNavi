package me.matsumo.onenavi.feature.home.map.components.topappbar

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.ImmutableList
import me.matsumo.onenavi.core.model.RouteWaypoint

@Composable
internal fun HomeMapRouteTopAppBarConfirmed(
    waypoints: ImmutableList<RouteWaypoint>,
    onBackClicked: () -> Unit,
    onEditClicked: () -> Unit,
    onSwapClicked: () -> Unit,
    modifier: Modifier = Modifier,
    onWaypointClicked: (Int) -> Unit,
) {
    val hasWaypoints = waypoints.size > 2

    Row(
        modifier = modifier.padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        IconButton(
            modifier = Modifier.size(48.dp),
            onClick = onBackClicked,
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
                HomeMapRouteWaypointRow(
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
                    onClicked = { onWaypointClicked(index) },
                )

                if (index < waypoints.lastIndex) {
                    HomeMapRouteWaypointDivider(
                        modifier = Modifier.height(16.dp)
                    )
                }
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            IconButton(
                modifier = Modifier.size(48.dp),
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
                    onClick = onSwapClicked,
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
