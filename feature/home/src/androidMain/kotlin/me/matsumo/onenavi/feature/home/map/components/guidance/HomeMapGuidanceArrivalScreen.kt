package me.matsumo.onenavi.feature.home.map.components.guidance

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import me.matsumo.onenavi.core.common.formatDistance
import me.matsumo.onenavi.core.common.formatDuration
import me.matsumo.onenavi.core.model.ArrivalInfo
import me.matsumo.onenavi.core.resource.Res
import me.matsumo.onenavi.core.resource.common_unit_day
import me.matsumo.onenavi.core.resource.common_unit_hour
import me.matsumo.onenavi.core.resource.common_unit_kilometer
import me.matsumo.onenavi.core.resource.common_unit_meter
import me.matsumo.onenavi.core.resource.common_unit_minute
import org.jetbrains.compose.resources.stringResource

private const val AUTO_DISMISS_DELAY_MS = 10_000L

@Composable
internal fun HomeMapGuidanceArrivalScreen(
    arrivalInfo: ArrivalInfo,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dayLabel = stringResource(Res.string.common_unit_day)
    val hourLabel = stringResource(Res.string.common_unit_hour)
    val minuteLabel = stringResource(Res.string.common_unit_minute)
    val meterLabel = stringResource(Res.string.common_unit_meter)
    val kilometerLabel = stringResource(Res.string.common_unit_kilometer)

    val durationText = remember(arrivalInfo.totalDurationSeconds, dayLabel, hourLabel, minuteLabel) {
        formatDuration(
            totalSeconds = arrivalInfo.totalDurationSeconds,
            dayLabel = dayLabel,
            hourLabel = hourLabel,
            minuteLabel = minuteLabel,
        )
    }

    val distanceText = remember(arrivalInfo.totalDistanceMeters, meterLabel, kilometerLabel) {
        formatDistance(
            meters = arrivalInfo.totalDistanceMeters,
            meterLabel = meterLabel,
            kilometerLabel = kilometerLabel,
        )
    }

    LaunchedEffect(Unit) {
        delay(AUTO_DISMISS_DELAY_MS)
        onDismiss()
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(24.dp))
                .background(NavigationColors.arrivalBackground)
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "目的地に到着しました",
                color = NavigationColors.arrivalText,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )

            if (arrivalInfo.destinationName.isNotBlank()) {
                Text(
                    text = arrivalInfo.destinationName,
                    color = NavigationColors.arrivalText.copy(alpha = 0.9f),
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            Text(
                text = "$distanceText / $durationText",
                color = NavigationColors.arrivalText.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodyMedium,
            )

            Button(onDismiss) {
                Text("閉じる")
            }
        }
    }
}
