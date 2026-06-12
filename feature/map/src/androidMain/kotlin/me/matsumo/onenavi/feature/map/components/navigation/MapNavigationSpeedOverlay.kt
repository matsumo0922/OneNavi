package me.matsumo.onenavi.feature.map.components.navigation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import me.matsumo.onenavi.core.resource.Res
import me.matsumo.onenavi.core.resource.home_map_navigation_current_speed_content_description
import me.matsumo.onenavi.core.resource.home_map_navigation_current_speed_unknown_content_description
import me.matsumo.onenavi.core.resource.home_map_navigation_speed_limit_content_description
import org.jetbrains.compose.resources.stringResource

/**
 * 案内中の地図上に現在速度と制限速度を表示する overlay。
 *
 * 制限速度が取得できない場合は現在速度だけを表示する。
 */
@Composable
internal fun MapNavigationSpeedOverlay(
    displaySpeedKmh: Int?,
    speedLimitKmh: Int?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MapNavigationCurrentSpeedBadge(
            displaySpeedKmh = displaySpeedKmh,
        )

        if (speedLimitKmh != null) {
            MapNavigationSpeedLimitSign(
                speedLimitKmh = speedLimitKmh,
            )
        }
    }
}

@Composable
private fun MapNavigationCurrentSpeedBadge(
    displaySpeedKmh: Int?,
    modifier: Modifier = Modifier,
) {
    val contentDescription = if (displaySpeedKmh != null) {
        stringResource(Res.string.home_map_navigation_current_speed_content_description, displaySpeedKmh)
    } else {
        stringResource(Res.string.home_map_navigation_current_speed_unknown_content_description)
    }
    val speedText = displaySpeedKmh?.toString() ?: SPEED_UNKNOWN_PLACEHOLDER

    Surface(
        modifier = modifier
            .height(56.dp)
            .widthIn(min = 104.dp)
            .semantics {
                this.contentDescription = contentDescription
            },
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.92f),
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shadowElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = speedText,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )

            Text(
                text = SPEED_UNIT_KMH,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun MapNavigationSpeedLimitSign(
    speedLimitKmh: Int,
    modifier: Modifier = Modifier,
) {
    val contentDescription = stringResource(
        Res.string.home_map_navigation_speed_limit_content_description,
        speedLimitKmh,
    )

    Surface(
        modifier = modifier
            .size(56.dp)
            .semantics {
                this.contentDescription = contentDescription
            },
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(4.dp, MaterialTheme.colorScheme.error),
        shadowElevation = 6.dp,
    ) {
        Box(
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = speedLimitKmh.toString(),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 速度をまだ取得できない時の表示文字列。 */
private const val SPEED_UNKNOWN_PLACEHOLDER = "--"

/** 速度表示で使う単位。 */
private const val SPEED_UNIT_KMH = "km/h"
