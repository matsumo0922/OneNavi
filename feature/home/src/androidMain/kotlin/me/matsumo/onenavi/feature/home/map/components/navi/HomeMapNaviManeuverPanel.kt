package me.matsumo.onenavi.feature.home.map.components.navi

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.matsumo.onenavi.core.common.formatDistance
import me.matsumo.onenavi.core.model.ManeuverInfo
import me.matsumo.onenavi.core.resource.Res
import me.matsumo.onenavi.core.resource.common_unit_kilometer
import me.matsumo.onenavi.core.resource.common_unit_meter
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun HomeMapGuidanceManeuverPanel(
    currentManeuver: ManeuverInfo,
    nextManeuver: ManeuverInfo?,
    modifier: Modifier = Modifier,
) {
    val meterLabel = stringResource(Res.string.common_unit_meter)
    val kilometerLabel = stringResource(Res.string.common_unit_kilometer)

    val distanceText = remember(currentManeuver.distanceMeters, meterLabel, kilometerLabel) {
        formatDistance(
            meters = currentManeuver.distanceMeters,
            meterLabel = meterLabel,
            kilometerLabel = kilometerLabel,
        )
    }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(NavigationColors.maneuverBackground)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = maneuverArrow(currentManeuver.type, currentManeuver.modifier),
                color = NavigationColors.maneuverText,
                fontSize = 20.sp,
            )

            Text(
                text = distanceText,
                color = NavigationColors.maneuverDistance,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
        }

        if (currentManeuver.instruction.isNotBlank()) {
            Text(
                text = currentManeuver.instruction,
                color = NavigationColors.maneuverText,
                style = MaterialTheme.typography.bodyLarge,
            )
        }

        if (nextManeuver != null) {
            val nextDistanceText = remember(nextManeuver.distanceMeters, meterLabel, kilometerLabel) {
                formatDistance(
                    meters = nextManeuver.distanceMeters,
                    meterLabel = meterLabel,
                    kilometerLabel = kilometerLabel,
                )
            }

            Text(
                text = "次: $nextDistanceText ${maneuverLabel(nextManeuver.type, nextManeuver.modifier)}",
                color = NavigationColors.maneuverText.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun maneuverArrow(type: String, modifier: String?): String {
    return when {
        modifier == "uturn" -> "↩"
        modifier == "sharp left" -> "↰"
        modifier == "sharp right" -> "↱"
        modifier == "left" -> "←"
        modifier == "right" -> "→"
        modifier == "slight left" -> "↖"
        modifier == "slight right" -> "↗"
        modifier == "straight" -> "↑"
        type == "merge" -> "⇢"
        type == "fork" && modifier == "left" -> "↖"
        type == "fork" && modifier == "right" -> "↗"
        type == "arrive" -> "◎"
        type == "depart" -> "↑"
        else -> "↑"
    }
}

private fun maneuverLabel(type: String, modifier: String?): String {
    return when {
        modifier == "uturn" -> "Uターン"
        modifier == "sharp left" -> "鋭角左折"
        modifier == "sharp right" -> "鋭角右折"
        modifier == "left" -> "左折"
        modifier == "right" -> "右折"
        modifier == "slight left" -> "斜め左"
        modifier == "slight right" -> "斜め右"
        modifier == "straight" -> "直進"
        type == "merge" -> "合流"
        type == "fork" -> "分岐"
        type == "off ramp" -> "出口"
        type == "arrive" -> "到着"
        else -> ""
    }
}
