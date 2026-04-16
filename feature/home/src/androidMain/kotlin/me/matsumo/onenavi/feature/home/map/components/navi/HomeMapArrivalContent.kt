package me.matsumo.onenavi.feature.home.map.components.navi

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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

@Composable
internal fun HomeMapArrivalContent(
    arrivalInfo: ArrivalInfo?,
    destinationName: String?,
    onFinishClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dayLabel = stringResource(Res.string.common_unit_day)
    val hourLabel = stringResource(Res.string.common_unit_hour)
    val minuteLabel = stringResource(Res.string.common_unit_minute)
    val meterLabel = stringResource(Res.string.common_unit_meter)
    val kilometerLabel = stringResource(Res.string.common_unit_kilometer)

    val title = destinationName
        ?.takeIf { it.isNotBlank() }
        ?: arrivalInfo?.destinationName?.takeIf { it.isNotBlank() }
        ?: "目的地"

    val distanceText = arrivalInfo?.let {
        formatDistance(it.totalDistanceMeters, meterLabel, kilometerLabel)
    }
    val durationText = arrivalInfo?.let {
        formatDuration(it.totalDurationSeconds, dayLabel, hourLabel, minuteLabel)
    }

    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "到着しました",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (distanceText != null && durationText != null) {
                Text(
                    text = "$distanceText  •  $durationText",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = onFinishClicked,
            ) {
                Text("終了")
            }
        }
    }
}
