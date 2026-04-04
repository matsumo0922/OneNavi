package me.matsumo.onenavi.feature.home.map.components.guidance

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.matsumo.onenavi.core.common.formatDistance
import me.matsumo.onenavi.core.common.formatDuration
import me.matsumo.onenavi.core.model.TripProgressInfo
import me.matsumo.onenavi.core.resource.Res
import me.matsumo.onenavi.core.resource.common_unit_day
import me.matsumo.onenavi.core.resource.common_unit_hour
import me.matsumo.onenavi.core.resource.common_unit_kilometer
import me.matsumo.onenavi.core.resource.common_unit_meter
import me.matsumo.onenavi.core.resource.common_unit_minute
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun HomeMapGuidanceTripCard(
    tripProgress: TripProgressInfo,
    onStopClicked: () -> Unit,
    onAddWaypointClicked: () -> Unit,
    onOverviewClicked: () -> Unit,
    onMoreClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dayLabel = stringResource(Res.string.common_unit_day)
    val hourLabel = stringResource(Res.string.common_unit_hour)
    val minuteLabel = stringResource(Res.string.common_unit_minute)
    val meterLabel = stringResource(Res.string.common_unit_meter)
    val kilometerLabel = stringResource(Res.string.common_unit_kilometer)

    val durationText = remember(tripProgress.durationRemainingSeconds, dayLabel, hourLabel, minuteLabel) {
        formatDuration(
            totalSeconds = tripProgress.durationRemainingSeconds,
            dayLabel = dayLabel,
            hourLabel = hourLabel,
            minuteLabel = minuteLabel,
        )
    }

    val distanceText = remember(tripProgress.distanceRemainingMeters, meterLabel, kilometerLabel) {
        formatDistance(
            meters = tripProgress.distanceRemainingMeters,
            meterLabel = meterLabel,
            kilometerLabel = kilometerLabel,
        )
    }

    val etaText = remember(tripProgress.estimatedArrivalTimeMillis) {
        val calendar = java.util.Calendar.getInstance().apply {
            timeInMillis = tripProgress.estimatedArrivalTimeMillis
        }
        val hour = calendar.get(java.util.Calendar.HOUR_OF_DAY)
        val minute = calendar.get(java.util.Calendar.MINUTE)
        "$hour:%02d".format(minute)
    }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(NavigationColors.tripCardBackground)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = durationText,
                color = NavigationColors.tripCardText,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )

            Text(
                text = distanceText,
                color = NavigationColors.tripCardSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )

            Text(
                text = "到着 $etaText",
                color = NavigationColors.tripCardSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val iconButtonColors = IconButtonDefaults.iconButtonColors(
                contentColor = NavigationColors.controlIcon,
            )

            IconButton(
                onClick = onStopClicked,
                colors = iconButtonColors,
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "ナビ終了",
                )
            }

            IconButton(
                onClick = onAddWaypointClicked,
                colors = iconButtonColors,
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "経由地追加",
                )
            }

            IconButton(
                onClick = onOverviewClicked,
                colors = iconButtonColors,
            ) {
                Icon(
                    imageVector = Icons.Default.Route,
                    contentDescription = "ルート全体表示",
                )
            }

            IconButton(
                onClick = onMoreClicked,
                colors = iconButtonColors,
            ) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "その他",
                )
            }
        }
    }
}
