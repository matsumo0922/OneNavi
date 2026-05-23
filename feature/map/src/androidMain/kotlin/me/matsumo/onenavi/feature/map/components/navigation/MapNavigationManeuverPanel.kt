package me.matsumo.onenavi.feature.map.components.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.collections.immutable.ImmutableList
import me.matsumo.onenavi.core.common.formatDistance
import me.matsumo.onenavi.core.model.ManeuverType
import me.matsumo.onenavi.core.navigation.newguidance.model.GuidanceManeuverInfo
import me.matsumo.onenavi.core.navigation.newguidance.model.GuidanceProgress
import me.matsumo.onenavi.core.navigation.newguidance.model.Lane
import me.matsumo.onenavi.core.navigation.newguidance.model.LaneGuidance
import me.matsumo.onenavi.core.resource.Res
import me.matsumo.onenavi.core.resource.common_unit_kilometer
import me.matsumo.onenavi.core.resource.common_unit_meter
import me.matsumo.onenavi.core.resource.home_map_navigation_followup
import me.matsumo.onenavi.core.ui.navigation.ManeuverIcon
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
    val followupLabel = stringResource(Res.string.home_map_navigation_followup)

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

    Column(modifier = modifier) {
        MapNavigationManeuverTopSection(
            modifier = Modifier.fillMaxWidth(),
            progress = progress,
            maneuver = currentManeuver,
            meterLabel = meterLabel,
            kilometerLabel = kilometerLabel,
            shape = topShape,
        )

        MapNavigationManeuverBottomSection(
            modifier = Modifier.fillMaxWidth(),
            laneGuidance = laneGuidance,
            nextManeuver = nextManeuver,
            followupLabel = followupLabel,
        )
    }
}

@Composable
private fun MapNavigationManeuverTopSection(
    progress: GuidanceProgress,
    maneuver: GuidanceManeuverInfo,
    meterLabel: String,
    kilometerLabel: String,
    shape: Shape,
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

    Surface(
        modifier = modifier
            .zIndex(1f)
            .shadow(
                elevation = 8.dp,
                shape = shape,
            ),
        shape = shape,
        color = ManeuverPanelBackgroundColor,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MapNavigationManeuverTurnIcon(
                modifier = Modifier.size(48.dp),
                maneuver = maneuver,
            )

            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = distanceText,
                    color = ManeuverPanelPrimaryTextColor,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )

                if (guidanceLabel != null) {
                    Text(
                        text = guidanceLabel,
                        color = ManeuverPanelPrimaryTextColor,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
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
    modifier: Modifier = Modifier,
) {
    when {
        laneGuidance != null -> {
            Surface(
                modifier = modifier,
                shape = RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp),
                color = ManeuverPanelSecondaryBackgroundColor,
            ) {
                MapNavigationManeuverLaneRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    lanes = laneGuidance.lanes,
                )
            }
        }

        nextManeuver != null -> {
            Surface(
                modifier = modifier.wrapContentWidth(Alignment.Start),
                shape = RoundedCornerShape(
                    topStart = 0.dp,
                    topEnd = 0.dp,
                    bottomStart = 16.dp,
                    bottomEnd = 12.dp,
                ),
                color = ManeuverPanelSecondaryBackgroundColor,
            ) {
                MapNavigationManeuverFollowupHint(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    maneuver = nextManeuver,
                    label = followupLabel,
                )
            }
        }
    }
}

@Composable
private fun MapNavigationManeuverLaneRow(
    lanes: ImmutableList<Lane>,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        lanes.forEach { lane ->
            MapNavigationManeuverLaneIcon(
                modifier = Modifier.size(36.dp),
                lane = lane,
            )
        }
    }
}

@Composable
private fun MapNavigationManeuverFollowupHint(
    maneuver: GuidanceManeuverInfo,
    label: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = ManeuverPanelPrimaryTextColor,
            style = MaterialTheme.typography.labelLarge,
        )

        MapNavigationManeuverTurnIcon(
            modifier = Modifier.size(20.dp),
            maneuver = maneuver,
        )
    }
}

@Composable
private fun MapNavigationManeuverTurnIcon(
    maneuver: GuidanceManeuverInfo,
    modifier: Modifier = Modifier,
) {
    ManeuverIcon(
        modifier = modifier,
        type = maneuver.type,
        maneuverModifier = maneuver.modifier,
        contentDescription = null,
        tint = ManeuverPanelPrimaryTextColor,
    )
}

@Composable
private fun MapNavigationManeuverLaneIcon(
    lane: Lane,
    modifier: Modifier = Modifier,
) {
    val direction = lane.recommendedDirection ?: lane.allowedDirections.firstOrNull()

    ManeuverIcon(
        modifier = modifier,
        type = ManeuverType.TURN,
        maneuverModifier = direction,
        contentDescription = null,
        tint = if (lane.isActive) {
            ManeuverPanelPrimaryTextColor
        } else {
            ManeuverPanelSecondaryTextColor
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

private val ManeuverPanelBackgroundColor = Color(0xFF2E63E0)
private val ManeuverPanelSecondaryBackgroundColor = Color(0xFF1E4BB8)
private val ManeuverPanelPrimaryTextColor = Color.White
private val ManeuverPanelSecondaryTextColor = Color.White.copy(alpha = 0.7f)
