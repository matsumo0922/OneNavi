package me.matsumo.onenavi.feature.map.components.callout

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import me.matsumo.onenavi.core.model.RoutePoint
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Compose slot を GoogleMap marker icon に変換し、CallOut 配置を一定間隔で更新する effect。
 */
@Composable
internal fun MapCallOutMarkerEffect(
    googleMap: GoogleMap,
    requests: ImmutableList<MapCallOutRequest>,
    viewportPadding: PaddingValues,
    onCallOutClick: (Int, MapCallOutRequest) -> Unit,
    modifier: Modifier = Modifier,
    relayoutInterval: Duration = 1.seconds,
    content: @Composable (
        index: Int,
        request: MapCallOutRequest,
        tailSide: MapCallOutTailSide,
    ) -> Unit,
) {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val markerSpecs = remember(googleMap) { mutableMapOf<String, MapCallOutMarkerState>() }
    val markerClickHandlers = remember(googleMap) { mutableMapOf<String, () -> Unit>() }
    val previousPlacements = remember(googleMap) { mutableMapOf<String, MapCallOutPreviousPlacement>() }
    val measuredSizes = remember { mutableStateMapOf<String, IntSize>() }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var placements by remember { mutableStateOf<List<MapCallOutPlacement>>(emptyList()) }

    DisposableEffect(googleMap) {
        googleMap.setOnMarkerClickListener { marker ->
            val tag = marker.tag as? String ?: return@setOnMarkerClickListener false
            val handler = markerClickHandlers[tag] ?: return@setOnMarkerClickListener false

            handler.invoke()
            true
        }

        onDispose {
            markerSpecs.values.forEach { state ->
                state.marker.remove()
            }
            markerSpecs.clear()
            markerClickHandlers.clear()
            previousPlacements.clear()
            googleMap.setOnMarkerClickListener(null)
        }
    }

    val defaultSize = with(density) {
        IntSize(
            width = (DEFAULT_CALLOUT_WIDTH + MapCallOutDefaults.ShadowPadding * 2).roundToPx(),
            height = (DEFAULT_CALLOUT_HEIGHT + MapCallOutDefaults.ShadowPadding * 2).roundToPx(),
        )
    }
    val tailLengthPx = with(density) { MapCallOutDefaults.TailLength.toPx() }
    val shadowPaddingPx = with(density) { MapCallOutDefaults.ShadowPadding.toPx() }
    val sizes = requests.map { request ->
        measuredSizes[request.id]
            ?.takeIf { it.width > 0 && it.height > 0 }
            ?: defaultSize
    }
    val viewport = viewportPadding.toViewportRect(
        size = viewportSize,
        density = density,
        layoutDirection = layoutDirection,
    )

    LaunchedEffect(
        googleMap,
        requests,
        sizes,
        viewport,
        viewportSize,
        tailLengthPx,
        shadowPaddingPx,
        relayoutInterval,
    ) {
        if (requests.isEmpty() || viewportSize == IntSize.Zero) {
            placements = emptyList()
            return@LaunchedEffect
        }

        while (isActive) {
            val requestsWithPrevious = requests.map { request ->
                request.copy(previousPlacement = previousPlacements[request.id])
            }
            val projectedPoints = requestsWithPrevious.projectedPoints(googleMap)

            placements = withContext(Dispatchers.Default) {
                MapCallOutPlacementEngine.place(
                    requests = requestsWithPrevious,
                    sizes = sizes,
                    viewportSize = viewportSize,
                    viewport = viewport,
                    tailLengthPx = tailLengthPx,
                    shadowPaddingPx = shadowPaddingPx,
                    project = { point -> projectedPoints.getValue(point) },
                )
            }

            delay(relayoutInterval)
        }
    }

    val specs = placements.mapNotNull { placement ->
        val request = requests.elementAtOrNull(placement.requestIndex) ?: return@mapNotNull null
        val descriptor = key(request.id, placement.tailSide, request.contentKey ?: NO_CONTENT_KEY) {
            rememberMapComposeBitmapDescriptor(request.id, placement.tailSide, request.contentKey ?: NO_CONTENT_KEY) {
                Box(
                    modifier = Modifier.onSizeChanged { size ->
                        measuredSizes[request.id] = size
                    },
                ) {
                    content(
                        placement.requestIndex,
                        request,
                        placement.tailSide,
                    )
                }
            }
        }

        MapCallOutMarkerSpec(
            request = request,
            placement = placement,
            icon = descriptor,
        )
    }

    SideEffect {
        syncCallOutMarkers(
            googleMap = googleMap,
            markers = markerSpecs,
            clickHandlers = markerClickHandlers,
            previousPlacements = previousPlacements,
            specs = specs,
            shadowPaddingPx = shadowPaddingPx,
            onCallOutClick = onCallOutClick,
        )
    }

    Box(
        modifier = modifier.onSizeChanged { size ->
            viewportSize = size
        },
    )
}

private fun PaddingValues.toViewportRect(
    size: IntSize,
    density: Density,
    layoutDirection: LayoutDirection,
): Rect {
    val start = with(density) { calculateLeftPadding(layoutDirection).roundToPx() }
    val top = with(density) { calculateTopPadding().roundToPx() }
    val end = with(density) { calculateRightPadding(layoutDirection).roundToPx() }
    val bottom = with(density) { calculateBottomPadding().roundToPx() }
    val right = (size.width - end).coerceAtLeast(start)
    val viewportBottom = (size.height - bottom).coerceAtLeast(top)

    return Rect(
        left = start.toFloat(),
        top = top.toFloat(),
        right = right.toFloat(),
        bottom = viewportBottom.toFloat(),
    )
}

