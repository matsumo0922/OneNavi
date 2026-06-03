package me.matsumo.onenavi.feature.map.components.navigation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.ImmutableList
import me.matsumo.onenavi.core.common.formatDistance
import me.matsumo.onenavi.core.common.formatDuration
import me.matsumo.onenavi.core.common.formatYen
import me.matsumo.onenavi.core.model.RouteDetail
import me.matsumo.onenavi.core.model.RoutePriority
import me.matsumo.onenavi.core.navigation.newguidance.model.RoutePreviewState
import me.matsumo.onenavi.core.resource.Res
import me.matsumo.onenavi.core.resource.common_error
import me.matsumo.onenavi.core.resource.common_loading
import me.matsumo.onenavi.core.resource.common_unit_day
import me.matsumo.onenavi.core.resource.common_unit_hour
import me.matsumo.onenavi.core.resource.common_unit_kilometer
import me.matsumo.onenavi.core.resource.common_unit_meter
import me.matsumo.onenavi.core.resource.common_unit_minute
import me.matsumo.onenavi.core.resource.home_map_navigation_eta_alternatives
import me.matsumo.onenavi.core.resource.home_map_route_result_via
import me.matsumo.onenavi.core.ui.theme.semiBold
import org.jetbrains.compose.resources.stringResource

/**
 * ナビゲーション中の代替ルート候補を下部に表示するカード。
 *
 * ETA カードの代わりに表示され、案内画面状態を維持したまま複数候補を確認できる。
 */
@Composable
internal fun MapNavigationAlternativesCard(
    routePreviewState: RoutePreviewState,
    onCloseClicked: () -> Unit,
    onRouteClicked: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.shadow(
            elevation = 10.dp,
            shape = AlternativesCardShape,
        ),
        shape = AlternativesCardShape,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
        ) {
            MapNavigationSearchResultsHeader(
                modifier = Modifier.fillMaxWidth(),
                title = stringResource(Res.string.home_map_navigation_eta_alternatives),
                onCloseClicked = onCloseClicked,
            )

            HorizontalDivider()

            when (routePreviewState) {
                RoutePreviewState.Idle,
                RoutePreviewState.Searching,
                -> MapNavigationAlternativesMessage(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    text = stringResource(Res.string.common_loading),
                )

                is RoutePreviewState.Failed -> MapNavigationAlternativesMessage(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    text = stringResource(Res.string.common_error),
                )

                is RoutePreviewState.Ready -> MapNavigationAlternativesRouteList(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    routes = routePreviewState.routes,
                    onRouteClicked = onRouteClicked,
                )
            }
        }
    }
}

@Composable
private fun MapNavigationAlternativesMessage(
    text: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun MapNavigationAlternativesRouteList(
    routes: ImmutableList<RouteDetail>,
    onRouteClicked: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(
            items = routes,
            key = { index, route -> "${route.id}_$index" },
        ) { index, route ->
            MapNavigationAlternativeRouteRow(
                modifier = Modifier.fillMaxWidth(),
                route = route,
                onClicked = {
                    onRouteClicked(index)
                },
            )
        }
    }
}

@Composable
private fun MapNavigationAlternativeRouteRow(
    route: RouteDetail,
    onClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val durationText = route.formattedDuration()
    val distanceAndTollText = route.formattedDistanceAndToll()
    val durationSpanStyle = MaterialTheme.typography.titleMedium.semiBold()
        .toSpanStyle()
        .copy(color = MaterialTheme.colorScheme.onSurface)
    val distanceAndTollSpanStyle = MaterialTheme.typography.bodyLarge
        .toSpanStyle()
        .copy(color = MaterialTheme.colorScheme.onSurfaceVariant)

    Column(
        modifier = modifier
            .clickable(onClick = onClicked)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        MapNavigationAlternativePriority(
            priority = route.priority,
        )

        Text(
            text = buildAnnotatedString {
                withStyle(durationSpanStyle) {
                    append(durationText)
                }
                append("  ")
                withStyle(distanceAndTollSpanStyle) {
                    append(distanceAndTollText)
                }
            },
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        val viaRoadText = route.viaRoadText()
        if (viaRoadText != null) {
            Text(
                text = viaRoadText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MapNavigationAlternativePriority(
    priority: RoutePriority?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = AlternativePriorityShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = (priority ?: RoutePriority.Recommended).label,
                style = MaterialTheme.typography.labelMedium.semiBold(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
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
private fun RouteDetail.formattedDistanceAndToll(): String {
    val distanceText = formatDistance(
        meters = distanceMeters,
        kilometerLabel = stringResource(Res.string.common_unit_kilometer),
        meterLabel = stringResource(Res.string.common_unit_meter),
    )
    val tollText = tollFee?.let { tollFee -> formatYen(tollFee) }
    return listOfNotNull(distanceText, tollText).joinToString(" · ")
}

@Composable
private fun RouteDetail.viaRoadText(): String? {
    val roadNames = roadNamesByDistance.take(2).joinToString("/")
    if (roadNames.isBlank()) return null
    return stringResource(Res.string.home_map_route_result_via, roadNames)
}

/** 代替ルートカードの角丸形状。 */
private val AlternativesCardShape = RoundedCornerShape(16.dp)

/** 代替ルート種別ラベルの角丸形状。 */
private val AlternativePriorityShape = RoundedCornerShape(999.dp)
