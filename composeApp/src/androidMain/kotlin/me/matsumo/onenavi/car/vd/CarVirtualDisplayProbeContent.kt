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
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.platform.LocalDensity
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
import me.matsumo.onenavi.car.CarGuidanceSessionReleaser
import me.matsumo.onenavi.core.common.car.CarPhoneSessionCoordinator
import me.matsumo.onenavi.core.common.car.OneNaviDisplaySurface
import me.matsumo.onenavi.core.model.AppSetting
import me.matsumo.onenavi.core.model.DeveloperFeature
import me.matsumo.onenavi.core.ui.theme.LocalOneNaviDisplaySurface
import me.matsumo.onenavi.core.ui.theme.LocalSupportsPlatformDialogWindow
import me.matsumo.onenavi.core.ui.theme.OneNaviTheme
import me.matsumo.onenavi.feature.map.DEFAULT_MAP_RENDER_SCALE
import me.matsumo.onenavi.feature.map.LocalMapRenderScale
import me.matsumo.onenavi.feature.map.MapRenderDensityDiagnostics
import me.matsumo.onenavi.guidance.GuidanceForegroundController
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

@Composable
internal fun CarVirtualDisplayProbeContent(
    displayId: Int,
    expectedDisplayId: Int,
    rendererLabel: String,
    viewport: CarVirtualDisplayViewport,
    inputState: CarVirtualDisplayProbeInputState,
    clickCoordinateResult: CarVirtualDisplayProbeClickCoordinateResult?,
    modifier: Modifier = Modifier,
) {
    val viewModel = koinViewModel<MainViewModel>()
    val settings by viewModel.setting.collectAsStateWithLifecycle(null)
    val shouldShowDebugOverlay = settings?.isDeveloperFeatureEnabled(DeveloperFeature.CAR_VD_DEBUG_OVERLAY) == true
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
    val hostInputPointLabel = "hostInput=${inputState.hostInputPointLabel}"
    val surfacePointLabel = "pt surface=${inputState.surfacePointLabel} " +
        "surfaceIn=${inputState.insideSurfaceLabel}"
    val observedFramePointLabel = "observed=${inputState.observedFramePointLabel} " +
        "observedIn=${inputState.insideObservedFrameLabel}"
    val hostVisiblePointLabel = "hostVisible=${inputState.hostVisiblePointLabel} " +
        "hostVisibleIn=${inputState.insideHostVisibleAreaLabel}"
    val gestureLabel = "d=${inputState.distanceLabel} v=${inputState.velocityLabel} " +
        "scale=${inputState.scaleFactorLabel}"
    val clickCoordinateLabel = clickCoordinateResult.toClickCoordinateLabel()
    val clickCandidateLabel = inputState.toClickCandidateLabel(viewport)
    val composeDensity = LocalDensity.current.density
    val mapDensityLabel = MapRenderDensityDiagnostics.label
        ?: "mapDensity eff=n/a compose=$composeDensity"

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
                    settings = settings,
                    viewport = viewport,
                )
            }
            if (shouldShowDebugOverlay) {
                CarVirtualDisplayProbeViewportOverlay(
                    modifier = Modifier.fillMaxSize(),
                    viewport = viewport,
                    inputState = inputState,
                    clickCoordinateResult = clickCoordinateResult,
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
                        hostInputPointLabel = hostInputPointLabel,
                        surfacePointLabel = surfacePointLabel,
                        observedFramePointLabel = observedFramePointLabel,
                        hostVisiblePointLabel = hostVisiblePointLabel,
                        gestureLabel = gestureLabel,
                        clickCoordinateLabel = clickCoordinateLabel,
                        clickCandidateLabel = clickCandidateLabel,
                        mapDensityLabel = mapDensityLabel,
                    )
                }
            }
        }
    }
}

