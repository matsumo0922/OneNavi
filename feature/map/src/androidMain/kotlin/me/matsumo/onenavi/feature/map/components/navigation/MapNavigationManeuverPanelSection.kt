package me.matsumo.onenavi.feature.map.components.navigation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.chrisbanes.haze.HazeState
import kotlinx.collections.immutable.ImmutableList
import me.matsumo.onenavi.core.common.formatDistance
import me.matsumo.onenavi.core.common.formatDuration
import me.matsumo.onenavi.core.common.formatYen
import me.matsumo.onenavi.core.model.RoadClass
import me.matsumo.onenavi.core.navigation.newguidance.model.EntrancePanelSubtitle
import me.matsumo.onenavi.core.navigation.newguidance.model.ExitPanelSubtitle
import me.matsumo.onenavi.core.navigation.newguidance.model.FacilityPanelItem
import me.matsumo.onenavi.core.navigation.newguidance.model.GuidancePanelFacility
import me.matsumo.onenavi.core.navigation.newguidance.model.GuidancePanelItem
import me.matsumo.onenavi.core.navigation.newguidance.model.GuidancePanelSubtitle
import me.matsumo.onenavi.core.navigation.newguidance.model.GuidanceTextPanelSubtitle
import me.matsumo.onenavi.core.navigation.newguidance.model.ManeuverPanelItem
import me.matsumo.onenavi.core.navigation.newguidance.model.RecommendedLanesPanelSubtitle
import me.matsumo.onenavi.core.navigation.newguidance.model.TollPanelSubtitle
import me.matsumo.onenavi.core.resource.Res
import me.matsumo.onenavi.core.resource.home_map_navigation_panel_direction_sign
import me.matsumo.onenavi.core.resource.home_map_navigation_panel_entrance
import me.matsumo.onenavi.core.resource.home_map_navigation_panel_exit
import me.matsumo.onenavi.core.resource.home_map_navigation_panel_facility_ic
import me.matsumo.onenavi.core.resource.home_map_navigation_panel_facility_jct
import me.matsumo.onenavi.core.resource.home_map_navigation_panel_facility_pa
import me.matsumo.onenavi.core.resource.home_map_navigation_panel_facility_sa
import me.matsumo.onenavi.core.resource.home_map_navigation_panel_facility_toll_gate_badge
import me.matsumo.onenavi.core.resource.home_map_navigation_panel_guidance_header
import me.matsumo.onenavi.core.resource.home_map_navigation_panel_guidance_header_description
import me.matsumo.onenavi.core.resource.home_map_navigation_panel_guidance_point
import me.matsumo.onenavi.core.resource.home_map_route_origin_current_location
import me.matsumo.onenavi.core.resource.ic_vehicle_puck
import me.matsumo.onenavi.core.ui.navigation.ManeuverIcon
import me.matsumo.onenavi.core.ui.theme.RouteColors
import me.matsumo.onenavi.core.ui.theme.bold
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun MapNavigationManeuverPanelSection(
    panelItems: ImmutableList<GuidancePanelItem>,
    hazeState: HazeState,
    meterLabel: String,
    kilometerLabel: String,
    dayLabel: String,
    hourLabel: String,
    minuteLabel: String,
    timestampMillis: Long,
    onDismissPanelClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(16.dp)
    val screenHeight = LocalWindowInfo.current.containerDpSize.height

    Surface(
        modifier = modifier
            .zIndex(1f)
            .height(screenHeight / 2f)
            .shadow(
                elevation = 8.dp,
                shape = shape,
            ),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
        ) {
            MapNavigationGuidancePanelHeader(
                modifier = Modifier.fillMaxWidth(),
                onDismissPanelClicked = onDismissPanelClicked,
            )

            LazyColumn(
                modifier = Modifier.fillMaxWidth(),

            ) {
                itemsIndexed(
                    items = panelItems,
                    key = { _, item -> item.id },
                ) { index, item ->
                    MapNavigationGuidancePanelRow(
                        modifier = Modifier.fillMaxWidth(),
                        item = item,
                        meterLabel = meterLabel,
                        kilometerLabel = kilometerLabel,
                        dayLabel = dayLabel,
                        hourLabel = hourLabel,
                        minuteLabel = minuteLabel,
                        timestampMillis = timestampMillis,
                        isPrimary = index == panelItems.lastIndex,
                    )
                }

                item(key = "footer") {
                    MapNavigationGuidancePanelFooter(
                        modifier = Modifier.fillMaxWidth(),
                        item = panelItems.last(),
                    )
                }
            }
        }
    }
}

