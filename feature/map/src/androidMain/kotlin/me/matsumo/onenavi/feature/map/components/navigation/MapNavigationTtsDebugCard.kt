package me.matsumo.onenavi.feature.map.components.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.matsumo.onenavi.core.common.formatDistance
import me.matsumo.onenavi.core.navigation.voice.debug.VoiceAnnouncementDebugFetchState
import me.matsumo.onenavi.core.navigation.voice.debug.VoiceAnnouncementDebugItem
import me.matsumo.onenavi.core.navigation.voice.debug.VoiceAnnouncementDebugRecentItem
import me.matsumo.onenavi.core.navigation.voice.debug.VoiceAnnouncementDebugResult
import me.matsumo.onenavi.core.navigation.voice.debug.VoiceAnnouncementDebugSnapshot
import me.matsumo.onenavi.core.navigation.voice.debug.VoiceAnnouncementDebugStageKind
import me.matsumo.onenavi.core.resource.Res
import me.matsumo.onenavi.core.resource.common_unit_kilometer
import me.matsumo.onenavi.core.resource.common_unit_meter
import org.jetbrains.compose.resources.stringResource

/**
 * ETA カード上へ表示する TTS 発話予定のデバッグカード。
 */
@Composable
internal fun MapNavigationTtsDebugCard(
    snapshot: VoiceAnnouncementDebugSnapshot,
    modifier: Modifier = Modifier,
) {
    val hasRecentAnnouncements = snapshot.recentAnnouncements.isNotEmpty()
    val hasUpcomingAnnouncements = snapshot.upcomingAnnouncements.isNotEmpty()
    if (!hasRecentAnnouncements && !hasUpcomingAnnouncements) return

    Surface(
        modifier = modifier.shadow(
            elevation = 10.dp,
            shape = TtsDebugCardShape,
        ),
        shape = TtsDebugCardShape,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            snapshot.recentAnnouncements.forEach { item ->
                MapNavigationTtsDebugRecentRow(
                    item = item,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            snapshot.upcomingAnnouncements.forEach { item ->
                MapNavigationTtsDebugRow(
                    item = item,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun MapNavigationTtsDebugRecentRow(
    item: VoiceAnnouncementDebugRecentItem,
    modifier: Modifier = Modifier,
) {
    val categoryText = remember(item.categories) {
        item.categories.take(MAX_CATEGORY_LABELS).joinToString(separator = "/")
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                modifier = Modifier.weight(1f),
                text = item.text.ifBlank { item.stageId },
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            MapNavigationTtsDebugResult(
                result = item.result,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = item.stageKind.label(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )

            Text(
                modifier = Modifier.weight(1f),
                text = categoryText.ifBlank { "category none" },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MapNavigationTtsDebugRow(
    item: VoiceAnnouncementDebugItem,
    modifier: Modifier = Modifier,
) {
    val meterLabel = stringResource(Res.string.common_unit_meter)
    val kilometerLabel = stringResource(Res.string.common_unit_kilometer)
    val distanceText = remember(item.remainingMeters, meterLabel, kilometerLabel) {
        formatDistance(
            meters = item.remainingMeters,
            meterLabel = meterLabel,
            kilometerLabel = kilometerLabel,
        )
    }
    val categoryText = remember(item.categories) {
        item.categories.take(MAX_CATEGORY_LABELS).joinToString(separator = "/")
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                modifier = Modifier.weight(1f),
                text = item.text.ifBlank { item.stageId },
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Text(
                text = "$distanceText ${item.stageKind.label()}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MapNavigationTtsDebugFetchState(
                fetchState = item.fetchState,
            )

            if (item.isRouteOrderBlocked) {
                Text(
                    text = "gate blocked",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                )
            }

            Text(
                modifier = Modifier.weight(1f),
                text = categoryText.ifBlank { "category none" },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MapNavigationTtsDebugResult(
    result: VoiceAnnouncementDebugResult,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            modifier = Modifier.size(FetchStateIconSize),
            imageVector = result.icon(),
            contentDescription = result.label(),
            tint = result.color(),
        )

        Text(
            text = result.label(),
            color = result.color(),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
    }
}

@Composable
private fun MapNavigationTtsDebugFetchState(
    fetchState: VoiceAnnouncementDebugFetchState,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            modifier = Modifier.size(FetchStateIconSize),
            imageVector = fetchState.icon(),
            contentDescription = fetchState.label(),
            tint = fetchState.color(),
        )

        Text(
            text = fetchState.label(),
            color = fetchState.color(),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
    }
}

private fun VoiceAnnouncementDebugStageKind.label(): String = when (this) {
    VoiceAnnouncementDebugStageKind.MIDDLE -> "MID"
    VoiceAnnouncementDebugStageKind.FINAL -> "FINAL"
}

private fun VoiceAnnouncementDebugFetchState.icon(): ImageVector = when (this) {
    VoiceAnnouncementDebugFetchState.CACHED -> Icons.Filled.Check
    VoiceAnnouncementDebugFetchState.IN_FLIGHT -> Icons.Filled.Route
    VoiceAnnouncementDebugFetchState.NOT_REQUESTED -> Icons.Filled.Close
}

private fun VoiceAnnouncementDebugFetchState.label(): String = when (this) {
    VoiceAnnouncementDebugFetchState.CACHED -> "cached"
    VoiceAnnouncementDebugFetchState.IN_FLIGHT -> "in-flight"
    VoiceAnnouncementDebugFetchState.NOT_REQUESTED -> "not requested"
}

private fun VoiceAnnouncementDebugResult.icon(): ImageVector = when (this) {
    VoiceAnnouncementDebugResult.SPOKEN -> Icons.Filled.Check
    VoiceAnnouncementDebugResult.NOT_SPOKEN -> Icons.Filled.Close
}

private fun VoiceAnnouncementDebugResult.label(): String = when (this) {
    VoiceAnnouncementDebugResult.SPOKEN -> "spoken"
    VoiceAnnouncementDebugResult.NOT_SPOKEN -> "not spoken"
}

@Composable
private fun VoiceAnnouncementDebugFetchState.color(): Color = when (this) {
    VoiceAnnouncementDebugFetchState.CACHED -> MaterialTheme.colorScheme.tertiary
    VoiceAnnouncementDebugFetchState.IN_FLIGHT -> MaterialTheme.colorScheme.primary
    VoiceAnnouncementDebugFetchState.NOT_REQUESTED -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun VoiceAnnouncementDebugResult.color(): Color = when (this) {
    VoiceAnnouncementDebugResult.SPOKEN -> MaterialTheme.colorScheme.tertiary
    VoiceAnnouncementDebugResult.NOT_SPOKEN -> MaterialTheme.colorScheme.error
}

/** TTS デバッグカードの角丸形状。 */
private val TtsDebugCardShape = RoundedCornerShape(16.dp)

/** fetch 状態アイコンのサイズ。 */
private val FetchStateIconSize = 14.dp

/** 1 行に出す category label の最大数。 */
private const val MAX_CATEGORY_LABELS = 3