private fun syncCallOutMarkers(
    googleMap: GoogleMap,
    markers: MutableMap<String, MapCallOutMarkerState>,
    clickHandlers: MutableMap<String, () -> Unit>,
    previousPlacements: MutableMap<String, MapCallOutPreviousPlacement>,
    specs: List<MapCallOutMarkerSpec>,
    shadowPaddingPx: Float,
    onCallOutClick: (Int, MapCallOutRequest) -> Unit,
) {
    val activeTags = specs.mapTo(mutableSetOf()) { it.tag }
    val activeRequestIds = specs.mapTo(mutableSetOf()) { it.request.id }
    val markerIterator = markers.iterator()

    while (markerIterator.hasNext()) {
        val (tag, state) = markerIterator.next()
        if (tag !in activeTags) {
            state.marker.remove()
            markerIterator.remove()
        }
    }

    clickHandlers.keys.retainAll(activeTags)
    previousPlacements.keys.retainAll(activeRequestIds)

    for (spec in specs) {
        val anchorX = spec.placement.tailSide.anchorX(
            size = spec.placement.size,
            shadowPaddingPx = shadowPaddingPx,
        )
        val anchorY = spec.placement.anchorY(shadowPaddingPx)

        clickHandlers[spec.tag] = {
            onCallOutClick(
                spec.placement.requestIndex,
                spec.request,
            )
        }

        val position = spec.placement.position.toLatLng()
        val zIndex = CALLOUT_MARKER_Z_INDEX_BASE + (spec.request.zIndexPriority ?: spec.request.priority)
        val markerState = markers[spec.tag] ?: googleMap.addMarker(
            MarkerOptions()
                .position(position)
                .anchor(anchorX, anchorY)
                .icon(spec.icon)
                .zIndex(zIndex),
        )?.let { marker ->
            MapCallOutMarkerState(
                marker = marker,
                icon = spec.icon,
                visualKey = spec.visualKey,
                zIndex = zIndex,
                anchorX = anchorX,
                anchorY = anchorY,
            ).also { state ->
                markers[spec.tag] = state
            }
        } ?: continue

        val marker = markerState.marker
        if (marker.position != position) {
            marker.position = position
        }
        if (markerState.visualKey != spec.visualKey || markerState.icon != spec.icon) {
            marker.setIcon(spec.icon)
            markerState.icon = spec.icon
            markerState.visualKey = spec.visualKey
        }
        if (markerState.anchorX != anchorX || markerState.anchorY != anchorY) {
            marker.setAnchor(anchorX, anchorY)
            markerState.anchorX = anchorX
            markerState.anchorY = anchorY
        }
        if (markerState.zIndex != zIndex) {
            marker.zIndex = zIndex
            markerState.zIndex = zIndex
        }
        marker.isVisible = true
        marker.tag = spec.tag

        previousPlacements[spec.request.id] = MapCallOutPreviousPlacement(
            position = spec.placement.position,
            tailSide = spec.placement.tailSide,
        )
    }
}

private fun List<MapCallOutRequest>.projectedPoints(googleMap: GoogleMap): Map<RoutePoint, Offset> {
    return flatMap { request ->
        buildList {
            when (val target = request.target) {
                is MapCallOutTarget.PointFixed -> add(target.point)
                is MapCallOutTarget.PolylineMovable -> addAll(target.points)
            }
            request.avoidancePolylines.forEach { polyline ->
                addAll(polyline)
            }

            request.previousPlacement?.let { placement ->
                add(placement.position)
            }
        }
    }.distinct().associateWith(googleMap::project)
}

private fun GoogleMap.project(point: RoutePoint): Offset {
    return projection.toScreenLocation(point.toLatLng()).let { screenPoint ->
        Offset(
            x = screenPoint.x.toFloat(),
            y = screenPoint.y.toFloat(),
        )
    }
}

private fun RoutePoint.toLatLng(): LatLng {
    return LatLng(latitude, longitude)
}

private fun MapCallOutTailSide.anchorX(
    size: IntSize,
    shadowPaddingPx: Float,
): Float {
    val width = size.width.toFloat().coerceAtLeast(1f)
    val shadow = shadowPaddingPx.coerceIn(0f, width / 2f)

    return when (this) {
        MapCallOutTailSide.BottomLeft -> shadow / width
        MapCallOutTailSide.BottomRight -> (width - shadow) / width
    }
}

private fun MapCallOutPlacement.anchorY(shadowPaddingPx: Float): Float {
    val height = size.height.toFloat().coerceAtLeast(1f)
    val shadow = shadowPaddingPx.coerceIn(0f, height / 2f)

    return (height - shadow) / height
}

private class MapCallOutMarkerSpec(
    val request: MapCallOutRequest,
    val placement: MapCallOutPlacement,
    val icon: BitmapDescriptor,
) {
    val tag: String = "$MARKER_TAG_PREFIX${request.id}"
    val visualKey: String = "${placement.tailSide}:${request.contentKey ?: NO_CONTENT_KEY}"
}

/**
 * GoogleMap marker の前回反映状態。
 */
private class MapCallOutMarkerState(
    val marker: Marker,
    var icon: BitmapDescriptor,
    var visualKey: String,
    var zIndex: Float,
    var anchorX: Float,
    var anchorY: Float,
)

private val DEFAULT_CALLOUT_WIDTH = 88.dp
private val DEFAULT_CALLOUT_HEIGHT = 44.dp
private const val CALLOUT_MARKER_Z_INDEX_BASE = 20_000f
private const val MARKER_TAG_PREFIX = "map-callout:"
private const val NO_CONTENT_KEY = "__none__"
