package me.matsumo.onenavi.feature.map.components.navigation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.collections.immutable.ImmutableList
import me.matsumo.onenavi.core.common.formatDistance
import me.matsumo.onenavi.core.common.formatDuration
import me.matsumo.onenavi.core.common.formatYen
import me.matsumo.onenavi.core.model.ManeuverType
import me.matsumo.onenavi.core.model.RoadClass
import me.matsumo.onenavi.core.navigation.newguidance.model.EntrancePanelSubtitle
import me.matsumo.onenavi.core.navigation.newguidance.model.ExitPanelSubtitle
import me.matsumo.onenavi.core.navigation.newguidance.model.FacilityPanelItem
import me.matsumo.onenavi.core.navigation.newguidance.model.GuidanceManeuverInfo
import me.matsumo.onenavi.core.navigation.newguidance.model.GuidancePanelFacility
import me.matsumo.onenavi.core.navigation.newguidance.model.GuidancePanelItem
import me.matsumo.onenavi.core.navigation.newguidance.model.GuidancePanelSubtitle
import me.matsumo.onenavi.core.navigation.newguidance.model.GuidanceProgress
import me.matsumo.onenavi.core.navigation.newguidance.model.GuidanceTextPanelSubtitle
import me.matsumo.onenavi.core.navigation.newguidance.model.Lane
import me.matsumo.onenavi.core.navigation.newguidance.model.LaneGuidance
import me.matsumo.onenavi.core.navigation.newguidance.model.ManeuverPanelItem
import me.matsumo.onenavi.core.navigation.newguidance.model.RecommendedLanesPanelSubtitle
import me.matsumo.onenavi.core.navigation.newguidance.model.TollPanelSubtitle
import me.matsumo.onenavi.core.resource.Res
import me.matsumo.onenavi.core.resource.common_unit_day
import me.matsumo.onenavi.core.resource.common_unit_hour
import me.matsumo.onenavi.core.resource.common_unit_kilometer
import me.matsumo.onenavi.core.resource.common_unit_meter
import me.matsumo.onenavi.core.resource.common_unit_minute
import me.matsumo.onenavi.core.resource.home_map_navigation_followup
import me.matsumo.onenavi.core.resource.home_map_navigation_panel_direction_sign
import me.matsumo.onenavi.core.resource.home_map_navigation_panel_entrance
import me.matsumo.onenavi.core.resource.home_map_navigation_panel_exit
import me.matsumo.onenavi.core.resource.home_map_navigation_panel_facility_ic
import me.matsumo.onenavi.core.resource.home_map_navigation_panel_facility_jct
import me.matsumo.onenavi.core.resource.home_map_navigation_panel_facility_pa
import me.matsumo.onenavi.core.resource.home_map_navigation_panel_facility_sa
import me.matsumo.onenavi.core.resource.home_map_navigation_panel_facility_toll_gate_badge
import me.matsumo.onenavi.core.resource.home_map_navigation_panel_guidance_point
import me.matsumo.onenavi.core.ui.navigation.ManeuverIcon
import me.matsumo.onenavi.core.ui.theme.RouteColors
import org.jetbrains.compose.resources.stringResource
import kotlin.math.floor

@Composable
internal fun MapNavigationManeuverPanel(
    progress: GuidanceProgress,
    modifier: Modifier = Modifier,
) {
    val currentManeuver = progress.nextManeuver ?: return
    val nextManeuver = progress.followupManeuver
    val laneGuidance = progress.lanes.firstOrNull { laneGuidance ->
        laneGuidance.lanes.isNotEmpty()
    }
    val meterLabel = stringResource(Res.string.common_unit_meter)
    val kilometerLabel = stringResource(Res.string.common_unit_kilometer)
    val dayLabel = stringResource(Res.string.common_unit_day)
    val hourLabel = stringResource(Res.string.common_unit_hour)
    val minuteLabel = stringResource(Res.string.common_unit_minute)
    val followupLabel = stringResource(Res.string.home_map_navigation_followup)
    var showPanel by remember { mutableStateOf(false) }

    val hasPanelItems = progress.panelItems.isNotEmpty()
    val hasPanelBottom = showPanel && hasPanelItems
    val hasLanes = laneGuidance != null
    val hasHint = !hasLanes && nextManeuver != null
    val topShape = when {
        hasPanelBottom || hasLanes -> RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
        hasHint -> RoundedCornerShape(
            topStart = 16.dp,
            topEnd = 16.dp,
            bottomStart = 0.dp,
            bottomEnd = 16.dp,
        )
        else -> RoundedCornerShape(16.dp)
    }

    Column(modifier = modifier) {
        MapNavigationManeuverTopSection(
            modifier = Modifier.fillMaxWidth(),
            progress = progress,
            maneuver = currentManeuver,
            meterLabel = meterLabel,
            kilometerLabel = kilometerLabel,
            showPanelItems = showPanel,
            shape = topShape,
            onShowPanelItemsClicked = { showPanel = !showPanel },
        )

        MapNavigationManeuverBottomSection(
            modifier = Modifier.fillMaxWidth(),
            laneGuidance = laneGuidance,
            nextManeuver = nextManeuver,
            followupLabel = followupLabel,
            panelItems = progress.panelItems,
            showPanelItems = showPanel,
            currentRoadClass = progress.currentRoadClass,
            meterLabel = meterLabel,
            kilometerLabel = kilometerLabel,
            dayLabel = dayLabel,
            hourLabel = hourLabel,
            minuteLabel = minuteLabel,
            timestampMillis = progress.locationTimestampMillis,
        )
    }
}