@Composable
private fun MapNavigationGuidancePanelHeader(
    onDismissPanelClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = stringResource(Res.string.home_map_navigation_panel_guidance_header),
                style = MaterialTheme.typography.titleMedium.bold(),
            )

            Text(
                text = stringResource(Res.string.home_map_navigation_panel_guidance_header_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        IconButton(
            onClick = onDismissPanelClicked,
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = null
            )
        }
    }
}

@Composable
private fun MapNavigationGuidancePanelFooter(
    item: GuidancePanelItem,
    modifier: Modifier = Modifier,
) {
    val panelColors = RouteColors.accent(item.roadClass())

    Column(
        modifier = modifier.padding(bottom = 16.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Row(
            modifier = Modifier
                .padding(
                    start = 20.dp,
                    end = 16.dp,
                )
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .height(16.dp)
                    .width(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                VerticalDivider(
                    modifier = Modifier
                        .requiredHeight(20.dp)
                        .offset(y = 4.dp),
                    color = panelColors.primary,
                    thickness = 8.dp,
                )
            }

            HorizontalDivider(
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.2f),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(40.dp),
                painter = painterResource(Res.drawable.ic_vehicle_puck),
                contentDescription = null,
            )

            Text(
                modifier = Modifier
                    .padding(start = 36.dp)
                    .weight(1f),
                text = stringResource(Res.string.home_map_route_origin_current_location),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Column(
                modifier = Modifier
                    .padding(end = 16.dp)
                    .widthIn(min = 64.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = "12分", // TODO: 経過時間
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )

                Text(
                    text = "12.3 km", // TODO: 走行距離
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun MapNavigationGuidancePanelRow(
    item: GuidancePanelItem,
    meterLabel: String,
    kilometerLabel: String,
    dayLabel: String,
    hourLabel: String,
    minuteLabel: String,
    timestampMillis: Long,
    isPrimary: Boolean,
    modifier: Modifier = Modifier,
) {
    val distanceText = remember(item.distanceToItemMeters, meterLabel, kilometerLabel) {
        formatPanelDistance(
            meters = item.distanceToItemMeters.toDouble(),
            meterLabel = meterLabel,
            kilometerLabel = kilometerLabel,
        )
    }
    val etaText = remember(item.etaEpochMillis, timestampMillis, dayLabel, hourLabel, minuteLabel) {
        panelDurationText(
            etaEpochMillis = item.etaEpochMillis,
            timestampMillis = timestampMillis,
            dayLabel = dayLabel,
            hourLabel = hourLabel,
            minuteLabel = minuteLabel,
        )
    }
    val title = item.panelTitle()
    val subtitle = item.subtitle
    val panelColors = RouteColors.accent(item.roadClass())

    Box(
        modifier = modifier.heightIn(min = GuidancePanelRowMinHeight),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(GuidancePanelRowMinHeight + 16.dp)
                .offset(y = 8.dp)
                .background(if (isPrimary) panelColors.container.copy(0.2f) else Color.Transparent)
        )

        Column(
            modifier = Modifier
                .padding(
                    start = 20.dp,
                    end = 16.dp,
                )
                .fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .border(
                            width = 4.dp,
                            color = panelColors.primary,
                            shape = CircleShape,
                        )
                        .background(MaterialTheme.colorScheme.surfaceContainer),
                )

                HorizontalDivider(
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.2f),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .height(GuidancePanelRowMinHeight)
                        .width(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    VerticalDivider(
                        modifier = Modifier
                            .requiredHeight(GuidancePanelRowMinHeight + 6.dp)
                            .offset(y = 1.dp),
                        color = panelColors.primary,
                        thickness = 8.dp,
                    )
                }

                MapNavigationGuidancePanelIcon(
                    modifier = Modifier.size(40.dp),
                    item = item,
                    tint = MaterialTheme.colorScheme.onSurface,
                )

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = title,
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )

                    if (subtitle != null) {
                        MapNavigationGuidancePanelSubtitle(
                            subtitle = subtitle,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                            secondaryContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Column(
                    modifier = Modifier.widthIn(min = 64.dp),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    if (etaText != null) {
                        Text(
                            text = etaText,
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )
                    }

                    Text(
                        text = distanceText,
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun MapNavigationGuidancePanelSubtitle(
    subtitle: GuidancePanelSubtitle,
    contentColor: Color,
    secondaryContentColor: Color,
    modifier: Modifier = Modifier,
) {
    when (subtitle) {
        is GuidanceTextPanelSubtitle -> Text(
            modifier = modifier,
            text = stringResource(Res.string.home_map_navigation_panel_direction_sign, subtitle.text),
            color = secondaryContentColor,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        is TollPanelSubtitle -> Text(
            modifier = modifier,
            text = formatYen(subtitle.amountYen),
            color = secondaryContentColor,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        EntrancePanelSubtitle -> Text(
            modifier = modifier,
            text = stringResource(Res.string.home_map_navigation_panel_entrance),
            color = secondaryContentColor,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        ExitPanelSubtitle -> Text(
            modifier = modifier,
            text = stringResource(Res.string.home_map_navigation_panel_exit),
            color = secondaryContentColor,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        is RecommendedLanesPanelSubtitle -> MapNavigationManeuverLaneIcons(
            modifier = modifier,
            lanes = subtitle.lanes,
            iconSize = GuidancePanelSubtitleLaneIconSize,
            spacing = GuidancePanelSubtitleLaneSpacing,
            activeTint = contentColor,
            inactiveTint = secondaryContentColor,
        )
    }
}

@Composable
private fun MapNavigationGuidancePanelIcon(
    item: GuidancePanelItem,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        when (item) {
            is ManeuverPanelItem -> ManeuverIcon(
                modifier = Modifier.size(34.dp),
                type = item.type,
                maneuverModifier = item.modifier,
                contentDescription = null,
                tint = tint,
            )
            is FacilityPanelItem -> MapNavigationFacilityBadge(
                modifier = Modifier.size(width = 38.dp, height = 28.dp),
                facility = item.kind,
                tint = tint,
            )
        }
    }
}

@Composable
private fun MapNavigationFacilityBadge(
    facility: GuidancePanelFacility,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(4.dp),
        color = Color.Transparent,
        border = BorderStroke(2.dp, tint),
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 2.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = facility.badgeText(),
                color = tint,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
    }
}

private fun formatPanelDistance(
    meters: Double,
    meterLabel: String,
    kilometerLabel: String,
): String = formatDistance(
    meters = meters,
    meterLabel = meterLabel,
    kilometerLabel = kilometerLabel,
)

private fun panelDurationText(
    etaEpochMillis: Long?,
    timestampMillis: Long,
    dayLabel: String,
    hourLabel: String,
    minuteLabel: String,
): String? {
    val eta = etaEpochMillis ?: return null
    val remainingSeconds = (eta - timestampMillis)
        .coerceAtLeast(0L)
        .toDouble() / MILLIS_PER_SECOND
    return formatDuration(
        totalSeconds = remainingSeconds,
        dayLabel = dayLabel,
        hourLabel = hourLabel,
        minuteLabel = minuteLabel,
    )
}

@Composable
private fun GuidancePanelItem.panelTitle(): String {
    val fallbackTitle = stringResource(Res.string.home_map_navigation_panel_guidance_point)
    return when (this) {
        is ManeuverPanelItem -> {
            val title = intersectionName?.takeIf { name -> name.isNotBlank() }
            val exitNumberTitle = exitNumber?.takeIf { number -> number.isNotBlank() }
            title ?: exitNumberTitle ?: fallbackTitle
        }
        is FacilityPanelItem ->
            name.takeIf { value -> value.isNotBlank() } ?: fallbackTitle
    }
}

@Composable
private fun GuidancePanelFacility.badgeText(): String = when (this) {
    GuidancePanelFacility.IC ->
        stringResource(Res.string.home_map_navigation_panel_facility_ic)
    GuidancePanelFacility.JCT ->
        stringResource(Res.string.home_map_navigation_panel_facility_jct)
    GuidancePanelFacility.SA ->
        stringResource(Res.string.home_map_navigation_panel_facility_sa)
    GuidancePanelFacility.PA ->
        stringResource(Res.string.home_map_navigation_panel_facility_pa)
    GuidancePanelFacility.TOLL_GATE ->
        stringResource(Res.string.home_map_navigation_panel_facility_toll_gate_badge)
}

private fun GuidancePanelItem.roadClass(): RoadClass = when (this) {
    is ManeuverPanelItem -> roadClass
    is FacilityPanelItem -> roadClass
}

private val GuidancePanelRowMinHeight = 60.dp
private val GuidancePanelSubtitleLaneIconSize = 22.dp
private val GuidancePanelSubtitleLaneSpacing = 6.dp
private const val MILLIS_PER_SECOND: Double = 1_000.0
