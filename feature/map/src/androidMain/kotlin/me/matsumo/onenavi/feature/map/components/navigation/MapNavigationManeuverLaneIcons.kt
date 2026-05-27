package me.matsumo.onenavi.feature.map.components.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Alignment.Horizontal
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import kotlinx.collections.immutable.ImmutableList
import me.matsumo.onenavi.core.model.ManeuverType
import me.matsumo.onenavi.core.navigation.newguidance.presentation.LaneCell
import me.matsumo.onenavi.core.ui.navigation.ManeuverIcon

@Composable
internal fun MapNavigationManeuverLaneIcons(
    lanes: ImmutableList<LaneCell>,
    iconSize: Dp,
    spacing: Dp,
    activeTint: Color,
    inactiveTint: Color,
    modifier: Modifier = Modifier,
    horizontalAlignment: Horizontal = Alignment.Start,
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
private fun MapNavigationManeuverLaneIcon(
    lane: LaneCell,
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

internal const val SecondaryContentAlpha: Float = 0.82f
