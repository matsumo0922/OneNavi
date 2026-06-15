package me.matsumo.onenavi.feature.map.components.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.ImmutableList
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import me.matsumo.onenavi.core.common.formatDistance
import me.matsumo.onenavi.core.common.formatDuration
import me.matsumo.onenavi.core.model.CongestionSegment
import me.matsumo.onenavi.core.navigation.newguidance.model.GpsSignalState
import me.matsumo.onenavi.core.navigation.newguidance.model.GuidanceProgress
import me.matsumo.onenavi.core.navigation.newguidance.model.VehiclePositionSource
import me.matsumo.onenavi.core.resource.Res
import me.matsumo.onenavi.core.resource.common_unit_day
import me.matsumo.onenavi.core.resource.common_unit_hour
import me.matsumo.onenavi.core.resource.common_unit_kilometer
import me.matsumo.onenavi.core.resource.common_unit_meter
import me.matsumo.onenavi.core.resource.common_unit_minute
import me.matsumo.onenavi.core.resource.home_map_navigation_eta_add_waypoint
import me.matsumo.onenavi.core.resource.home_map_navigation_eta_alternatives
import me.matsumo.onenavi.core.resource.home_map_navigation_eta_close
import me.matsumo.onenavi.core.resource.home_map_navigation_eta_detour
import me.matsumo.onenavi.feature.map.state.NavigationTrafficLevel
import me.matsumo.onenavi.feature.map.state.calculateNavigationTrafficLevel
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant

/**
 * 案内中に画面下部へ表示する ETA カード。
 *
 * 残り時間・残り距離・到着予想時刻と、案内操作ボタンを並べる。ETA 文字色は残ルート上の
 * 渋滞合計通過時間で 緑 / 黄 / 赤 を出し分ける。各操作ボタンの挙動は呼び出し側に委ねる。
 */
@Composable
internal fun MapNavigationEtaCard(
    progress: GuidanceProgress,
    congestionSegments: ImmutableList<CongestionSegment>,
    gpsSignalState: GpsSignalState,
    displaySpeedKmh: Int?,
    speedLimitKmh: Int?,
    onCloseClicked: () -> Unit,
    onAlternativesClicked: () -> Unit,
    onAddWaypointClicked: () -> Unit,
    onRoutePreviewClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val meterLabel = stringResource(Res.string.common_unit_meter)
    val kilometerLabel = stringResource(Res.string.common_unit_kilometer)
    val dayLabel = stringResource(Res.string.common_unit_day)
    val hourLabel = stringResource(Res.string.common_unit_hour)
    val minuteLabel = stringResource(Res.string.common_unit_minute)

    val trafficLevel = remember(congestionSegments, progress.currentCumulativeMeters) {
        calculateNavigationTrafficLevel(
            congestionSegments = congestionSegments,
            currentCumulativeMeters = progress.currentCumulativeMeters,
        )
    }
    val durationText = remember(progress.durationRemainingSeconds, dayLabel, hourLabel, minuteLabel) {
        formatDuration(
            totalSeconds = progress.durationRemainingSeconds.toDouble(),
            dayLabel = dayLabel,
            hourLabel = hourLabel,
            minuteLabel = minuteLabel,
        )
    }
    val summaryText = remember(
        progress.distanceRemainingMeters,
        progress.etaEpochMillis,
        meterLabel,
        kilometerLabel,
    ) {
        formatEtaSummary(
            distanceRemainingMeters = progress.distanceRemainingMeters,
            etaEpochMillis = progress.etaEpochMillis,
            meterLabel = meterLabel,
            kilometerLabel = kilometerLabel,
        )
    }

    Surface(
        modifier = modifier.shadow(
            elevation = 10.dp,
            shape = EtaCardShape,
        ),
        shape = EtaCardShape,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = durationText,
                        color = etaColorOf(trafficLevel),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )

                    Text(
                        text = summaryText,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }

                Spacer(
                    modifier = Modifier.weight(1f),
                )

                MapNavigationSpeedRow(
                    displaySpeedKmh = displaySpeedKmh,
                    speedLimitKmh = speedLimitKmh,
                    isEstimated = progress.positionSource == VehiclePositionSource.DEAD_RECKONING,
                )
            }

            MapNavigationGpsSignalRow(
                gpsSignalState = gpsSignalState,
                positionSource = progress.positionSource,
            )

            MapNavigationEtaActionRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.End),
                onCloseClicked = onCloseClicked,
                onAlternativesClicked = onAlternativesClicked,
                onAddWaypointClicked = onAddWaypointClicked,
                onRoutePreviewClicked = onRoutePreviewClicked,
            )
        }
    }
}

