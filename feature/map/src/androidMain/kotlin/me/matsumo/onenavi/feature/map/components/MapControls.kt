package me.matsumo.onenavi.feature.map.components

import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.filled.LocationSearching
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.GoogleMap
import me.matsumo.onenavi.feature.map.state.MapCameraState
import me.matsumo.onenavi.feature.map.state.VehicleLocationState

@Composable
internal fun MapControls(
    cameraState: MapCameraState,
    vehicleLocationState: VehicleLocationState?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.padding(16.dp),
        contentAlignment = Alignment.BottomEnd,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MapCompass(
                modifier = Modifier.size(48.dp),
                bearing = cameraState.cameraState.bearing,
                onClicked = {
                    if (cameraState.cameraState.perspective == GoogleMap.CameraPerspective.TILTED) {
                        cameraState.setPerspective(GoogleMap.CameraPerspective.TOP_DOWN_NORTH_UP)
                    } else {
                        cameraState.setPerspective(GoogleMap.CameraPerspective.TILTED)
                    }
                },
            )

            MapZoomButtons(
                modifier = Modifier.width(48.dp),
                onZoomInClicked = cameraState::zoomIn,
                onZoomOutClicked = cameraState::zoomOut,
            )

            FloatingActionButton(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                onClick = { cameraState.followVehicleLocation(vehicleLocationState) },
            ) {
                Icon(
                    imageVector = if (cameraState.cameraState.isFollowingMyLocation) {
                        Icons.Default.MyLocation
                    } else {
                        Icons.Default.LocationSearching
                    },
                    contentDescription = null,
                )
            }
        }
    }
}

@Composable
private fun MapZoomButtons(
    onZoomInClicked: () -> Unit,
    onZoomOutClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
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

private const val COMPASS_NEEDLE_WIDTH_RATIO = 0.1f
private const val COMPASS_NEEDLE_LENGTH_RATIO = 0.35f
private const val COMPASS_NEEDLE_INSET_RATIO = 0.03f

@Composable
private fun MapCompass(
    bearing: Double,
    onClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rotationDegrees = -bearing.toFloat()

    val northColor = Color(0xFFE53935)
    val southColor = Color(0xFF757575)
    val surfaceColor = MaterialTheme.colorScheme.surfaceContainerHigh

    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = surfaceColor,
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
        onClick = onClicked,
    ) {
        Canvas(
            modifier = Modifier.size(48.dp),
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
