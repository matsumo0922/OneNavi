package me.matsumo.onenavi.feature.home.map.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import me.matsumo.onenavi.core.model.RouteWaypoint
import me.matsumo.onenavi.core.resource.Res
import me.matsumo.onenavi.core.resource.home_map_route_done
import me.matsumo.onenavi.core.resource.home_map_route_input_destination
import me.matsumo.onenavi.core.resource.home_map_route_input_origin
import me.matsumo.onenavi.core.resource.home_map_route_input_waypoint
import me.matsumo.onenavi.core.resource.home_map_route_origin_current_location
import me.matsumo.onenavi.feature.home.map.HomeMapViewEvent
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

private const val MAX_WAYPOINTS = 5

@Composable
internal fun HomeMapRouteTopAppBar(
    waypoints: ImmutableList<RouteWaypoint>,
    modifier: Modifier = Modifier,
    onViewEvent: (HomeMapViewEvent) -> Unit,
) {
    var isEditing by remember { mutableStateOf(false) }

    ElevatedCard(
        modifier = modifier,
    ) {
        if (isEditing) {
            HomeMapRouteTopAppBarEditing(
                waypoints = waypoints,
                onBackClicked = { isEditing = false },
                onConfirmed = { confirmed ->
                    onViewEvent(HomeMapViewEvent.OnRouteWaypointsConfirmed(confirmed))
                    isEditing = false
                },
                onWaypointClicked = { index ->
                    onViewEvent(HomeMapViewEvent.OnWaypointClicked(index))
                },
            )
        } else {
            HomeMapRouteTopAppBarConfirmed(
                waypoints = waypoints,
                onBackClicked = {
                    onViewEvent(HomeMapViewEvent.OnDismissRoutes)
                },
                onEditClicked = { isEditing = true },
                onSwapClicked = {
                    onViewEvent(HomeMapViewEvent.OnSwapOriginDestination)
                },
                onWaypointClicked = { index ->
                    onViewEvent(HomeMapViewEvent.OnWaypointClicked(index))
                },
            )
        }
    }
}

