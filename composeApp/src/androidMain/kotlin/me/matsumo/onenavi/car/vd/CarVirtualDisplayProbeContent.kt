package me.matsumo.onenavi.car.vd

import android.Manifest.permission
import android.content.pm.PackageManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.matsumo.onenavi.MainViewModel
import me.matsumo.onenavi.OneNaviApp
import me.matsumo.onenavi.core.ui.theme.OneNaviTheme
import org.koin.compose.viewmodel.koinViewModel

@Composable
internal fun CarVirtualDisplayProbeContent(
    displayId: Int,
    expectedDisplayId: Int,
    rendererLabel: String,
    viewport: CarVirtualDisplayViewport,
    inputState: CarVirtualDisplayProbeInputState,
    modifier: Modifier = Modifier,
) {
    val lifecycleStateLabel = rememberCarVirtualDisplayLifecycleStateLabel()
    val observedFrame = viewport.observedFrame
    val hostSlotRightInset = viewport.surfaceWidth - viewport.visibleRight
    val observedFrameLabel = "blue frame=${observedFrame.width}x${observedFrame.height} " +
        "L=${observedFrame.left} R=${viewport.observedFrameRightInset} " +
        observedFrame.frameLabel
    val hostSlotFrameLabel = "pale frame=${viewport.visibleWidth}x${viewport.surfaceHeight} " +
        "L=${viewport.visibleLeft} R=$hostSlotRightInset " +
        "Rect(${viewport.visibleLeft},0 - ${viewport.visibleRight},${viewport.surfaceHeight})"
    val hostStableLabel = "host stable=${viewport.stableWidth}x${viewport.stableHeight} " +
        "${viewport.stableAreaLabel}"
    val surfacePointLabel = "pt surface=${inputState.surfacePointLabel} " +
        "surfaceIn=${inputState.insideSurfaceLabel}"
    val hostVisiblePointLabel = "hostVisible=${inputState.hostVisiblePointLabel} " +
        "hostVisibleIn=${inputState.insideHostVisibleAreaLabel}"
    val gestureLabel = "d=${inputState.distanceLabel} v=${inputState.velocityLabel} " +
        "scale=${inputState.scaleFactorLabel}"

    MaterialTheme {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color(0xFF111827)),
            contentAlignment = Alignment.Center,
        ) {
            CarVirtualDisplayObservedFrameRoot(
                modifier = Modifier.fillMaxSize(),
                viewport = viewport,
            ) {
                CarVirtualDisplayProbeAppHost(
                    modifier = Modifier.fillMaxSize(),
                )
            }
            CarVirtualDisplayProbeViewportOverlay(
                modifier = Modifier.fillMaxSize(),
                viewport = viewport,
            )
            CarVirtualDisplayObservedFrameRoot(
                modifier = Modifier.fillMaxSize(),
                viewport = viewport,
            ) {
                CarVirtualDisplayProbeDiagnosticsOverlay(
                    modifier = Modifier.padding(16.dp),
                    displayId = displayId,
                    expectedDisplayId = expectedDisplayId,
                    rendererLabel = rendererLabel,
                    viewport = viewport,
                    inputState = inputState,
                    lifecycleStateLabel = lifecycleStateLabel,
                    observedFrameLabel = observedFrameLabel,
                    hostSlotFrameLabel = hostSlotFrameLabel,
                    hostStableLabel = hostStableLabel,
                    surfacePointLabel = surfacePointLabel,
                    hostVisiblePointLabel = hostVisiblePointLabel,
                    gestureLabel = gestureLabel,
                )
            }
        }
    }
}

@Composable
private fun CarVirtualDisplayProbeAppHost(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val viewModel = koinViewModel<MainViewModel>()
    val setting by viewModel.setting.collectAsStateWithLifecycle(null)
    val appSetting = setting
    val hasLocationPermission = ContextCompat.checkSelfPermission(
        context,
        permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED

    when {
        appSetting == null -> {
            CarVirtualDisplayProbeStatusMessage(
                modifier = modifier,
                text = "Loading OneNavi setting...",
            )
        }

        hasLocationPermission -> {
            OneNaviApp(
                modifier = modifier,
                setting = appSetting,
            )
        }

        else -> {
            OneNaviTheme(
                appSetting = appSetting,
            ) {
                CarVirtualDisplayProbeStatusMessage(
                    modifier = modifier,
                    text = "スマホ側で位置情報を許可してください",
                )
            }
        }
    }
}

@Composable
private fun CarVirtualDisplayProbeStatusMessage(
    text: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Text(
            modifier = Modifier.padding(24.dp),
            text = text,
            color = Color.White,
            fontSize = 16.sp,
        )
    }
}

