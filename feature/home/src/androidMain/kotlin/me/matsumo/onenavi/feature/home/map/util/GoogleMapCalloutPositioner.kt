package me.matsumo.onenavi.feature.home.map.util

import androidx.compose.runtime.Immutable
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.LatLng
import me.matsumo.onenavi.core.model.RoutePoint

@Immutable
internal data class CalloutSize(
    val widthPx: Double,
    val heightPx: Double,
)

@Immutable
private data class ScreenRect(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
) {
    fun overlaps(other: ScreenRect): Boolean {
        return left < other.right && right > other.left && top < other.bottom && bottom > other.top
    }
}

internal object GoogleMapCalloutPositioner {

    private const val DEFAULT_MARGIN_PX = 16.0
    private val DEFAULT_OFFSET_CANDIDATES = doubleArrayOf(0.3, 0.7, 0.15, 0.85, 0.05, 0.95)

    fun computePositions(
        googleMap: GoogleMap,
        geometries: List<List<RoutePoint>>,
        calloutSize: CalloutSize,
        marginPx: Double = DEFAULT_MARGIN_PX,
        offsetCandidates: DoubleArray = DEFAULT_OFFSET_CANDIDATES,
    ): List<LatLng?> {
        if (geometries.isEmpty()) return emptyList()

        val visibleBounds = googleMap.projection.visibleRegion.latLngBounds
        val placedRects = mutableListOf<ScreenRect>()

        return geometries.map { geometry ->
            val visiblePoints = geometry.filter { point ->
                visibleBounds.contains(LatLng(point.latitude, point.longitude))
            }

            if (visiblePoints.isEmpty()) {
                return@map null
            }

            var bestPosition: LatLng? = null
            var bestRect: ScreenRect? = null

            for (offset in offsetCandidates) {
                val index = (visiblePoints.size * offset).toInt().coerceIn(0, visiblePoints.lastIndex)
                val candidate = visiblePoints[index]
                val (position, rect) = toScreenRect(
                    googleMap = googleMap,
                    routePoint = candidate,
                    calloutSize = calloutSize,
                    marginPx = marginPx,
                )

                if (placedRects.none(rect::overlaps)) {
                    bestPosition = position
                    bestRect = rect
                    break
                }
            }

            if (bestPosition == null) {
                val fallbackPoint = visiblePoints[visiblePoints.size / 2]
                val (position, rect) = toScreenRect(
                    googleMap = googleMap,
                    routePoint = fallbackPoint,
                    calloutSize = calloutSize,
                    marginPx = marginPx,
                )
                bestPosition = position
                bestRect = rect
            }

            placedRects += checkNotNull(bestRect)
            bestPosition
        }
    }

    private fun toScreenRect(
        googleMap: GoogleMap,
        routePoint: RoutePoint,
        calloutSize: CalloutSize,
        marginPx: Double,
    ): Pair<LatLng, ScreenRect> {
        val latLng = LatLng(routePoint.latitude, routePoint.longitude)
        val screenPoint = googleMap.projection.toScreenLocation(latLng)
        val halfWidth = calloutSize.widthPx / 2.0 + marginPx

        return latLng to ScreenRect(
            left = screenPoint.x - halfWidth,
            top = screenPoint.y - calloutSize.heightPx - marginPx,
            right = screenPoint.x + halfWidth,
            bottom = screenPoint.y + marginPx,
        )
    }
}
