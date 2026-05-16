package me.matsumo.onenavi.feature.map.components.callout

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
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
import com.google.maps.android.compose.MapsComposeExperimentalApi
import com.google.maps.android.compose.rememberComposeBitmapDescriptor
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import me.matsumo.onenavi.core.model.RoutePoint
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Compose slot を GoogleMap marker icon に変換し、CallOut 配置を一定間隔で更新する effect。
 */
@OptIn(MapsComposeExperimentalApi::class)
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
    val markerSpecs = remember(googleMap) { mutableMapOf<String, Marker>() }
    val markerClickHandlers = remember(googleMap) { mutableMapOf<String, () -> Unit>() }
    val previousPlacements = remember(googleMap) { mutableMapOf<String, MapCallOutPreviousPlacement>() }
    val measuredSizes = remember { mutableStateMapOf<String, IntSize>() }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var relayoutTick by remember { mutableIntStateOf(0) }

    DisposableEffect(googleMap) {
        googleMap.setOnMarkerClickListener { marker ->
            val tag = marker.tag as? String ?: return@setOnMarkerClickListener false
            val handler = markerClickHandlers[tag] ?: return@setOnMarkerClickListener false

            handler.invoke()
            true
        }

        onDispose {
            markerSpecs.values.forEach(Marker::remove)
            markerSpecs.clear()
            markerClickHandlers.clear()
            previousPlacements.clear()
            googleMap.setOnMarkerClickListener(null)
        }
    }

    LaunchedEffect(requests, relayoutInterval) {
        if (requests.isEmpty()) return@LaunchedEffect

        relayoutTick += 1
        while (isActive) {
            delay(relayoutInterval)
            relayoutTick += 1
        }
    }

    val defaultSize = with(density) {
        IntSize(
            width = DEFAULT_CALLOUT_WIDTH.roundToPx(),
            height = DEFAULT_CALLOUT_HEIGHT.roundToPx(),
        )
    }
    val tailLengthPx = with(density) { MapCallOutDefaults.TailLength.toPx() }
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
    val placements = remember(
        googleMap,
        requests,
        sizes,
        viewport,
        viewportSize,
        tailLengthPx,
        relayoutTick,
    ) {
        if (requests.isEmpty() || viewportSize == IntSize.Zero) {
            emptyList()
        } else {
            val requestsWithPrevious = requests.map { request ->
                request.copy(previousPlacement = previousPlacements[request.id])
            }

            MapCallOutPlacementEngine.place(
                requests = requestsWithPrevious,
                sizes = sizes,
                viewportSize = viewportSize,
                viewport = viewport,
                tailLengthPx = tailLengthPx,
                project = googleMap::project,
            )
        }
    }
    val specs = placements.map { placement ->
        val request = requests[placement.requestIndex]
        val descriptor = key(request.id, placement.tailSide, request.contentKey ?: NO_CONTENT_KEY) {
            rememberComposeBitmapDescriptor(
                request.id,
                placement.tailSide,
                request.contentKey ?: NO_CONTENT_KEY,
            ) {
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
    markers: MutableMap<String, Marker>,
    clickHandlers: MutableMap<String, () -> Unit>,
    previousPlacements: MutableMap<String, MapCallOutPreviousPlacement>,
    specs: List<MapCallOutMarkerSpec>,
    onCallOutClick: (Int, MapCallOutRequest) -> Unit,
) {
    val activeTags = specs.mapTo(mutableSetOf()) { it.tag }
    val activeRequestIds = specs.mapTo(mutableSetOf()) { it.request.id }
    val markerIterator = markers.iterator()

    while (markerIterator.hasNext()) {
        val (tag, marker) = markerIterator.next()
        if (tag !in activeTags) {
            marker.remove()
            markerIterator.remove()
        }
    }

    clickHandlers.keys.retainAll(activeTags)
    previousPlacements.keys.retainAll(activeRequestIds)

    for (spec in specs) {
        clickHandlers[spec.tag] = {
            onCallOutClick(
                spec.placement.requestIndex,
                spec.request,
            )
        }

        val marker = markers[spec.tag] ?: googleMap.addMarker(
            MarkerOptions()
                .position(spec.placement.position.toLatLng())
                .anchor(spec.placement.tailSide.anchorX(), 1f)
                .icon(spec.icon)
                .zIndex(spec.request.priority.toFloat()),
        ) ?: continue

        markers[spec.tag] = marker
        marker.position = spec.placement.position.toLatLng()
        marker.setAnchor(spec.placement.tailSide.anchorX(), 1f)
        marker.setIcon(spec.icon)
        marker.zIndex = spec.request.priority.toFloat()
        marker.isVisible = true
        marker.tag = spec.tag

        previousPlacements[spec.request.id] = MapCallOutPreviousPlacement(
            position = spec.placement.position,
            tailSide = spec.placement.tailSide,
        )
    }
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

private fun MapCallOutTailSide.anchorX(): Float {
    return when (this) {
        MapCallOutTailSide.BottomLeft -> 0f
        MapCallOutTailSide.BottomRight -> 1f
    }
}

private class MapCallOutMarkerSpec(
    val request: MapCallOutRequest,
    val placement: MapCallOutPlacement,
    val icon: BitmapDescriptor,
) {
    val tag: String = "$MARKER_TAG_PREFIX${request.id}"
}

private val DEFAULT_CALLOUT_WIDTH = 88.dp
private val DEFAULT_CALLOUT_HEIGHT = 44.dp
private const val MARKER_TAG_PREFIX = "map-callout:"
private const val NO_CONTENT_KEY = "__none__"