@Composable
private fun CarVirtualDisplayProbeDiagnosticsOverlay(
    displayId: Int,
    expectedDisplayId: Int,
    rendererLabel: String,
    viewport: CarVirtualDisplayViewport,
    inputState: CarVirtualDisplayProbeInputState,
    lifecycleStateLabel: String,
    observedFrameLabel: String,
    hostSlotFrameLabel: String,
    hostStableLabel: String,
    surfacePointLabel: String,
    hostVisiblePointLabel: String,
    gestureLabel: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(Color(0xB3111827))
            .padding(12.dp),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "OneNavi VD $rendererLabel",
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "lifecycle=$lifecycleStateLabel display=$displayId/$expectedDisplayId",
            color = Color(0xFF93C5FD),
            fontSize = 12.sp,
        )
        Text(
            text = "surface=${viewport.surfaceWidth}x${viewport.surfaceHeight} dpi=${viewport.densityDpi}",
            color = Color(0xFFE5E7EB),
            fontSize = 12.sp,
        )
        Text(
            text = observedFrameLabel,
            color = Color(0xFF60A5FA),
            fontSize = 12.sp,
        )
        Text(
            text = hostSlotFrameLabel,
            color = Color(0xFFC7D2FE),
            fontSize = 12.sp,
        )
        Text(
            text = hostStableLabel,
            color = Color(0xFFE5E7EB),
            fontSize = 12.sp,
        )
        Text(
            text = "input #${inputState.sequence} ${inputState.kind.label} pan=${inputState.panModeLabel}",
            color = Color(0xFFFDE68A),
            fontSize = 12.sp,
        )
        Text(
            text = surfacePointLabel,
            color = Color(0xFFFDE68A),
            fontSize = 12.sp,
        )
        Text(
            text = hostVisiblePointLabel,
            color = Color(0xFFFDE68A),
            fontSize = 12.sp,
        )
        Text(
            text = gestureLabel,
            color = Color(0xFFFDE68A),
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun rememberCarVirtualDisplayLifecycleStateLabel(): String {
    val lifecycleOwner = LocalLifecycleOwner.current
    var lifecycleState by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            lifecycleState = event.targetState
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    return lifecycleState.toLifecycleStateLabel()
}

@Composable
private fun CarVirtualDisplayProbeViewportOverlay(
    viewport: CarVirtualDisplayViewport,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier = modifier,
    ) {
        drawProbeViewportFrames(viewport = viewport)
    }
}

private fun DrawScope.drawProbeViewportFrames(viewport: CarVirtualDisplayViewport) {
    val observedFrame = viewport.observedFrame

    drawProbeFrame(
        color = Color(0xCC60A5FA),
        left = observedFrame.left,
        top = observedFrame.top,
        right = observedFrame.right,
        bottom = observedFrame.bottom,
        strokeWidth = OBSERVED_FRAME_STROKE_WIDTH,
    )
    drawProbeFrame(
        color = Color(0x66C7D2FE),
        left = viewport.visibleLeft,
        top = 0,
        right = viewport.visibleRight,
        bottom = viewport.surfaceHeight,
        strokeWidth = HOST_SLOT_STROKE_WIDTH,
    )
}

private fun DrawScope.drawProbeFrame(
    color: Color,
    left: Int,
    top: Int,
    right: Int,
    bottom: Int,
    strokeWidth: Float,
) {
    val strokeInset = strokeWidth / 2f
    val frameLeft = left.toFloat() + strokeInset
    val frameTop = top.toFloat() + strokeInset
    val frameWidth = (right - left).toFloat() - strokeWidth
    val frameHeight = (bottom - top).toFloat() - strokeWidth

    drawRect(
        color = color,
        topLeft = Offset(
            x = frameLeft,
            y = frameTop,
        ),
        size = Size(
            width = frameWidth.coerceAtLeast(0f),
            height = frameHeight.coerceAtLeast(0f),
        ),
        style = Stroke(width = strokeWidth),
    )
}

private fun Lifecycle.State.toLifecycleStateLabel(): String {
    return when (this) {
        Lifecycle.State.DESTROYED -> "DESTROYED"
        Lifecycle.State.INITIALIZED -> "INITIALIZED"
        Lifecycle.State.CREATED -> "CREATED"
        Lifecycle.State.STARTED -> "STARTED"
        Lifecycle.State.RESUMED -> "RESUMED"
    }
}

/** host 上で OneNavi として観測できる描画枠を示す線幅。 */
private const val OBSERVED_FRAME_STROKE_WIDTH = 2f

/** split 後に host が表示している横スロットを示す検証枠の線幅。 */
private const val HOST_SLOT_STROKE_WIDTH = 2f
