package me.matsumo.onenavi.feature.map.components.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.chrisbanes.haze.HazeState
import kotlinx.collections.immutable.ImmutableList
import me.matsumo.onenavi.core.common.formatDistance
import me.matsumo.onenavi.core.model.RoadClass
import me.matsumo.onenavi.core.navigation.newguidance.model.GuidanceManeuverInfo
import me.matsumo.onenavi.core.navigation.newguidance.model.GuidanceProgress
import me.matsumo.onenavi.core.navigation.newguidance.model.Lane
import me.matsumo.onenavi.core.navigation.newguidance.model.LaneGuidance
import me.matsumo.onenavi.core.resource.Res
import me.matsumo.onenavi.core.resource.common_unit_day
import me.matsumo.onenavi.core.resource.common_unit_hour
import me.matsumo.onenavi.core.resource.common_unit_kilometer
import me.matsumo.onenavi.core.resource.common_unit_meter
import me.matsumo.onenavi.core.resource.common_unit_minute
import me.matsumo.onenavi.core.resource.home_map_navigation_followup
import me.matsumo.onenavi.core.ui.navigation.ManeuverIcon
import me.matsumo.onenavi.core.ui.theme.RouteColors
import org.jetbrains.compose.resources.stringResource
import kotlin.math.floor

@Composable
internal fun MapNavigationManeuverPanel(
    progress: GuidanceProgress,
    hazeState: HazeState,
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
    val hasLanes = laneGuidance != null
    val hasHint = !hasLanes && nextManeuver != null
    val topShape = when {
        hasLanes -> RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
        hasHint -> RoundedCornerShape(
            topStart = 16.dp,
            topEnd = 16.dp,
            bottomStart = 0.dp,
            bottomEnd = 16.dp,
        )
        else -> RoundedCornerShape(16.dp)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        if (hasPanelItems && showPanel) {
            MapNavigationManeuverPanelSection(
                modifier = Modifier.fillMaxWidth(),
                panelItems = progress.panelItems,
                hazeState = hazeState,
                meterLabel = meterLabel,
                kilometerLabel = kilometerLabel,
                dayLabel = dayLabel,
                hourLabel = hourLabel,
                minuteLabel = minuteLabel,
                timestampMillis = progress.locationTimestampMillis,
                onDismissPanelClicked = { showPanel = false },
            )
        } else {
            MapNavigationManeuverTopSection(
                modifier = Modifier.fillMaxWidth(),
                progress = progress,
                maneuver = currentManeuver,
                meterLabel = meterLabel,
                kilometerLabel = kilometerLabel,
                showPanelItems = showPanel,
                shape = topShape,
                onShowPanelItemsClicked = { showPanel = true },
            )

            MapNavigationManeuverBottomSection(
                modifier = Modifier.fillMaxWidth(),
                laneGuidance = laneGuidance,
                nextManeuver = nextManeuver,
                followupLabel = followupLabel,
                currentRoadClass = progress.currentRoadClass,
            )
        }
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
            ?: progress.directionSign?.primary?.takeIf { primary -> primary.isNotBlank() }
            ?: progress.currentRoadName?.takeIf { roadName -> roadName.isNotBlank() }
            ?: maneuver.exitNumber?.takeIf { exitNumber -> exitNumber.isNotBlank() }
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
    currentRoadClass: RoadClass,
    modifier: Modifier = Modifier,
) {
    when {
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

private val ManeuverLaneIconSize = 36.dp
private val ManeuverLaneIconSpacing = 12.dp
