package me.matsumo.onenavi.feature.home.map.components.navi

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.collections.immutable.ImmutableList
import me.matsumo.onenavi.core.common.formatDistance
import me.matsumo.onenavi.core.model.LaneInfo
import me.matsumo.onenavi.core.model.ManeuverInfo
import me.matsumo.onenavi.core.resource.Res
import me.matsumo.onenavi.core.resource.common_unit_kilometer
import me.matsumo.onenavi.core.resource.common_unit_meter
import me.matsumo.onenavi.core.ui.navigation.ManeuverIcon
import org.jetbrains.compose.resources.stringResource
import kotlin.math.floor

@Composable
internal fun NaviManeuverPanel(
    currentManeuver: ManeuverInfo,
    nextManeuver: ManeuverInfo?,
    modifier: Modifier = Modifier,
) {
    val meterLabel = stringResource(Res.string.common_unit_meter)
    val kilometerLabel = stringResource(Res.string.common_unit_kilometer)

    val hasLanes = currentManeuver.lanes.isNotEmpty()
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
        NaviManeuverTopSection(
            modifier = Modifier.fillMaxWidth(),
            maneuver = currentManeuver,
            meterLabel = meterLabel,
            kilometerLabel = kilometerLabel,
            shape = topShape,
        )

        NaviManeuverBottomSection(
            currentManeuver = currentManeuver,
            nextManeuver = nextManeuver,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun NaviManeuverTopSection(
    maneuver: ManeuverInfo,
    meterLabel: String,
    kilometerLabel: String,
    shape: androidx.compose.ui.graphics.Shape,
    modifier: Modifier = Modifier,
) {
    val distanceText = remember(maneuver.distanceMeters, meterLabel, kilometerLabel) {
        formatGuidanceDistance(maneuver.distanceMeters, meterLabel, kilometerLabel)
    }

    val roadLabel = maneuver.destinations
        ?: maneuver.roadName
        ?: maneuver.instruction.takeIf { it.isNotBlank() }

    Surface(
        modifier = modifier
            .zIndex(1f)
            .shadow(elevation = 8.dp, shape = shape),
        shape = shape,
        color = NavigationColors.maneuverBackground,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ManeuverTurnIcon(
                modifier = Modifier.size(48.dp),
                maneuver = maneuver,
            )

            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = distanceText,
                    color = NavigationColors.maneuverDistance,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )

                if (!roadLabel.isNullOrBlank()) {
                    Text(
                        text = roadLabel,
                        color = NavigationColors.maneuverText,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
    }
}

@Composable
private fun NaviManeuverBottomSection(
    currentManeuver: ManeuverInfo,
    nextManeuver: ManeuverInfo?,
    modifier: Modifier = Modifier,
) {
    val hasLanes = currentManeuver.lanes.isNotEmpty()
    if (!hasLanes && nextManeuver == null) return

    if (hasLanes) {
        Surface(
            modifier = modifier,
            shape = RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp),
            color = NavigationColors.maneuverSecondaryBackground,
        ) {
            NaviLaneRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                lanes = currentManeuver.lanes,
            )
        }
    } else if (nextManeuver != null) {
        Surface(
            modifier = modifier.wrapContentWidth(Alignment.Start),
            shape = RoundedCornerShape(
                topStart = 0.dp,
                topEnd = 0.dp,
                bottomStart = 16.dp,
                bottomEnd = 12.dp,
            ),
            color = NavigationColors.maneuverSecondaryBackground,
        ) {
            NaviNextManeuverHint(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                maneuver = nextManeuver,
            )
        }
    }
}

@Composable
private fun NaviLaneRow(
    lanes: ImmutableList<LaneInfo>,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        lanes.forEach { lane ->
            NaviLaneIcon(
                modifier = Modifier.size(36.dp),
                lane = lane,
            )
        }
    }
}

@Composable
private fun NaviNextManeuverHint(
    maneuver: ManeuverInfo,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "その先",
            color = NavigationColors.maneuverText,
            style = MaterialTheme.typography.labelLarge,
        )

        ManeuverTurnIcon(
            modifier = Modifier.size(20.dp),
            maneuver = maneuver,
        )
    }
}

@Composable
private fun ManeuverTurnIcon(
    maneuver: ManeuverInfo,
    modifier: Modifier = Modifier,
) {
    ManeuverIcon(
        modifier = modifier,
        type = maneuver.type,
        maneuverModifier = maneuver.modifier,
        contentDescription = null,
        tint = NavigationColors.maneuverText,
    )
}

@Composable
private fun NaviLaneIcon(
    lane: LaneInfo,
    modifier: Modifier = Modifier,
) {
    val direction = lane.activeDirection ?: lane.directions.firstOrNull()

    ManeuverIcon(
        modifier = modifier,
        type = "turn",
        maneuverModifier = direction,
        contentDescription = null,
        tint = if (lane.isRecommended) NavigationColors.maneuverText else NavigationColors.maneuverSecondaryText,
    )
}

/**
 * ナビ表示用に距離を 10m 単位へ切り捨ててからフォーマットする。
 * 1km 未満は 10m 単位、1km 以上は既存の `formatDistance` と同じ出力になる。
 */
private fun formatGuidanceDistance(
    meters: Double,
    meterLabel: String,
    kilometerLabel: String,
): String {
    val floored = floor(meters / 10.0) * 10.0
    return formatDistance(
        meters = floored,
        meterLabel = meterLabel,
        kilometerLabel = kilometerLabel,
    )
}
