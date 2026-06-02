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
import androidx.compose.material3.HorizontalDivider
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
import dev.chrisbanes.haze.HazeState
import kotlinx.collections.immutable.ImmutableList
import me.matsumo.onenavi.core.common.formatDistance
import me.matsumo.onenavi.core.common.formatDuration
import me.matsumo.onenavi.core.common.formatYen
import me.matsumo.onenavi.core.model.RoadClass
import me.matsumo.onenavi.core.navigation.newguidance.presentation.GuidanceListDetail
import me.matsumo.onenavi.core.navigation.newguidance.presentation.GuidanceListIcon
import me.matsumo.onenavi.core.navigation.newguidance.presentation.GuidanceListItem
import me.matsumo.onenavi.core.navigation.newguidance.presentation.LanePresentation
import me.matsumo.onenavi.core.navigation.newguidance.semantic.FacilityKind
import me.matsumo.onenavi.core.navigation.newguidance.semantic.HighwayBoundary
import me.matsumo.onenavi.core.resource.Res
import me.matsumo.onenavi.core.resource.home_map_navigation_panel_direction_sign
import me.matsumo.onenavi.core.resource.home_map_navigation_panel_entrance
import me.matsumo.onenavi.core.resource.home_map_navigation_panel_exit
import me.matsumo.onenavi.core.resource.home_map_navigation_panel_facility_ic
import me.matsumo.onenavi.core.resource.home_map_navigation_panel_facility_jct
import me.matsumo.onenavi.core.resource.home_map_navigation_panel_facility_pa
import me.matsumo.onenavi.core.resource.home_map_navigation_panel_facility_sa
import me.matsumo.onenavi.core.resource.home_map_navigation_panel_facility_toll_gate_badge
import me.matsumo.onenavi.core.resource.home_map_navigation_panel_guidance_point
import me.matsumo.onenavi.core.resource.home_map_route_origin_current_location
import me.matsumo.onenavi.core.resource.ic_vehicle_puck
import me.matsumo.onenavi.core.ui.navigation.ManeuverIcon
import me.matsumo.onenavi.core.ui.theme.RouteColors
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

@Suppress("UnusedParameter")
@Composable
internal fun MapNavigationManeuverPanelSection(
    listItems: ImmutableList<GuidanceListItem>,
    hazeState: HazeState,
    meterLabel: String,
    kilometerLabel: String,
    dayLabel: String,
    hourLabel: String,
    minuteLabel: String,
    timestampMillis: Long,
    elapsedSeconds: Int,
    traveledMeters: Int,
    onDismissPanelClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val screenHeight = LocalWindowInfo.current.containerDpSize.height
    val roadClass = listItems.lastOrNull()?.roadClass ?: RoadClass.ORDINARY
    val panelColors = RouteColors.accent(roadClass)
    val shape = RoundedCornerShape(
        topStart = 0.dp,
        topEnd = 0.dp,
        bottomStart = 16.dp,
        bottomEnd = 16.dp,
    )

    Surface(
        modifier = modifier
            .height(screenHeight / 3f)
            .shadow(
                elevation = 8.dp,
                shape = shape,
            ),
        shape = shape,
        color = panelColors.primary,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
        ) {
            itemsIndexed(
                items = listItems,
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
                    isPrimary = index == listItems.lastIndex,
                )
            }

            item(key = "footer") {
                MapNavigationGuidancePanelFooter(
                    modifier = Modifier.fillMaxWidth(),
                    roadClass = roadClass,
                    elapsedSeconds = elapsedSeconds,
                    traveledMeters = traveledMeters,
                    meterLabel = meterLabel,
                    kilometerLabel = kilometerLabel,
                    dayLabel = dayLabel,
                    hourLabel = hourLabel,
                    minuteLabel = minuteLabel,
                )
            }
        }
    }
}

