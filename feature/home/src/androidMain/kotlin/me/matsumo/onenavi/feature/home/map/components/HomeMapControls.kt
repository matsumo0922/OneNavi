package me.matsumo.onenavi.feature.home.map.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.extension.compose.animation.viewport.MapViewportState
import com.mapbox.maps.plugin.viewport.data.DefaultViewportTransitionOptions
import com.mapbox.maps.plugin.viewport.data.FollowPuckViewportStateBearing
import com.mapbox.maps.plugin.viewport.data.FollowPuckViewportStateOptions
import kotlinx.coroutines.launch

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

private const val TRANSITION_MAX_DURATION_MS = 1000L
private const val FOLLOW_PUCK_ZOOM = 16.0
private const val FOLLOW_PUCK_PITCH = 45.0
private const val ZOOM_STEP = 1.0

@Composable
internal fun HomeMapControls(
    bearing: Double,
    trackingMode: LocationTrackingMode?,
    viewportState: MapViewportState,
    modifier: Modifier = Modifier,
    onResetBearing: () -> Unit,
    onTrackingModeChanged: (LocationTrackingMode) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var lastTrackingMode by remember { mutableStateOf(LocationTrackingMode.TiltedHeading) }

    val transitionOptions = remember {
        DefaultViewportTransitionOptions.Builder()
            .maxDurationMs(TRANSITION_MAX_DURATION_MS)
            .build()
    }

    LaunchedEffect(Unit) {
        viewportState.transitionToFollowPuckState(
            followPuckViewportStateOptions = buildFollowPuckOptions(LocationTrackingMode.TiltedHeading),
            defaultTransitionOptions = transitionOptions,
        )
    }

    fun setZoom(zoom: Double) {
        scope.launch {
            val currentZoom = viewportState.cameraState?.zoom ?: FOLLOW_PUCK_ZOOM
            viewportState.easeTo(
                CameraOptions.Builder()
                    .zoom(currentZoom + zoom)
                    .build(),
            )
        }
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HomeMapCompass(
            bearing = bearing,
            onClicked = onResetBearing,
        )

        HomeMapZoomButtons(
            onZoomInClicked = { setZoom(ZOOM_STEP) },
            onZoomOutClicked = { setZoom(-ZOOM_STEP) },
        )

        FloatingActionButton(
            onClick = {
                scope.launch {
                    val currentZoom = viewportState.cameraState?.zoom

                    if (trackingMode == null) {
                        onTrackingModeChanged(lastTrackingMode)
                        viewportState.transitionToFollowPuckState(
                            followPuckViewportStateOptions = buildFollowPuckOptions(
                                mode = lastTrackingMode,
                                zoom = currentZoom,
                            ),
                            defaultTransitionOptions = transitionOptions,
                        )
                    } else {
                        val nextMode = when (trackingMode) {
                            LocationTrackingMode.TiltedHeading -> LocationTrackingMode.TopDownHeading
                            LocationTrackingMode.TopDownHeading -> LocationTrackingMode.TopDownNorth
                            LocationTrackingMode.TopDownNorth -> LocationTrackingMode.TiltedHeading
                        }

                        onTrackingModeChanged(trackingMode)
                        lastTrackingMode = nextMode

                        viewportState.transitionToFollowPuckState(
                            followPuckViewportStateOptions = buildFollowPuckOptions(
                                mode = nextMode,
                                zoom = currentZoom,
                            ),
                            defaultTransitionOptions = transitionOptions,
                        )
                    }
                }
            },
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

private const val COMPASS_ANIMATION_DURATION_MS = 300
private const val COMPASS_NORTH_LABEL_FONT_SIZE = 11
private const val COMPASS_NEEDLE_WIDTH_RATIO = 0.18f
private const val COMPASS_NEEDLE_LENGTH_RATIO = 0.35f

@Composable
private fun HomeMapCompass(
    bearing: Double,
    modifier: Modifier = Modifier,
    onClicked: () -> Unit,
) {
    val animatedBearing by animateFloatAsState(
        targetValue = -bearing.toFloat(),
        animationSpec = tween(durationMillis = COMPASS_ANIMATION_DURATION_MS),
        label = "compass_bearing",
    )

    val textMeasurer = rememberTextMeasurer()
    val northColor = Color(0xFFE53935)
    val southColor = Color(0xFF757575)
    val surfaceColor = FloatingActionButtonDefaults.containerColor()
    val onSurfaceColor = MaterialTheme.colorScheme.onPrimaryContainer

    Surface(
        modifier = modifier.size(48.dp),
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

            rotate(
                degrees = animatedBearing,
                pivot = Offset(centerX, centerY),
            ) {
                // 北側の針（赤）
                val northPath = Path().apply {
                    moveTo(centerX, centerY - needleLength)
                    lineTo(centerX - needleWidth, centerY)
                    lineTo(centerX + needleWidth, centerY)
                    close()
                }
                drawPath(
                    path = northPath,
                    color = northColor,
                )

                // 南側の針（グレー）
                val southPath = Path().apply {
                    moveTo(centerX, centerY + needleLength)
                    lineTo(centerX - needleWidth, centerY)
                    lineTo(centerX + needleWidth, centerY)
                    close()
                }
                drawPath(
                    path = southPath,
                    color = southColor,
                )

                // 「N」ラベル
                val textLayoutResult = textMeasurer.measure(
                    text = "N",
                    style = TextStyle(
                        color = Color.White,
                        fontSize = COMPASS_NORTH_LABEL_FONT_SIZE.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                )
                drawText(
                    textLayoutResult = textLayoutResult,
                    topLeft = Offset(
                        x = centerX - textLayoutResult.size.width / 2,
                        y = centerY - needleLength + 2.dp.toPx(),
                    ),
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

private fun buildFollowPuckOptions(
    mode: LocationTrackingMode,
    zoom: Double? = null,
): FollowPuckViewportStateOptions {
    val effectiveZoom = zoom ?: FOLLOW_PUCK_ZOOM

    return when (mode) {
        LocationTrackingMode.TiltedHeading -> FollowPuckViewportStateOptions.Builder()
            .zoom(effectiveZoom)
            .pitch(FOLLOW_PUCK_PITCH)
            .bearing(FollowPuckViewportStateBearing.SyncWithLocationPuck)
            .build()

        LocationTrackingMode.TopDownHeading -> FollowPuckViewportStateOptions.Builder()
            .zoom(effectiveZoom)
            .pitch(0.0)
            .bearing(FollowPuckViewportStateBearing.SyncWithLocationPuck)
            .build()

        LocationTrackingMode.TopDownNorth -> FollowPuckViewportStateOptions.Builder()
            .zoom(effectiveZoom)
            .pitch(0.0)
            .bearing(FollowPuckViewportStateBearing.Constant(0.0))
            .build()
    }
}

/**
 * FAB のデフォルトコンテナカラーを取得するためのヘルパー。
 */
private object FloatingActionButtonDefaults {
    @Composable
    fun containerColor() = MaterialTheme.colorScheme.primaryContainer
}