@Composable
private fun MapNavigationManeuverTopSection(
    progress: GuidanceProgress,
    maneuver: GuidanceManeuverInfo,
    meterLabel: String,
    kilometerLabel: String,
    showPanelItems: Boolean,
    shape: Shape,
    onShowPanelItemsClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val distanceText = remember(maneuver.distanceToManeuverMeters, meterLabel, kilometerLabel) {
        formatGuidanceDistance(
            meters = maneuver.distanceToManeuverMeters.toDouble(),
            meterLabel = meterLabel,
            kilometerLabel = kilometerLabel,
        )
    }
    val guidanceLabel = remember(
        progress.directionSign,
        progress.currentRoadName,
        maneuver.exitNumber,
        maneuver.intersectionName,
    ) {
        maneuver.intersectionName
            ?.takeIf { intersectionName -> intersectionName.isNotBlank() }
            ?: progress.directionSign
                ?.primary
                ?.takeIf { primary -> primary.isNotBlank() }
            ?: progress.currentRoadName
                ?.takeIf { roadName -> roadName.isNotBlank() }
            ?: maneuver.exitNumber
                ?.takeIf { exitNumber -> exitNumber.isNotBlank() }
    }
    val panelColors = RouteColors.accent(progress.currentRoadClass)
    val contentColor = panelColors.onPrimary

    Surface(
        modifier = modifier
            .zIndex(1f)
            .shadow(
                elevation = 8.dp,
                shape = shape,
            ),
        shape = shape,
        color = panelColors.primary,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MapNavigationManeuverTurnIcon(
                modifier = Modifier.size(48.dp),
                maneuver = maneuver,
                tint = contentColor,
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = distanceText,
                    color = contentColor,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )

                if (guidanceLabel != null) {
                    Text(
                        text = guidanceLabel,
                        color = contentColor,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (progress.panelItems.isNotEmpty()) {
                IconButton(onShowPanelItemsClicked) {
                    Icon(
                        imageVector = if (showPanelItems) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                        contentDescription = null,
                        tint = contentColor,
                    )
                }
            }
        }
    }
}

@Composable
private fun MapNavigationManeuverBottomSection(
    laneGuidance: LaneGuidance?,
    nextManeuver: GuidanceManeuverInfo?,
    followupLabel: String,
    panelItems: ImmutableList<GuidancePanelItem>,
    showPanelItems: Boolean,
    currentRoadClass: RoadClass,
    meterLabel: String,
    kilometerLabel: String,
    dayLabel: String,
    hourLabel: String,
    minuteLabel: String,
    timestampMillis: Long,
    modifier: Modifier = Modifier,
) {
    when {
        showPanelItems && panelItems.isNotEmpty() -> {
            Surface(
                modifier = modifier,
                shape = RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp),
                color = RouteColors.accent(panelItems.first().roadClass()).primary,
            ) {
                MapNavigationGuidancePanelList(
                    modifier = Modifier.fillMaxWidth(),
                    panelItems = panelItems,
                    meterLabel = meterLabel,
                    kilometerLabel = kilometerLabel,
                    dayLabel = dayLabel,
                    hourLabel = hourLabel,
                    minuteLabel = minuteLabel,
                    timestampMillis = timestampMillis,
                )
            }
        }

        laneGuidance != null -> {
            val panelColors = RouteColors.accent(currentRoadClass)
            Surface(
                modifier = modifier,
                shape = RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp),
                color = panelColors.primary,
            ) {
                MapNavigationManeuverLaneRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    lanes = laneGuidance.lanes,
                    activeTint = panelColors.onPrimary,
                    inactiveTint = panelColors.onPrimary.copy(alpha = SecondaryContentAlpha),
                )
            }
        }

        nextManeuver != null -> {
            val panelColors = RouteColors.accent(currentRoadClass)
            Surface(
                modifier = modifier.wrapContentWidth(Alignment.Start),
                shape = RoundedCornerShape(
                    topStart = 0.dp,
                    topEnd = 0.dp,
                    bottomStart = 16.dp,
                    bottomEnd = 12.dp,
                ),
                color = panelColors.primary,
            ) {
                MapNavigationManeuverFollowupHint(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    maneuver = nextManeuver,
                    label = followupLabel,
                    contentColor = panelColors.onPrimary,
                )
            }
        }
    }
}

