package me.matsumo.onenavi.feature.home.map.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * 地図の追従モードを表す列挙型。
 * FAB 押下で [TiltedHeading] → [TopDownHeading] → [TopDownNorth] の順にループする。
 */
enum class LocationTrackingMode {
    /** 斜め上視点 + 進行方向 */
    TiltedHeading,

    /** 真上視点 + 進行方向 */
    TopDownHeading,

    /** 真上視点 + 北向き */
    TopDownNorth,
}

@Composable
internal fun HomeMapControls(
    trackingMode: LocationTrackingMode?,
    modifier: Modifier = Modifier,
    onLocationClicked: () -> Unit,
    onZoomInClicked: () -> Unit,
    onZoomOutClicked: () -> Unit,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HomeMapZoomButtons(
            onZoomInClicked = onZoomInClicked,
            onZoomOutClicked = onZoomOutClicked,
        )

        FloatingActionButton(
            onClick = onLocationClicked,
        ) {
            Icon(
                imageVector = trackingMode.toIcon(),
                contentDescription = null,
            )
        }
    }
}

@Composable
private fun HomeMapZoomButtons(
    modifier: Modifier = Modifier,
    onZoomInClicked: () -> Unit,
    onZoomOutClicked: () -> Unit,
) {
    Surface(
        modifier = modifier.width(48.dp),
        shape = CircleShape,
        color = FloatingActionButtonDefaults.containerColor(),
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clickable { onZoomInClicked() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                )
            }

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 8.dp),
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f),
            )

            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clickable { onZoomOutClicked() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Remove,
                    contentDescription = null,
                )
            }
        }
    }
}

private fun LocationTrackingMode?.toIcon(): ImageVector = when (this) {
    LocationTrackingMode.TiltedHeading -> Icons.Filled.Explore
    LocationTrackingMode.TopDownHeading -> Icons.Default.Navigation
    LocationTrackingMode.TopDownNorth -> Icons.Default.NearMe
    null -> Icons.Default.MyLocation
}

/**
 * FAB のデフォルトコンテナカラーを取得するためのヘルパー。
 */
private object FloatingActionButtonDefaults {
    @Composable
    fun containerColor() = MaterialTheme.colorScheme.primaryContainer
}
