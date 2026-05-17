package me.matsumo.onenavi.feature.map.components.bottomsheet

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.ImmutableList
import me.matsumo.onenavi.core.common.formatDistance
import me.matsumo.onenavi.core.common.formatDuration
import me.matsumo.onenavi.core.common.formatYen
import me.matsumo.onenavi.core.model.RoadClass
import me.matsumo.onenavi.core.model.RouteDetail
import me.matsumo.onenavi.core.model.RoutePriority
import me.matsumo.onenavi.core.resource.Res
import me.matsumo.onenavi.core.resource.common_unit_day
import me.matsumo.onenavi.core.resource.common_unit_hour
import me.matsumo.onenavi.core.resource.common_unit_kilometer
import me.matsumo.onenavi.core.resource.common_unit_meter
import me.matsumo.onenavi.core.resource.common_unit_minute
import me.matsumo.onenavi.core.resource.home_map_route_result_start_navigation
import me.matsumo.onenavi.core.resource.home_map_route_result_via
import me.matsumo.onenavi.core.ui.theme.RouteColors
import me.matsumo.onenavi.core.ui.theme.semiBold
import me.matsumo.onenavi.feature.map.state.MapUiEvent
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun MapRoutePreviewSheet(
    routes: ImmutableList<RouteDetail>,
    selectedRouteIndex: Int,
    onUiEvent: (MapUiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current

    val navigationBarHeightDp = WindowInsets.navigationBars
        .asPaddingValues()
        .calculateBottomPadding()

    Column(
        modifier = modifier.onGloballyPositioned { coordinates ->
            val yDp = 48.dp // BottomSheetDefaults.DragHandle の固定サイズ
            val heightDp = with(density) { coordinates.size.height.toDp() }

            onUiEvent(MapUiEvent.OnBottomSheetPeekHeightChanged(yDp + heightDp + navigationBarHeightDp))
        },
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MapRoutePreviewRow(
            routes = routes,
            selectedRouteIndex = selectedRouteIndex,
            onRouteSelected = { onUiEvent(MapUiEvent.OnRouteIndexChanged(it)) },
        )

        AnimatedContent(
            modifier = Modifier.fillMaxWidth(),
            targetState = selectedRouteIndex,
        ) {
            MapRoutePreviewItem(
                modifier = Modifier.fillMaxWidth(),
                route = routes[it],
                onNavigationClicked = { onUiEvent(MapUiEvent.OnNavigationStart) },
            )
        }
    }
}

@Composable
private fun MapRoutePreviewRow(
    routes: ImmutableList<RouteDetail>,
    selectedRouteIndex: Int,
    onRouteSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        contentPadding = PaddingValues(horizontal = 16.dp),
    ) {
        itemsIndexed(routes) { index, route ->
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onRouteSelected(index) }
                    .background(if (selectedRouteIndex == index) MaterialTheme.colorScheme.surfaceContainer else Color.Transparent)
                    .border(
                        width = 1.dp,
                        color = if (selectedRouteIndex == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(16.dp),
                    )
                    .padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                MapRoutePriority(
                    priority = route.priority,
                )

                Text(
                    modifier = Modifier.padding(top = 8.dp),
                    text = route.formattedDuration(),
                    style = MaterialTheme.typography.bodyMedium.semiBold(),
                    color = if (selectedRouteIndex == index) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Text(
                    text = formatYen(route.tollFee ?: 0),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selectedRouteIndex == index) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MapRoutePreviewItem(
    route: RouteDetail,
    onNavigationClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            MapRoutePriority(
                priority = route.priority,
            )

            Text(
                modifier = Modifier.padding(top = 4.dp),
                text = route.formattedDuration(),
                style = MaterialTheme.typography.headlineMedium,
            )

            Text(
                modifier = Modifier.padding(top = 4.dp),
                text = buildAnnotatedString {
                    append(route.formattedDistance())

                    route.tollFee?.let {
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) {
                            append(" ")
                            append(formatYen(it))
                        }
                    }
                },
                style = MaterialTheme.typography.bodyLarge.semiBold(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                text = stringResource(Res.string.home_map_route_result_via, route.roadNamesByDistance.take(2).joinToString("/")),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (route.entryInterchangeName != null || route.exitInterchangeName != null) {
                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    route.entryInterchangeName?.let {
                        Text(
                            text = "IN: ",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        InterChange(name = it)
                    }

                    Spacer(modifier = Modifier.size(4.dp))

                    route.exitInterchangeName?.let {
                        Text(
                            text = "OUT: ",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        InterChange(name = it)
                    }
                }
            }
        }

        Button(
            onClick = onNavigationClicked,
            contentPadding = ButtonDefaults.SmallContentPadding,
        ) {
            Icon(
                modifier = Modifier.size(ButtonDefaults.SmallIconSize),
                imageVector = Icons.Outlined.Navigation,
                contentDescription = null,
            )

            Spacer(
                modifier = Modifier.size(ButtonDefaults.IconSpacing),
            )

            Text(stringResource(Res.string.home_map_route_result_start_navigation))
        }
    }
}

@Composable
private fun MapRoutePriority(
    priority: RoutePriority?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(8.dp, 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = (priority ?: RoutePriority.Recommended).label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
private fun InterChange(
    name: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(RouteColors.accent(RoadClass.HIGHWAY).primary)
            .padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.bodySmall.semiBold(),
            color = RouteColors.accent(RoadClass.HIGHWAY).onPrimary,
        )
    }
}

@Composable
private fun RouteDetail.formattedDuration(): String = formatDuration(
    totalSeconds = durationSeconds,
    dayLabel = stringResource(Res.string.common_unit_day),
    hourLabel = stringResource(Res.string.common_unit_hour),
    minuteLabel = stringResource(Res.string.common_unit_minute),
)

@Composable
private fun RouteDetail.formattedDistance(): String = formatDistance(
    meters = distanceMeters,
    kilometerLabel = stringResource(Res.string.common_unit_kilometer),
    meterLabel = stringResource(Res.string.common_unit_meter),
)
