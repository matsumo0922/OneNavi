package me.matsumo.onenavi.feature.home.map.components.topappbar

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import me.matsumo.onenavi.core.model.RouteWaypoint
import me.matsumo.onenavi.core.resource.Res
import me.matsumo.onenavi.core.resource.home_map_route_done
import me.matsumo.onenavi.feature.home.map.components.WaypointPosition
import me.matsumo.onenavi.feature.home.map.components.resolvePosition
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

private const val MAX_WAYPOINTS = 5

@Composable
internal fun HomeMapRouteTopAppBarEditing(
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
                            modifier = Modifier.height(16.dp)
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