@Composable
private fun MapNavigationGuidancePanelFooter(
    roadClass: RoadClass,
    elapsedSeconds: Int,
    traveledMeters: Int,
    meterLabel: String,
    kilometerLabel: String,
    dayLabel: String,
    hourLabel: String,
    minuteLabel: String,
    modifier: Modifier = Modifier,
) {
    val panelColors = RouteColors.accent(roadClass)
    val elapsedText = remember(elapsedSeconds, dayLabel, hourLabel, minuteLabel) {
        formatDuration(
            totalSeconds = elapsedSeconds.toDouble(),
            dayLabel = dayLabel,
            hourLabel = hourLabel,
            minuteLabel = minuteLabel,
        )
    }
    val traveledText = remember(traveledMeters, meterLabel, kilometerLabel) {
        formatDistance(
            meters = traveledMeters.toDouble(),
            meterLabel = meterLabel,
            kilometerLabel = kilometerLabel,
        )
    }

    Column(
        modifier = modifier.padding(bottom = 16.dp),
        horizontalAlignment = Alignment.Start,
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
                    color = panelColors.container,
                    thickness = 8.dp,
                )
            }

            HorizontalDivider(
                modifier = Modifier.weight(1f),
                color = panelColors.onPrimary.copy(0.2f),
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
                color = panelColors.onPrimary,
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
                    text = elapsedText,
                    color = panelColors.onPrimary,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )

                Text(
                    text = traveledText,
                    color = panelColors.onPrimary,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun MapNavigationGuidancePanelRow(
    item: GuidanceListItem,
    meterLabel: String,
    kilometerLabel: String,
    dayLabel: String,
    hourLabel: String,
    minuteLabel: String,
    timestampMillis: Long,
    isPrimary: Boolean,
    modifier: Modifier = Modifier,
) {
    val distanceText = remember(item.distanceMeters, meterLabel, kilometerLabel) {
        formatPanelDistance(
            meters = item.distanceMeters.toDouble(),
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
    val detail = item.detail
    val panelColors = RouteColors.accent(item.roadClass)

    Box(
        modifier = modifier.heightIn(min = GuidancePanelRowMinHeight),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(GuidancePanelRowMinHeight + 16.dp)
                .offset(y = 8.dp)
                .background(if (isPrimary) panelColors.container.copy(0.2f) else Color.Transparent),
        )

        Column(
            modifier = Modifier
                .padding(
                    start = 20.dp,
                    end = 16.dp,
                )
                .fillMaxWidth(),
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
                            color = panelColors.container,
                            shape = CircleShape,
                        )
                        .background(MaterialTheme.colorScheme.surfaceContainer),
                )

                HorizontalDivider(
                    modifier = Modifier.weight(1f),
                    color = panelColors.onPrimary.copy(0.2f),
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
                        color = panelColors.container,
                        thickness = 8.dp,
                    )
                }

                MapNavigationGuidancePanelIcon(
                    modifier = Modifier.size(40.dp),
                    icon = item.icon,
                    tint = panelColors.onPrimary,
                )

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = title,
                        color = panelColors.onPrimary,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )

                    if (detail != null) {
                        MapNavigationGuidancePanelDetail(
                            detail = detail,
                            contentColor = panelColors.onPrimary,
                            secondaryContentColor = panelColors.onPrimary,
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
                            color = panelColors.onPrimary,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )
                    }

                    Text(
                        text = distanceText,
                        color = panelColors.onPrimary,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun MapNavigationGuidancePanelDetail(
    detail: GuidanceListDetail,
    contentColor: Color,
    secondaryContentColor: Color,
    modifier: Modifier = Modifier,
) {
    when (detail) {
        is GuidanceListDetail.Signpost -> Text(
            modifier = modifier,
            text = stringResource(Res.string.home_map_navigation_panel_direction_sign, detail.text),
            color = secondaryContentColor,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        is GuidanceListDetail.Toll -> Text(
            modifier = modifier,
            text = formatYen(detail.amountYen),
            color = secondaryContentColor,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        is GuidanceListDetail.Boundary -> Text(
            modifier = modifier,
            text = stringResource(detail.kind.boundaryLabel()),
            color = secondaryContentColor,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        is GuidanceListDetail.Warning -> Text(
            modifier = modifier,
            text = detail.text,
            color = secondaryContentColor,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        is GuidanceListDetail.Lanes -> MapNavigationGuidancePanelLaneDetail(
            modifier = modifier,
            lane = detail.lane,
            contentColor = contentColor,
            secondaryContentColor = secondaryContentColor,
        )
    }
}

@Composable
private fun MapNavigationGuidancePanelLaneDetail(
    lane: LanePresentation,
    contentColor: Color,
    secondaryContentColor: Color,
    modifier: Modifier = Modifier,
) {
    // テキスト由来のレーン (側 + 本数 / 警告文) は発話テキスト解析経路で対応する。現状は視覚レーンのみ描く。
    val visualLanes = lane as? LanePresentation.VisualLanes ?: return
    MapNavigationManeuverLaneIcons(
        modifier = modifier,
        lanes = visualLanes.lanes,
        iconSize = GuidancePanelSubtitleLaneIconSize,
        spacing = GuidancePanelSubtitleLaneSpacing,
        activeTint = contentColor,
        inactiveTint = secondaryContentColor,
    )
}

@Composable
private fun MapNavigationGuidancePanelIcon(
    icon: GuidanceListIcon,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        when (icon) {
            is GuidanceListIcon.Maneuver -> ManeuverIcon(
                modifier = Modifier.size(34.dp),
                type = icon.type,
                maneuverModifier = icon.modifier,
                contentDescription = null,
                tint = tint,
            )

            is GuidanceListIcon.FacilityBadge -> MapNavigationFacilityBadge(
                modifier = Modifier.size(width = 38.dp, height = 28.dp),
                facility = icon.kind,
                tint = tint,
            )
        }
    }
}

@Composable
private fun MapNavigationFacilityBadge(
    facility: FacilityKind,
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
                text = stringResource(facility.badgeLabel()),
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
private fun GuidanceListItem.panelTitle(): String {
    val fallbackTitle = stringResource(Res.string.home_map_navigation_panel_guidance_point)
    return title?.takeIf { value -> value.isNotBlank() } ?: fallbackTitle
}

/** 境界種別ごとの表示文字列リソースを返す。 */
private fun HighwayBoundary.boundaryLabel() = when (this) {
    HighwayBoundary.ENTRANCE -> Res.string.home_map_navigation_panel_entrance
    HighwayBoundary.EXIT -> Res.string.home_map_navigation_panel_exit
}

/** 施設種別ごとのバッジ文字列リソースを返す。 */
private fun FacilityKind.badgeLabel() = when (this) {
    FacilityKind.IC -> Res.string.home_map_navigation_panel_facility_ic
    FacilityKind.JCT -> Res.string.home_map_navigation_panel_facility_jct
    FacilityKind.SA -> Res.string.home_map_navigation_panel_facility_sa
    FacilityKind.PA -> Res.string.home_map_navigation_panel_facility_pa
    FacilityKind.TOLL_GATE -> Res.string.home_map_navigation_panel_facility_toll_gate_badge
}

private val GuidancePanelRowMinHeight = 60.dp
private val GuidancePanelSubtitleLaneIconSize = 22.dp
private val GuidancePanelSubtitleLaneSpacing = 6.dp
private const val MILLIS_PER_SECOND: Double = 1_000.0