@Composable
private fun MapNavigationGuidancePanelList(
    panelItems: ImmutableList<GuidancePanelItem>,
    meterLabel: String,
    kilometerLabel: String,
    dayLabel: String,
    hourLabel: String,
    minuteLabel: String,
    timestampMillis: Long,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.heightIn(max = GuidancePanelListMaxHeight),
        contentPadding = PaddingValues(vertical = 2.dp),
    ) {
        itemsIndexed(
            items = panelItems,
            key = { _, item -> item.id },
        ) { index, item ->
            if (index > 0) {
                MapNavigationGuidancePanelDivider(
                    roadClass = item.roadClass(),
                )
            }

            MapNavigationGuidancePanelRow(
                modifier = Modifier.fillMaxWidth(),
                item = item,
                meterLabel = meterLabel,
                kilometerLabel = kilometerLabel,
                dayLabel = dayLabel,
                hourLabel = hourLabel,
                minuteLabel = minuteLabel,
                timestampMillis = timestampMillis,
            )
        }
    }
}

@Composable
private fun MapNavigationGuidancePanelDivider(
    roadClass: RoadClass,
    modifier: Modifier = Modifier,
) {
    val panelColors = RouteColors.accent(roadClass)

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = panelColors.primary,
    ) {
        HorizontalDivider(
            color = panelColors.onPrimary.copy(alpha = GuidancePanelDividerAlpha),
            thickness = 1.dp,
        )
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
    val contentColor = panelColors.onPrimary
    val secondaryContentColor = contentColor.copy(alpha = SecondaryContentAlpha)

    Surface(
        modifier = modifier,
        color = panelColors.primary,
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = GuidancePanelRowMinHeight)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MapNavigationGuidancePanelIcon(
                modifier = Modifier.size(44.dp),
                item = item,
                tint = contentColor,
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = title,
                    color = contentColor,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                if (subtitle != null) {
                    MapNavigationGuidancePanelSubtitle(
                        subtitle = subtitle,
                        contentColor = contentColor,
                        secondaryContentColor = secondaryContentColor,
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
                        color = contentColor,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                }

                Text(
                    text = distanceText,
                    color = contentColor,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                )
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

@Composable
private fun MapNavigationManeuverLaneRow(
    lanes: ImmutableList<Lane>,
    activeTint: Color,
    inactiveTint: Color,
    modifier: Modifier = Modifier,
) {
    MapNavigationManeuverLaneIcons(
        modifier = modifier,
        lanes = lanes,
        iconSize = ManeuverLaneIconSize,
        spacing = ManeuverLaneIconSpacing,
        horizontalAlignment = Alignment.CenterHorizontally,
        activeTint = activeTint,
        inactiveTint = inactiveTint,
    )
}

@Composable
private fun MapNavigationManeuverLaneIcons(
    lanes: ImmutableList<Lane>,
    iconSize: Dp,
    spacing: Dp,
    activeTint: Color,
    inactiveTint: Color,
    modifier: Modifier = Modifier,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(spacing, horizontalAlignment),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        lanes.forEach { lane ->
            MapNavigationManeuverLaneIcon(
                modifier = Modifier.size(iconSize),
                lane = lane,
                activeTint = activeTint,
                inactiveTint = inactiveTint,
            )
        }
    }
}

@Composable
private fun MapNavigationManeuverFollowupHint(
    maneuver: GuidanceManeuverInfo,
    label: String,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = contentColor,
            style = MaterialTheme.typography.labelLarge,
        )

        MapNavigationManeuverTurnIcon(
            modifier = Modifier.size(20.dp),
            maneuver = maneuver,
            tint = contentColor,
        )
    }
}

@Composable
private fun MapNavigationManeuverTurnIcon(
    maneuver: GuidanceManeuverInfo,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    ManeuverIcon(
        modifier = modifier,
        type = maneuver.type,
        maneuverModifier = maneuver.modifier,
        contentDescription = null,
        tint = tint,
    )
}

@Composable
private fun MapNavigationManeuverLaneIcon(
    lane: Lane,
    activeTint: Color,
    inactiveTint: Color,
    modifier: Modifier = Modifier,
) {
    val direction = lane.recommendedDirection ?: lane.allowedDirections.firstOrNull()

    ManeuverIcon(
        modifier = modifier,
        type = ManeuverType.TURN,
        maneuverModifier = direction,
        contentDescription = null,
        tint = if (lane.isActive) {
            activeTint
        } else {
            inactiveTint
        },
    )
}

/**
 * ナビ表示用に距離を 10m 単位へ切り捨ててからフォーマットする。
 */
private fun formatGuidanceDistance(
    meters: Double,
    meterLabel: String,
    kilometerLabel: String,
): String {
    val flooredMeters = floor(meters / 10.0) * 10.0
    return formatDistance(
        meters = flooredMeters,
        meterLabel = meterLabel,
        kilometerLabel = kilometerLabel,
    )
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

private const val MILLIS_PER_SECOND: Double = 1_000.0
private const val SecondaryContentAlpha: Float = 0.82f

private val GuidancePanelListMaxHeight = 320.dp
private val GuidancePanelRowMinHeight = 74.dp
private val GuidancePanelSubtitleLaneIconSize = 22.dp
private val GuidancePanelSubtitleLaneSpacing = 6.dp
private val ManeuverLaneIconSize = 36.dp
private val ManeuverLaneIconSpacing = 12.dp
private const val GuidancePanelDividerAlpha: Float = 0.24f
