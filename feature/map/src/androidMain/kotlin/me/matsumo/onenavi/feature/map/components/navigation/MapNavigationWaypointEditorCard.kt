package me.matsumo.onenavi.feature.map.components.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import me.matsumo.onenavi.core.model.RouteWaypoint
import me.matsumo.onenavi.core.navigation.newguidance.model.RoutePreviewState
import me.matsumo.onenavi.core.resource.Res
import me.matsumo.onenavi.core.resource.common_error
import me.matsumo.onenavi.core.resource.common_loading
import me.matsumo.onenavi.core.resource.home_map_navigation_waypoint_editor_title
import me.matsumo.onenavi.core.resource.home_map_route_done
import me.matsumo.onenavi.core.resource.home_map_route_origin_current_location
import me.matsumo.onenavi.core.ui.theme.semiBold
import me.matsumo.onenavi.feature.map.components.topappbar.HomeMapRouteWaypointDivider
import org.jetbrains.compose.resources.stringResource
import sh.calvin.reorderable.ReorderableColumn

/**
 * ナビゲーション中の未通過 waypoint を並び替え・削除するカード。
 *
 * 現在地は並び替え対象外とし、編集対象リストの最後の地点を目的地として扱う。
 */
@Composable
internal fun MapNavigationWaypointEditorCard(
    originWaypoint: RouteWaypoint.CurrentLocation,
    waypoints: ImmutableList<RouteWaypoint.Place>,
    routePreviewState: RoutePreviewState,
    onCloseClicked: () -> Unit,
    onDoneClicked: (ImmutableList<RouteWaypoint.Place>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var editingWaypoints by remember(waypoints) {
        mutableStateOf(waypoints)
    }

    Surface(
        modifier = modifier.shadow(
            elevation = 10.dp,
            shape = WaypointEditorCardShape,
        ),
        shape = WaypointEditorCardShape,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
        ) {
            MapNavigationSearchResultsHeader(
                modifier = Modifier.fillMaxWidth(),
                title = stringResource(Res.string.home_map_navigation_waypoint_editor_title),
                onCloseClicked = onCloseClicked,
            )

            HorizontalDivider()

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(
                        top = 12.dp,
                        bottom = 12.dp,
                    ),
            ) {
                MapNavigationWaypointEditorOriginRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(WaypointEditorRowHeight),
                    originWaypoint = originWaypoint,
                )

                HomeMapRouteWaypointDivider(
                    modifier = Modifier.height(WaypointEditorDividerHeight),
                )

                ReorderableColumn(
                    list = editingWaypoints,
                    onSettle = { fromIndex, toIndex ->
                        editingWaypoints = editingWaypoints.toMutableList()
                            .apply {
                                add(toIndex, removeAt(fromIndex))
                            }
                            .toImmutableList()
                    },
                ) { waypointIndex, waypoint, _ ->
                    key(waypoint.itemKey(waypointIndex)) {
                        ReorderableItem {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                MapNavigationWaypointEditorPlaceRow(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(WaypointEditorRowHeight),
                                    waypoint = waypoint,
                                    number = waypointIndex + 1,
                                    isDestination = waypointIndex == editingWaypoints.lastIndex,
                                    canRemove = editingWaypoints.size > 1,
                                    onRemoveClicked = {
                                        editingWaypoints = editingWaypoints.toMutableList()
                                            .apply {
                                                removeAt(waypointIndex)
                                            }
                                            .toImmutableList()
                                    },
                                    dragHandleModifier = Modifier.draggableHandle(),
                                )

                                if (waypointIndex < editingWaypoints.lastIndex) {
                                    HomeMapRouteWaypointDivider(
                                        modifier = Modifier.height(WaypointEditorDividerHeight),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            HorizontalDivider()

            MapNavigationWaypointEditorBottomBar(
                modifier = Modifier.fillMaxWidth(),
                routePreviewState = routePreviewState,
                canConfirm = editingWaypoints.isNotEmpty(),
                onDoneClicked = {
                    onDoneClicked(editingWaypoints)
                },
            )
        }
    }
}

@Composable
private fun MapNavigationWaypointEditorOriginRow(
    originWaypoint: RouteWaypoint.CurrentLocation,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier.size(WaypointEditorIconSize),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                modifier = Modifier.size(20.dp),
                imageVector = Icons.Filled.MyLocation,
                contentDescription = null,
                tint = Color(0xFF4285F4),
            )
        }

        Text(
            modifier = Modifier.weight(1f),
            text = originWaypoint.displayName(),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun MapNavigationWaypointEditorPlaceRow(
    waypoint: RouteWaypoint.Place,
    number: Int,
    isDestination: Boolean,
    canRemove: Boolean,
    onRemoveClicked: () -> Unit,
    modifier: Modifier = Modifier,
    dragHandleModifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (isDestination) {
            MapNavigationWaypointEditorDestinationIcon()
        } else {
            MapNavigationWaypointEditorNumberIcon(
                number = number,
            )
        }

        Text(
            modifier = Modifier.weight(1f),
            text = waypoint.name,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Icon(
            modifier = dragHandleModifier,
            imageVector = Icons.Filled.DragHandle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        IconButton(
            modifier = Modifier.size(40.dp),
            onClick = onRemoveClicked,
            enabled = canRemove,
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = null,
            )
        }
    }
}

@Composable
private fun MapNavigationWaypointEditorDestinationIcon(
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.size(WaypointEditorIconSize),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            modifier = Modifier.size(20.dp),
            imageVector = Icons.Filled.LocationOn,
            contentDescription = null,
            tint = Color(0xFFEA4335),
        )
    }
}

@Composable
private fun MapNavigationWaypointEditorNumberIcon(
    number: Int,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(WaypointEditorIconSize)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface)
            .padding(2.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.onSurface),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = number.toString(),
                style = MaterialTheme.typography.labelSmall.semiBold(),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun MapNavigationWaypointEditorBottomBar(
    routePreviewState: RoutePreviewState,
    canConfirm: Boolean,
    onDoneClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isSearching = routePreviewState is RoutePreviewState.Searching
    val statusText = when (routePreviewState) {
        RoutePreviewState.Idle,
        is RoutePreviewState.Ready,
        -> null

        RoutePreviewState.Searching -> stringResource(Res.string.common_loading)
        is RoutePreviewState.Failed -> stringResource(Res.string.common_error)
    }

    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (statusText != null) {
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = onDoneClicked,
            enabled = canConfirm && !isSearching,
        ) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
            )

            Text(
                text = stringResource(Res.string.home_map_route_done),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun RouteWaypoint.CurrentLocation.displayName(): String {
    return stringResource(Res.string.home_map_route_origin_current_location)
}

private fun RouteWaypoint.Place.itemKey(index: Int): String {
    return "$name:$latitude:$longitude:$index"
}

/** waypoint 編集カードの角丸形状。 */
private val WaypointEditorCardShape = RoundedCornerShape(16.dp)

/** waypoint 編集カードの行高。 */
private val WaypointEditorRowHeight = 40.dp

/** waypoint 編集カードの行間 divider 高。 */
private val WaypointEditorDividerHeight = 16.dp

/** waypoint 編集カードの地点 icon サイズ。 */
private val WaypointEditorIconSize = 24.dp