@Composable
private fun CarVirtualDisplayProbeAppHost(
    settings: AppSetting?,
    viewport: CarVirtualDisplayViewport,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val carPhoneSessionCoordinator = koinInject<CarPhoneSessionCoordinator>()
    val carGuidanceSessionReleaser = koinInject<CarGuidanceSessionReleaser>()
    val guidanceForegroundController = koinInject<GuidanceForegroundController>()
    val hasLocationPermission = ContextCompat.checkSelfPermission(
        context,
        permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED

    DisposableEffect(carGuidanceSessionReleaser, guidanceForegroundController) {
        carGuidanceSessionReleaser.ensureStarted()
        guidanceForegroundController.ensureStarted()
        guidanceForegroundController.restartIfGuidanceActive()

        onDispose {}
    }

    DisposableEffect(carPhoneSessionCoordinator) {
        carPhoneSessionCoordinator.registerSurface(OneNaviDisplaySurface.AndroidAutoVirtualDisplay)

        onDispose {
            carPhoneSessionCoordinator.unregisterSurface(OneNaviDisplaySurface.AndroidAutoVirtualDisplay)
        }
    }

    when {
        settings == null -> {
            CarVirtualDisplayProbeStatusMessage(
                modifier = modifier,
                text = "Loading OneNavi setting...",
            )
        }

        hasLocationPermission -> {
            val mapRenderScale = rememberCarVirtualDisplayMapRenderScale(viewport.densityDpi)
            CompositionLocalProvider(
                LocalOneNaviDisplaySurface provides OneNaviDisplaySurface.AndroidAutoVirtualDisplay,
                LocalSupportsPlatformDialogWindow provides false,
                LocalMapRenderScale provides mapRenderScale,
            ) {
                OneNaviApp(
                    modifier = modifier,
                    setting = settings,
                )
            }
        }

        else -> {
            OneNaviTheme(
                appSetting = settings,
            ) {
                CarVirtualDisplayProbeStatusMessage(
                    modifier = modifier,
                    text = "スマホ側で位置情報を許可してください",
                )
            }
        }
    }
}

/**
 * VirtualDisplay 上の地図描画スケール係数を算出する。
 *
 * GoogleMap の描画 density はプロセス単位で端末本体（applicationContext）の density に焼き付くため、
 * VirtualDisplay 上では地図だけが `端末本体 density / 表示先 density` 倍に拡大される。地図サブツリーの
 * 座標計算をその焼き付け済み density 空間へ揃えるための係数として、両者の比を返す。
 *
 * @param displayDensityDpi 表示先 VirtualDisplay の densityDpi
 * @return 地図サブツリーへ与える描画スケール係数
 */
@Composable
private fun rememberCarVirtualDisplayMapRenderScale(displayDensityDpi: Int): Float {
    val context = LocalContext.current

    return remember(displayDensityDpi, context) {
        resolveMapRenderScale(
            bakedDensityDpi = context.applicationContext.resources.displayMetrics.densityDpi,
            displayDensityDpi = displayDensityDpi,
        )
    }
}

private fun resolveMapRenderScale(bakedDensityDpi: Int, displayDensityDpi: Int): Float {
    if (displayDensityDpi <= 0) {
        return DEFAULT_MAP_RENDER_SCALE
    }

    return bakedDensityDpi.toFloat() / displayDensityDpi
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
    hostInputPointLabel: String,
    surfacePointLabel: String,
    observedFramePointLabel: String,
    hostVisiblePointLabel: String,
    gestureLabel: String,
    clickCoordinateLabel: String,
    clickCandidateLabel: String,
    mapDensityLabel: String,
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
            text = hostInputPointLabel,
            color = Color(0xFFFDE68A),
            fontSize = 12.sp,
        )
        Text(
            text = surfacePointLabel,
            color = Color(0xFFFDE68A),
            fontSize = 12.sp,
        )
        Text(
            text = observedFramePointLabel,
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
        Text(
            text = clickCoordinateLabel,
            color = Color(0xFFFCA5A5),
            fontSize = 12.sp,
        )
        Text(
            text = clickCandidateLabel,
            color = Color(0xFFA7F3D0),
            fontSize = 12.sp,
        )
        Text(
            text = mapDensityLabel,
            color = Color(0xFFF9A8D4),
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
    inputState: CarVirtualDisplayProbeInputState,
    clickCoordinateResult: CarVirtualDisplayProbeClickCoordinateResult?,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier = modifier,
    ) {
        drawProbeViewportFrames(viewport)
        drawProbeClickCoordinates(
            viewport = viewport,
            inputState = inputState,
            clickCoordinateResult = clickCoordinateResult,
        )
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

private fun DrawScope.drawProbeClickCoordinates(
    viewport: CarVirtualDisplayViewport,
    inputState: CarVirtualDisplayProbeInputState,
    clickCoordinateResult: CarVirtualDisplayProbeClickCoordinateResult?,
) {
    val clickCoordinateCandidates = inputState.createCarVirtualDisplayProbeClickCoordinateCandidates(viewport)

    clickCoordinateCandidates.forEach { candidate ->
        drawProbeClickCoordinateCandidate(candidate)
    }

    if (clickCoordinateResult != null) {
        drawProbeClickCoordinateResult(clickCoordinateResult)
    }
}

private fun DrawScope.drawProbeClickCoordinateCandidate(candidate: CarVirtualDisplayProbeClickCoordinateCandidate) {
    val color = candidate.label.toCandidateColor()

    drawCircle(
        color = color.copy(alpha = 0.36f),
        radius = CLICK_CANDIDATE_RADIUS,
        center = candidate.point,
    )
    drawCircle(
        color = color,
        radius = CLICK_CANDIDATE_RADIUS,
        center = candidate.point,
        style = Stroke(width = CLICK_CANDIDATE_STROKE_WIDTH),
    )
}

private fun DrawScope.drawProbeClickCoordinateResult(result: CarVirtualDisplayProbeClickCoordinateResult) {
    drawCircle(
        color = Color(0xCCEF4444),
        radius = CLICK_RESULT_RADIUS,
        center = result.point,
    )
    drawLine(
        color = Color.White,
        start = Offset(
            x = result.point.x - CLICK_RESULT_CROSS_HALF_LENGTH,
            y = result.point.y,
        ),
        end = Offset(
            x = result.point.x + CLICK_RESULT_CROSS_HALF_LENGTH,
            y = result.point.y,
        ),
        strokeWidth = CLICK_RESULT_CROSS_STROKE_WIDTH,
    )
    drawLine(
        color = Color.White,
        start = Offset(
            x = result.point.x,
            y = result.point.y - CLICK_RESULT_CROSS_HALF_LENGTH,
        ),
        end = Offset(
            x = result.point.x,
            y = result.point.y + CLICK_RESULT_CROSS_HALF_LENGTH,
        ),
        strokeWidth = CLICK_RESULT_CROSS_STROKE_WIDTH,
    )
}

private fun CarVirtualDisplayProbeClickCoordinateResult?.toClickCoordinateLabel(): String {
    if (this == null) {
        return "tap actual=n/a"
    }

    return "tap actual=$label ${point.toPointLabel()}"
}

private fun CarVirtualDisplayProbeInputState.toClickCandidateLabel(viewport: CarVirtualDisplayViewport): String {
    val candidates = createCarVirtualDisplayProbeClickCoordinateCandidates(viewport)

    if (candidates.isEmpty()) {
        return "tap candidates=n/a"
    }

    val candidateLabels = candidates.joinToString(separator = " ") { candidate ->
        "${candidate.label}:${candidate.point.toPointLabel()}"
    }

    return "tap candidates=$candidateLabels"
}

private fun Offset.toPointLabel(): String {
    return "${x.toInt()},${y.toInt()}"
}

/** click 座標候補をデバッグ描画する色。採用座標は赤い円と白い十字で別描画する。 */
private fun String.toCandidateColor(): Color {
    return when (this) {
        CLICK_COORDINATE_OBSERVED_OFFSET_LABEL -> Color(0xFFF59E0B)
        CLICK_COORDINATE_SURFACE_LABEL -> Color(0xFF22D3EE)
        CLICK_COORDINATE_OBSERVED_LABEL -> Color(0xFFA78BFA)
        CLICK_COORDINATE_HOST_VISIBLE_LABEL -> Color(0xFF34D399)
        CLICK_COORDINATE_VISIBLE_SCALED_LABEL -> Color(0xFFF472B6)
        else -> Color(0xFFE5E7EB)
    }
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

/** click 候補位置を示す円の半径。 */
private const val CLICK_CANDIDATE_RADIUS = 9f

/** click 候補位置を示す円の線幅。 */
private const val CLICK_CANDIDATE_STROKE_WIDTH = 2f

/** 実際に採用した click 位置を示す円の半径。 */
private const val CLICK_RESULT_RADIUS = 13f

/** 実際に採用した click 位置を示す十字線の半分の長さ。 */
private const val CLICK_RESULT_CROSS_HALF_LENGTH = 18f

/** 実際に採用した click 位置を示す十字線の線幅。 */
private const val CLICK_RESULT_CROSS_STROKE_WIDTH = 3f
