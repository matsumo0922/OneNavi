package me.matsumo.onenavi.feature.home.map.components.topappbar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import me.matsumo.onenavi.core.model.RouteWaypoint
import me.matsumo.onenavi.core.resource.Res
import me.matsumo.onenavi.core.resource.home_map_route_done
import org.jetbrains.compose.resources.stringResource
import sh.calvin.reorderable.ReorderableColumn

private const val MAX_WAYPOINTS = 5
private val ITEM_HEIGHT = 32.dp
private val DIVIDER_HEIGHT = 16.dp

@Composable
internal fun HomeMapRouteTopAppBarEditing(
    waypoints: ImmutableList<RouteWaypoint>,
    waypointEditResult: Pair<Int, RouteWaypoint.Place>?,
    onWaypointEditResultConsumed: () -> Unit,
    onBackClicked: () -> Unit,
    onConfirmed: (ImmutableList<RouteWaypoint>) -> Unit,
    modifier: Modifier = Modifier,
    onWaypointClicked: (Int) -> Unit,
) {
    var editingList by remember(waypoints) {
        mutableStateOf(
            buildList {
                addAll(waypoints)

                if (size < MAX_WAYPOINTS) {
                    add(null)
                }
            },
        )
    }

    LaunchedEffect(waypointEditResult) {
        val (index, place) = waypointEditResult ?: return@LaunchedEffect
        if (index !in editingList.indices) return@LaunchedEffect

        editingList = editingList.toMutableList().apply {
            set(index, place)
            if (none { it == null } && size < MAX_WAYPOINTS) {
                add(null)
            }
        }
        onWaypointEditResultConsumed()
    }

    val confirmedCount = editingList.count { it != null }
    val canConfirm = confirmedCount >= 2

    Column(
        modifier = modifier.padding(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
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

            Box(
                modifier = Modifier
                    .padding(end = 8.dp)
                    .padding(vertical = 8.dp)
                    .weight(1f),
            ) {
                Column {
                    for (index in editingList.indices) {
                        Box(
                            modifier = Modifier.height(ITEM_HEIGHT),
                        )

                        if (index < editingList.lastIndex) {
                            HomeMapRouteWaypointDivider(
                                modifier = Modifier.height(DIVIDER_HEIGHT),
                            )
                        }
                    }
                }

                ReorderableColumn(
                    list = editingList,
                    onSettle = { fromIndex, toIndex ->
                        editingList = editingList.toMutableList().apply {
                            add(toIndex, removeAt(fromIndex))
                        }
                    },
                    verticalArrangement = Arrangement.spacedBy(DIVIDER_HEIGHT),
                ) { index, item, _ ->
                    key(item ?: index) {
                        ReorderableItem {
                            val position = resolvePosition(index, editingList.size)
                            val waypointIndex = editingList.subList(0, index + 1)
                                .count { it != null && it !is RouteWaypoint.CurrentLocation } - 1
                            val waypointLabel = if (position == WaypointPosition.Middle && item != null) {
                                ('A' + waypointIndex.coerceAtLeast(0)).toString()
                            } else {
                                null
                            }

                            Row(
                                modifier = Modifier
                                    .height(ITEM_HEIGHT)
                                    .fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                HomeMapRouteWaypointRow(
                                    modifier = Modifier.weight(1f),
                                    waypoint = item,
                                    position = position,
                                    isEditing = true,
                                    waypointLabel = waypointLabel,
                                    onClicked = { onWaypointClicked(index) },
                                )

                                Icon(
                                    modifier = Modifier.draggableHandle(),
                                    imageVector = Icons.Filled.DragHandle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )

                                if (item != null) {
                                    IconButton(
                                        modifier = Modifier.size(40.dp),
                                        onClick = {
                                            editingList = editingList.toMutableList().apply {
                                                removeAt(index)
                                                if (none { it == null } && size < MAX_WAYPOINTS) {
                                                    add(null)
                                                }
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
                                        modifier = Modifier.size(40.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        HorizontalDivider()

        Row(
            modifier = Modifier.align(Alignment.End)
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
