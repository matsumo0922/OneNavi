package me.matsumo.onenavi.feature.home.map.components.topappbar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.matsumo.onenavi.core.model.RouteWaypoint
import me.matsumo.onenavi.core.resource.Res
import me.matsumo.onenavi.core.resource.home_map_route_input_destination
import me.matsumo.onenavi.core.resource.home_map_route_input_origin
import me.matsumo.onenavi.core.resource.home_map_route_input_waypoint
import me.matsumo.onenavi.core.resource.home_map_route_origin_current_location
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun HomeMapRouteWaypointRow(
    waypoint: RouteWaypoint?,
    position: WaypointPosition,
    isEditing: Boolean,
    waypointLabel: String?,
    modifier: Modifier = Modifier,
    onClicked: () -> Unit,
) {
    val currentLocationLabel = stringResource(Res.string.home_map_route_origin_current_location)
    val placeholderText = when (position) {
        WaypointPosition.First -> stringResource(Res.string.home_map_route_input_origin)
        WaypointPosition.Middle -> stringResource(Res.string.home_map_route_input_waypoint)
        WaypointPosition.Last -> stringResource(Res.string.home_map_route_input_destination)
    }

    Row(
        modifier = modifier
            .clickable(onClick = onClicked)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HomeMapRouteWaypointIcon(
            waypoint = waypoint,
            position = position,
            isEditing = isEditing,
            waypointLabel = waypointLabel,
        )

        if (waypoint != null) {
            val displayName = when (waypoint) {
                is RouteWaypoint.CurrentLocation -> currentLocationLabel
                is RouteWaypoint.Place -> waypoint.name
            }

            Text(
                text = displayName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Text(
                text = placeholderText,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun HomeMapRouteWaypointIcon(
    waypoint: RouteWaypoint?,
    position: WaypointPosition,
    isEditing: Boolean,
    waypointLabel: String?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.size(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            waypoint is RouteWaypoint.CurrentLocation -> {
                Icon(
                    modifier = Modifier.size(20.dp),
                    imageVector = Icons.Filled.MyLocation,
                    contentDescription = null,
                    tint = Color(0xFF4285F4),
                )
            }

            position == WaypointPosition.Last -> {
                Icon(
                    modifier = Modifier.size(20.dp),
                    imageVector = Icons.Filled.LocationOn,
                    contentDescription = null,
                    tint = Color(0xFFEA4335),
                )
            }

            isEditing && waypointLabel != null -> {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = waypointLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            else -> {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(Color.Transparent)
                        .padding(2.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)),
                )
            }
        }
    }
}