@Composable
private fun MapNavigationGpsSignalRow(
    gpsSignalState: GpsSignalState,
    positionSource: VehiclePositionSource,
    modifier: Modifier = Modifier,
) {
    val lost = gpsSignalState as? GpsSignalState.Lost
    val isDeadReckoning = positionSource == VehiclePositionSource.DEAD_RECKONING
    if (lost == null && !isDeadReckoning) return

    val elapsedSeconds = lost?.elapsedSeconds?.toInt()?.coerceAtLeast(0) ?: 0
    val text = if (isDeadReckoning) {
        "測位低下 · 推定走行中 ${elapsedSeconds}s"
    } else {
        "測位低下 ${elapsedSeconds}s"
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        Text(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun MapNavigationEtaActionRow(
    onCloseClicked: () -> Unit,
    onAlternativesClicked: () -> Unit,
    onAddWaypointClicked: () -> Unit,
    onRoutePreviewClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MapNavigationEtaActionButton(
            icon = Icons.AutoMirrored.Filled.CallSplit,
            contentDescription = stringResource(Res.string.home_map_navigation_eta_alternatives),
            onClick = onAlternativesClicked,
        )

        MapNavigationEtaActionButton(
            icon = Icons.Default.AddCircleOutline,
            contentDescription = stringResource(Res.string.home_map_navigation_eta_add_waypoint),
            onClick = onAddWaypointClicked,
        )

        MapNavigationEtaActionButton(
            icon = Icons.Default.Route,
            contentDescription = stringResource(Res.string.home_map_navigation_eta_detour),
            onClick = onRoutePreviewClicked,
        )

        MapNavigationEtaActionButton(
            icon = Icons.Default.Close,
            contentDescription = stringResource(Res.string.home_map_navigation_eta_close),
            onClick = onCloseClicked,
            isHighlighted = true,
        )
    }
}

@Composable
private fun MapNavigationEtaActionButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isHighlighted: Boolean = false,
) {
    val containerColor = if (isHighlighted) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        Color.Transparent
    }
    val contentColor = if (isHighlighted) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val buttonWidth = if (isHighlighted) HighlightedActionWidth else ActionButtonSize

    Surface(
        onClick = onClick,
        modifier = modifier.size(width = buttonWidth, height = ActionButtonSize),
        shape = CircleShape,
        color = containerColor,
        contentColor = contentColor,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                modifier = Modifier.size(ActionIconSize),
                imageVector = icon,
                contentDescription = contentDescription,
            )
        }
    }
}

/**
 * 「4.7 km · 0:25」形式で残距離と到着予想時刻のサマリ行を組み立てる。
 */
private fun formatEtaSummary(
    distanceRemainingMeters: Int,
    etaEpochMillis: Long,
    meterLabel: String,
    kilometerLabel: String,
): String {
    val distanceText = formatDistance(
        meters = distanceRemainingMeters.toDouble(),
        meterLabel = meterLabel,
        kilometerLabel = kilometerLabel,
    )
    val arrivalText = formatArrivalClockTime(etaEpochMillis)
    return "$distanceText · $arrivalText"
}

/**
 * 到着予想時刻（epoch millis）を端末のタイムゾーンで「H:mm」形式にフォーマットする。
 */
private fun formatArrivalClockTime(epochMillis: Long): String {
    val arrivalDateTime = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(TimeZone.currentSystemDefault())
    val minuteText = arrivalDateTime.minute.toString().padStart(2, '0')
    return "${arrivalDateTime.hour}:$minuteText"
}

/**
 * 渋滞レベルに対応する ETA 文字色を返す。
 */
private fun etaColorOf(level: NavigationTrafficLevel): Color = when (level) {
    NavigationTrafficLevel.CLEAR -> EtaClearColor
    NavigationTrafficLevel.MODERATE -> EtaModerateColor
    NavigationTrafficLevel.HEAVY -> EtaHeavyColor
}

/** ETA カードの角丸形状。 */
private val EtaCardShape = RoundedCornerShape(16.dp)

/** アクションボタンの基準サイズ（高さ・通常時の横幅）。 */
private val ActionButtonSize = 48.dp

/** 強調表示（案内終了）アクションの横幅。 */
private val HighlightedActionWidth = 64.dp

/** アクションアイコンのサイズ。 */
private val ActionIconSize = 24.dp

/** 渋滞ほぼ無し時の ETA 文字色（緑）。 */
private val EtaClearColor = Color(0xFF1E8E3E)

/** やや渋滞時の ETA 文字色（黄）。 */
private val EtaModerateColor = Color(0xFFB26A00)

/** 強い渋滞時の ETA 文字色（赤）。 */
private val EtaHeavyColor = Color(0xFFD93025)