@Composable
private fun HomeMapRouteTopAppBarConfirmed(
    waypoints: ImmutableList<RouteWaypoint>,
    onBackClicked: () -> Unit,
    onEditClicked: () -> Unit,
    onSwapClicked: () -> Unit,
    modifier: Modifier = Modifier,
    onWaypointClicked: (Int) -> Unit,
) {
    val hasWaypoints = waypoints.size > 2

    Row(
        modifier = modifier.padding(
            start = 4.dp,
            top = 8.dp,
            bottom = 8.dp,
            end = 4.dp,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onBackClicked,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = null,
            )
        }

        Column(
            modifier = Modifier.weight(1f),
        ) {
            waypoints.forEachIndexed { index, waypoint ->
                HomeMapRouteWaypointRow(
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
                    HomeMapRouteWaypointDivider()
                }
            }
        }

        if (hasWaypoints) {
            IconButton(
                onClick = onEditClicked,
            ) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = null,
                )
            }
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                IconButton(
                    onClick = onEditClicked,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = null,
                    )
                }

                IconButton(
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

@Composable
private fun HomeMapRouteTopAppBarEditing(
    waypoints: ImmutableList<RouteWaypoint>,
    onBackClicked: () -> Unit,
    onConfirmed: (ImmutableList<RouteWaypoint>) -> Unit,
    modifier: Modifier = Modifier,
    onWaypointClicked: (Int) -> Unit,
) {
    val editingList = remember(waypoints) {
        mutableStateListOf<RouteWaypoint?>().apply {
            addAll(waypoints)
            if (size < MAX_WAYPOINTS) {
                add(null)
            }
        }
    }

    val confirmedCount = editingList.count { it != null }
    val canConfirm = confirmedCount >= 2

    var dragIndex by remember { mutableIntStateOf(-1) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }

    Column(
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(
                start = 4.dp,
                top = 8.dp,
                end = 4.dp,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onBackClicked,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null,
                )
            }

            Column(
                modifier = Modifier.weight(1f),
            ) {
                editingList.forEachIndexed { index, waypoint ->
                    val position = resolvePosition(
                        index = index,
                        totalCount = editingList.size,
                    )
                    val waypointIndex = editingList.subList(0, index + 1)
                        .count { it != null && it !is RouteWaypoint.CurrentLocation } - 1
                    val waypointLabel = if (position == WaypointPosition.Middle && waypoint != null) {
                        ('A' + waypointIndex.coerceAtLeast(0)).toString()
                    } else {
                        null
                    }

                    Row(
                        modifier = Modifier
                            .let { rowModifier ->
                                if (index == dragIndex) {
                                    rowModifier.offset { IntOffset(0, dragOffsetY.roundToInt()) }
                                } else {
                                    rowModifier
                                }
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        HomeMapRouteWaypointRow(
                            modifier = Modifier.weight(1f),
                            waypoint = waypoint,
                            position = position,
                            isEditing = true,
                            waypointLabel = waypointLabel,
                            onClicked = { onWaypointClicked(index) },
                        )

                        Icon(
                            modifier = Modifier
                                .size(48.dp)
                                .padding(12.dp)
                                .pointerInput(index) {
                                    detectDragGestures(
                                        onDragStart = {
                                            dragIndex = index
                                            dragOffsetY = 0f
                                        },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            dragOffsetY += dragAmount.y

                                            val itemHeight = 56f
                                            val targetIndex = (index + (dragOffsetY / itemHeight).roundToInt())
                                                .coerceIn(0, editingList.lastIndex)

                                            if (targetIndex != index && targetIndex != dragIndex) {
                                                val item = editingList.removeAt(dragIndex)
                                                editingList.add(targetIndex, item)
                                                dragIndex = targetIndex
                                                dragOffsetY = 0f
                                            }
                                        },
                                        onDragEnd = {
                                            dragIndex = -1
                                            dragOffsetY = 0f
                                        },
                                        onDragCancel = {
                                            dragIndex = -1
                                            dragOffsetY = 0f
                                        },
                                    )
                                },
                            imageVector = Icons.Filled.DragHandle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        if (waypoint != null) {
                            IconButton(
                                onClick = {
                                    editingList.removeAt(index)
                                    if (editingList.none { it == null } && editingList.size < MAX_WAYPOINTS) {
                                        editingList.add(null)
                                    }
                                },
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = null,
                                )
                            }
                        } else {
                            Box(
                                modifier = Modifier.size(48.dp),
                            )
                        }
                    }

                    if (index < editingList.lastIndex) {
                        HomeMapRouteWaypointDivider(
                            modifier = Modifier.padding(
                                start = 12.dp,
                            ),
                        )
                    }
                }
            }
        }

        HorizontalDivider()

        Row(
            modifier = Modifier
                .align(Alignment.End)
                .padding(
                    end = 8.dp,
                    top = 4.dp,
                    bottom = 4.dp,
                ),
        ) {
            TextButton(
                onClick = {
                    val confirmed = editingList.filterNotNull().toImmutableList()
                    onConfirmed(confirmed)
                },
                enabled = canConfirm,
            ) {
                Text(
                    text = stringResource(Res.string.home_map_route_done),
                )
            }
        }
    }
}

@Composable
private fun HomeMapRouteWaypointRow(
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
            .padding(
                vertical = 8.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
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

@Composable
private fun HomeMapRouteWaypointDivider(
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(
            start = 8.dp,
        ),
    ) {
        Text(
            text = "⋮",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HorizontalDivider(
            modifier = Modifier.padding(
                start = 16.dp,
            ),
        )
    }
}

private enum class WaypointPosition {
    First,
    Middle,
    Last,
}

private fun resolvePosition(index: Int, totalCount: Int): WaypointPosition {
    return when {
        index == 0 -> WaypointPosition.First
        index == totalCount - 1 -> WaypointPosition.Last
        else -> WaypointPosition.Middle
    }
}
