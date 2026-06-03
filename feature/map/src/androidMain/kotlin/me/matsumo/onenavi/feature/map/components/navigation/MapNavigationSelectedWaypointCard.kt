package me.matsumo.onenavi.feature.map.components.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.matsumo.onenavi.core.common.formatDistance
import me.matsumo.onenavi.core.common.formatDuration
import me.matsumo.onenavi.core.model.RouteDetail
import me.matsumo.onenavi.core.model.SearchResultItem
import me.matsumo.onenavi.core.navigation.newguidance.model.RoutePreviewState
import me.matsumo.onenavi.core.resource.Res
import me.matsumo.onenavi.core.resource.common_error
import me.matsumo.onenavi.core.resource.common_loading
import me.matsumo.onenavi.core.resource.common_unit_day
import me.matsumo.onenavi.core.resource.common_unit_hour
import me.matsumo.onenavi.core.resource.common_unit_kilometer
import me.matsumo.onenavi.core.resource.common_unit_meter
import me.matsumo.onenavi.core.resource.common_unit_minute
import me.matsumo.onenavi.core.resource.home_map_navigation_eta_add_waypoint
import me.matsumo.onenavi.core.resource.home_map_navigation_eta_alternatives
import me.matsumo.onenavi.core.ui.theme.semiBold
import org.jetbrains.compose.resources.stringResource

/**
 * ナビゲーション中に waypoint 追加候補として選択した地点を表示するカード。
 *
 * 選択地点の詳細と、候補地点を経由地にした仮ルートのサマリを表示する。
 */
@Composable
internal fun MapNavigationSelectedWaypointCard(
    place: SearchResultItem,
    routePreviewState: RoutePreviewState,
    onCloseClicked: () -> Unit,
    onAddWaypointClicked: () -> Unit,
    onAlternativesClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.shadow(
            elevation = 10.dp,
            shape = SelectedWaypointCardShape,
        ),
        shape = SelectedWaypointCardShape,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
        ) {
            MapNavigationSearchResultsHeader(
                modifier = Modifier.fillMaxWidth(),
                onCloseClicked = onCloseClicked,
            )

            HorizontalDivider()

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    MapNavigationSelectedWaypointTitle(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .padding(top = 12.dp),
                        place = place,
                    )
                }

                items(place.detailLines()) { detail ->
                    Text(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        text = detail,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            HorizontalDivider()

            MapNavigationSelectedWaypointBottomBar(
                modifier = Modifier.fillMaxWidth(),
                routePreviewState = routePreviewState,
                onAddWaypointClicked = onAddWaypointClicked,
                onAlternativesClicked = onAlternativesClicked,
            )
        }
    }
}

@Composable
private fun MapNavigationSelectedWaypointTitle(
    place: SearchResultItem,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = place.name,
            style = MaterialTheme.typography.titleMedium.semiBold(),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        place.ratingLine()?.let { rating ->
            Text(
                text = rating,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MapNavigationSelectedWaypointBottomBar(
    routePreviewState: RoutePreviewState,
    onAddWaypointClicked: () -> Unit,
    onAlternativesClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = routePreviewState.summaryText(),
            style = MaterialTheme.typography.titleMedium.semiBold(),
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                modifier = Modifier.weight(1f),
                onClick = onAddWaypointClicked,
            ) {
                Icon(
                    imageVector = Icons.Default.AddCircleOutline,
                    contentDescription = null,
                )

                Text(
                    text = stringResource(Res.string.home_map_navigation_eta_add_waypoint),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            FilledTonalButton(
                modifier = Modifier.weight(1f),
                onClick = onAlternativesClicked,
            ) {
                Icon(
                    imageVector = Icons.Default.Route,
                    contentDescription = null,
                )

                Text(
                    text = stringResource(Res.string.home_map_navigation_eta_alternatives),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun RoutePreviewState.summaryText(): String {
    return when (this) {
        RoutePreviewState.Idle,
        RoutePreviewState.Searching,
        -> stringResource(Res.string.common_loading)

        is RoutePreviewState.Failed -> stringResource(Res.string.common_error)

        is RoutePreviewState.Ready -> selectedRoute.summaryText()
    }
}

@Composable
private fun RouteDetail.summaryText(): String {
    val duration = formatDuration(
        totalSeconds = durationSeconds,
        dayLabel = stringResource(Res.string.common_unit_day),
        hourLabel = stringResource(Res.string.common_unit_hour),
        minuteLabel = stringResource(Res.string.common_unit_minute),
    )
    val distance = formatDistance(
        meters = distanceMeters,
        kilometerLabel = stringResource(Res.string.common_unit_kilometer),
        meterLabel = stringResource(Res.string.common_unit_meter),
    )

    return "$duration · $distance"
}

private fun SearchResultItem.detailLines(): List<String> {
    return listOfNotNull(
        shortFormattedAddress ?: formattedAddress,
        primaryTypeDisplayName,
        currentOpeningHours,
    ).distinct()
}

private fun SearchResultItem.ratingLine(): String? {
    val rating = rating ?: return null
    val count = userRatingCount?.let { " ($it)" } ?: ""
    return "$rating ★$count"
}

/** 選択地点カードの角丸形状。 */
private val SelectedWaypointCardShape = RoundedCornerShape(16.dp)
