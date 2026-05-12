package me.matsumo.onenavi.feature.map.components.bottomsheet

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.ImmutableList
import me.matsumo.onenavi.core.common.formatDistance
import me.matsumo.onenavi.core.common.formatDuration
import me.matsumo.onenavi.core.model.RouteDetail
import me.matsumo.onenavi.core.resource.Res
import me.matsumo.onenavi.core.resource.common_unit_day
import me.matsumo.onenavi.core.resource.common_unit_hour
import me.matsumo.onenavi.core.resource.common_unit_kilometer
import me.matsumo.onenavi.core.resource.common_unit_meter
import me.matsumo.onenavi.core.resource.common_unit_minute
import me.matsumo.onenavi.core.resource.home_map_route_result_duration_distance
import me.matsumo.onenavi.core.resource.home_map_route_result_start_navigation
import me.matsumo.onenavi.feature.map.state.MapUiEvent
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun MapRoutePreviewSheet(
    routes: ImmutableList<RouteDetail>,
    selectedRouteIndex: Int,
    onUiEvent: (MapUiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(routes) { index, route ->
            MapRoutePreviewItem(
                modifier = Modifier.fillMaxWidth(),
                route = route,
                isSelected = selectedRouteIndex == index,
                onNavigationClicked = { onUiEvent(MapUiEvent.OnNavigationStart) },
                onRouteSelected = { onUiEvent(MapUiEvent.OnRouteIndexChanged(index)) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MapRoutePreviewItem(
    route: RouteDetail,
    isSelected: Boolean,
    onNavigationClicked: () -> Unit,
    onRouteSelected: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dayLabel = stringResource(Res.string.common_unit_day)
    val hourLabel = stringResource(Res.string.common_unit_hour)
    val minuteLabel = stringResource(Res.string.common_unit_minute)
    val meterLabel = stringResource(Res.string.common_unit_meter)
    val kilometerLabel = stringResource(Res.string.common_unit_kilometer)

    val duration = remember(route, dayLabel, hourLabel, minuteLabel) {
        formatDuration(
            totalSeconds = route.durationSeconds,
            dayLabel = dayLabel,
            hourLabel = hourLabel,
            minuteLabel = minuteLabel,
        )
    }
    val distance = remember(route, meterLabel, kilometerLabel) {
        formatDistance(
            meters = route.distanceMeters,
            meterLabel = meterLabel,
            kilometerLabel = kilometerLabel,
        )
    }

    Row(
        modifier = modifier
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .then(
                if (isSelected) {
                    Modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest)
                } else {
                    Modifier
                },
            )
            .clickable { onRouteSelected.invoke() }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                modifier = Modifier.fillMaxWidth(),
                text = stringResource(Res.string.home_map_route_result_duration_distance, duration, distance),
                style = MaterialTheme.typography.titleLarge,
            )
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
