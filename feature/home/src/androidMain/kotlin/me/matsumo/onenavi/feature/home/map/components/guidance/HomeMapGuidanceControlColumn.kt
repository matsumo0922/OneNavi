package me.matsumo.onenavi.feature.home.map.components.guidance

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

@Composable
internal fun HomeMapGuidanceControlColumn(
    onSettingsClicked: () -> Unit,
    onVolumeClicked: () -> Unit,
    onCompassClicked: () -> Unit,
    onZoomInClicked: () -> Unit,
    onZoomOutClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        GuidanceControlButton(
            onClick = onSettingsClicked,
            contentDescription = "設定",
        ) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = null,
                tint = NavigationColors.controlIcon,
            )
        }

        GuidanceControlButton(
            onClick = onVolumeClicked,
            contentDescription = "音量",
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = null,
                tint = NavigationColors.controlIcon,
            )
        }

        GuidanceControlButton(
            onClick = onCompassClicked,
            contentDescription = "コンパス",
        ) {
            Icon(
                imageVector = Icons.Default.Explore,
                contentDescription = null,
                tint = NavigationColors.controlIcon,
            )
        }

        GuidanceControlButton(
            onClick = onZoomInClicked,
            contentDescription = "ズームイン",
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                tint = NavigationColors.controlIcon,
            )
        }

        GuidanceControlButton(
            onClick = onZoomOutClicked,
            contentDescription = "ズームアウト",
        ) {
            Icon(
                imageVector = Icons.Default.Remove,
                contentDescription = null,
                tint = NavigationColors.controlIcon,
            )
        }
    }
}

@Composable
private fun GuidanceControlButton(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    IconButton(
        modifier = modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(NavigationColors.controlBackground.copy(alpha = 0.8f)),
        onClick = onClick,
    ) {
        content()
    }
}
