package me.matsumo.onenavi.feature.map.components.navigation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
 * 案内中の ETA カード右端に現在速度と制限速度を表示する行。
 *
 * 制限速度が取得できない場合は現在速度だけを表示する。
 */
@Composable
internal fun MapNavigationSpeedRow(
    displaySpeedKmh: Int?,
    speedLimitKmh: Int?,
    modifier: Modifier = Modifier,
    isEstimated: Boolean = false,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (speedLimitKmh != null) {
            MapNavigationSpeedLimitSign(
                speedLimitKmh = speedLimitKmh,
            )
        }

        MapNavigationCurrentSpeedColumn(
            displaySpeedKmh = displaySpeedKmh,
            isEstimated = isEstimated,
        )
    }
}

@Composable
private fun MapNavigationCurrentSpeedColumn(
    displaySpeedKmh: Int?,
    modifier: Modifier = Modifier,
    isEstimated: Boolean = false,
) {
    val contentDescription = if (displaySpeedKmh != null) {
        stringResource(Res.string.home_map_navigation_current_speed_content_description, displaySpeedKmh)
    } else {
        stringResource(Res.string.home_map_navigation_current_speed_unknown_content_description)
    }
    val speedText = displaySpeedKmh?.toString() ?: SPEED_UNKNOWN_PLACEHOLDER

    Column(
        modifier = modifier
            .semantics {
                this.contentDescription = contentDescription
            },
        horizontalAlignment = Alignment.End,
    ) {
        Text(
            text = "$speedText $SPEED_UNIT_KM",
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.End,
        )

        Text(
            text = if (isEstimated) ESTIMATED_SPEED_LABEL else SPEED_LABEL,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.End,
        )
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
            .size(SpeedLimitSignSize)
            .semantics {
                this.contentDescription = contentDescription
            },
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(3.dp, MaterialTheme.colorScheme.error),
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
private const val SPEED_UNIT_KM = "km/h"

/** 速度表示の補助ラベル。 */
private const val SPEED_LABEL = "speed"

/** 推定速度表示の補助ラベル。 */
private const val ESTIMATED_SPEED_LABEL = "推定"

/** 制限速度標識のサイズ。 */
private val SpeedLimitSignSize = 48.dp
