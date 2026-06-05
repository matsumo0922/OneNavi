package me.matsumo.onenavi.feature.map.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.matsumo.onenavi.core.resource.Res
import me.matsumo.onenavi.core.resource.home_map_control_compass
import me.matsumo.onenavi.core.resource.home_map_control_volume
import me.matsumo.onenavi.core.resource.home_map_control_zoom_in
import me.matsumo.onenavi.core.resource.home_map_control_zoom_out
import me.matsumo.onenavi.core.resource.setting_title
import me.matsumo.onenavi.feature.map.state.MapCameraState
import me.matsumo.onenavi.feature.map.state.MapPanelLayout
import me.matsumo.onenavi.feature.map.state.MapPanelSide
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun MapControls(
    cameraState: MapCameraState,
    panelLayout: MapPanelLayout,
    topPadding: Dp,
    bottomPadding: Dp,
    isNavigating: Boolean,
    onSettingClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settingContentDescription = stringResource(Res.string.setting_title)
    val volumeContentDescription = stringResource(Res.string.home_map_control_volume)
    val compassContentDescription = stringResource(Res.string.home_map_control_compass)
    val zoomInContentDescription = stringResource(Res.string.home_map_control_zoom_in)
    val zoomOutContentDescription = stringResource(Res.string.home_map_control_zoom_out)

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(top = topPadding, bottom = bottomPadding)
            .padding(
                top = 8.dp,
                start = 16.dp,
                end = 16.dp,
            ),
    ) {
        Column(
            modifier = Modifier.align(panelLayout.toControlsTopAlignment()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 設定・音量は案内中のみ表示する
            AnimatedVisibility(
                visible = isNavigating,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    MapControlIconButton(
                        modifier = Modifier.size(MapControlButtonSize),
                        imageVector = Icons.Default.Settings,
                        contentDescription = settingContentDescription,
                        onClicked = onSettingClicked,
                    )

                    MapControlIconButton(
                        modifier = Modifier.size(MapControlButtonSize),
                        imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = volumeContentDescription,
                        // TODO: TTS ミュート配線は別 PR で対応する
                        onClicked = {},
                    )
                }
            }

            MapCompass(
                modifier = Modifier.size(MapControlButtonSize),
                bearing = cameraState.cameraState.bearing,
                contentDescription = compassContentDescription,
                onClicked = cameraState::toggleCompassPerspective,
            )
        }

        MapZoomButtons(
            modifier = Modifier
                .align(panelLayout.toControlsBottomAlignment())
                .width(MapControlButtonSize),
            zoomInContentDescription = zoomInContentDescription,
            zoomOutContentDescription = zoomOutContentDescription,
            onZoomInClicked = cameraState::zoomIn,
            onZoomOutClicked = cameraState::zoomOut,
        )
    }
}

@Composable
private fun MapControlIconButton(
    imageVector: ImageVector,
    contentDescription: String,
    onClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
        onClick = onClicked,
    ) {
        Box(
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = imageVector,
                contentDescription = contentDescription,
            )
        }
    }
}

@Composable
private fun MapZoomButtons(
    zoomInContentDescription: String,
    zoomOutContentDescription: String,
    onZoomInClicked: () -> Unit,
    onZoomOutClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(MapControlButtonSize)
                    .clickable { onZoomInClicked() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = zoomInContentDescription,
                )
            }

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )

            Box(
                modifier = Modifier
                    .size(MapControlButtonSize)
                    .clickable { onZoomOutClicked() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Remove,
                    contentDescription = zoomOutContentDescription,
                )
            }
        }
    }
}

private const val COMPASS_NEEDLE_WIDTH_RATIO = 0.1f
private const val COMPASS_NEEDLE_LENGTH_RATIO = 0.35f
private const val COMPASS_NEEDLE_INSET_RATIO = 0.03f

/** 地図コントロールの丸ボタンサイズ。 */
private val MapControlButtonSize = 56.dp

@Composable
private fun MapCompass(
    bearing: Double,
    contentDescription: String,
    onClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rotationDegrees = -bearing.toFloat()

    val northColor = Color(0xFFE53935)
    val southColor = Color(0xFF757575)
    val surfaceColor = MaterialTheme.colorScheme.surface

    Surface(
        modifier = modifier.semantics {
            this.contentDescription = contentDescription
        },
        shape = CircleShape,
        color = surfaceColor,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
        onClick = onClicked,
    ) {
        Canvas(
            modifier = Modifier.fillMaxSize(),
        ) {
            val centerX = size.width / 2
            val centerY = size.height / 2
            val needleWidth = size.width * COMPASS_NEEDLE_WIDTH_RATIO
            val needleLength = size.height * COMPASS_NEEDLE_LENGTH_RATIO
            val inset = size.height * COMPASS_NEEDLE_INSET_RATIO

            rotate(
                degrees = rotationDegrees,
                pivot = Offset(centerX, centerY),
            ) {
                // 北側の針（赤）
                val northPath = Path().apply {
                    moveTo(centerX, centerY - needleLength) // 先端
                    lineTo(centerX - needleWidth, centerY) // 左肩
                    lineTo(centerX, centerY - inset) // 中央の食い込み
                    lineTo(centerX + needleWidth, centerY) // 右肩
                    close()
                }
                drawPath(
                    path = northPath,
                    color = northColor,
                )

                // 南側の針（グレー）
                val southPath = Path().apply {
                    moveTo(centerX, centerY + needleLength) // 先端
                    lineTo(centerX - needleWidth, centerY) // 左肩
                    lineTo(centerX, centerY + inset) // 中央の食い込み
                    lineTo(centerX + needleWidth, centerY) // 右肩
                    close()
                }
                drawPath(
                    path = southPath,
                    color = southColor,
                )
            }
        }
    }
}

/** controls カラムを置く物理側が左かどうか。Split かつ UI 帯が左のときだけ左側。 */
private val MapPanelLayout.isControlsOnLeft: Boolean
    get() = isSplit && panelSide == MapPanelSide.LEFT

private fun MapPanelLayout.toControlsTopAlignment(): Alignment {
    return if (isControlsOnLeft) {
        AbsoluteAlignment.TopLeft
    } else {
        AbsoluteAlignment.TopRight
    }
}

private fun MapPanelLayout.toControlsBottomAlignment(): Alignment {
    return if (isControlsOnLeft) {
        AbsoluteAlignment.BottomLeft
    } else {
        AbsoluteAlignment.BottomRight
    }
}
