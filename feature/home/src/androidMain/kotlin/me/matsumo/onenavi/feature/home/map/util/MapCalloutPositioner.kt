package me.matsumo.onenavi.feature.home.map.util

import androidx.compose.runtime.Immutable
import com.mapbox.geojson.Point
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.MapboxMap
import com.mapbox.maps.ScreenCoordinate
import me.matsumo.onenavi.core.model.RoutePoint

/**
 * 吹き出しの推定サイズ（ピクセル単位）。
 *
 * @param widthPx 吹き出しの幅
 * @param heightPx 吹き出しの高さ
 */
@Immutable
data class CalloutSize(
    val widthPx: Double,
    val heightPx: Double,
)

/**
 * スクリーン座標系の矩形。重なり判定に使用する。
 *
 * @param left 左端 X 座標
 * @param top 上端 Y 座標
 * @param right 右端 X 座標
 * @param bottom 下端 Y 座標
 */
@Immutable
data class ScreenRect(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
) {
    /** この矩形が [other] と重なるかを返す。 */
    fun overlaps(other: ScreenRect): Boolean =
        left < other.right && right > other.left && top < other.bottom && bottom > other.top
}

/**
 * 地図上の吹き出し配置位置を計算するユーティリティ。
 *
 * ルートプレビュー吹き出しに限らず、任意の座標列に対して
 * ビューポート追従 + 重なり回避付きの配置位置を返す。
 *
 * ## 使い方
 * ```kotlin
 * val points = MapCalloutPositioner.computePositions(
 *     mapboxMap = mapboxMap,
 *     geometries = routes.map { it.item.geometry },
 *     calloutSize = CalloutSize(widthPx = 180.0, heightPx = 80.0),
 * )
 * ```
 */
object MapCalloutPositioner {

    private const val DEFAULT_MARGIN_PX = 16.0

    /**
     * 重なり回避で試行する、可視区間上のオフセット候補。
     * 中央を避けて前方・後方の候補を試行し、空きスペースを探す。
     */
    private val DEFAULT_OFFSET_CANDIDATES = doubleArrayOf(0.3, 0.7, 0.15, 0.85, 0.05, 0.95)

    /**
     * 各座標列に対して、ビューポート内の可視区間から重なりを回避した配置位置を計算する。
     *
     * @param mapboxMap 現在のカメラ状態を取得するための MapboxMap
     * @param geometries 吹き出しごとの座標列。各リストの可視区間から配置位置を決定する
     * @param calloutSize 吹き出しの推定サイズ（ピクセル）
     * @param marginPx 矩形の重なり判定に加えるマージン（ピクセル）
     * @param offsetCandidates 可視区間上で試行するオフセット（0.0〜1.0）
     * @return 各座標列に対応する配置位置。ビューポート外の場合は null
     */
    fun computePositions(
        mapboxMap: MapboxMap,
        geometries: List<List<RoutePoint>>,
        calloutSize: CalloutSize,
        marginPx: Double = DEFAULT_MARGIN_PX,
        offsetCandidates: DoubleArray = DEFAULT_OFFSET_CANDIDATES,
    ): List<Point?> {
        if (geometries.isEmpty()) return emptyList()

        val cameraState = mapboxMap.cameraState
        val bounds = mapboxMap.coordinateBoundsForCamera(
            CameraOptions.Builder()
                .center(cameraState.center)
                .zoom(cameraState.zoom)
                .bearing(cameraState.bearing)
                .pitch(cameraState.pitch)
                .padding(cameraState.padding)
                .build(),
        )

        val south = bounds.southwest.latitude()
        val north = bounds.northeast.latitude()
        val west = bounds.southwest.longitude()
        val east = bounds.northeast.longitude()

        val visibleSegments = geometries.map { geometry ->
            geometry.filter { point ->
                point.latitude in south..north && point.longitude in west..east
            }
        }

        val placedRects = mutableListOf<ScreenRect>()
        val resultPoints = mutableListOf<Point?>()

        for (visiblePoints in visibleSegments) {
            if (visiblePoints.isEmpty()) {
                resultPoints.add(null)
                continue
            }

            var bestPoint: Point? = null
            var bestRect: ScreenRect? = null

            for (offset in offsetCandidates) {
                val index = (visiblePoints.size * offset).toInt()
                    .coerceIn(0, visiblePoints.lastIndex)
                val candidate = visiblePoints[index]
                val (point, rect) = toScreenRect(
                    mapboxMap = mapboxMap,
                    routePoint = candidate,
                    calloutSize = calloutSize,
                    marginPx = marginPx,
                )

                val overlaps = placedRects.any { placed -> rect.overlaps(placed) }
                if (!overlaps) {
                    bestPoint = point
                    bestRect = rect
                    break
                }
            }

            // 全候補が重なる場合はフォールバックとして中央を使用
            if (bestPoint == null) {
                val mid = visiblePoints[visiblePoints.size / 2]
                val (point, rect) = toScreenRect(
                    mapboxMap = mapboxMap,
                    routePoint = mid,
                    calloutSize = calloutSize,
                    marginPx = marginPx,
                )
                bestPoint = point
                bestRect = rect
            }

            resultPoints.add(bestPoint)
            placedRects.add(bestRect!!)
        }

        return resultPoints
    }

    /**
     * 座標点をスクリーン投影し、吹き出し本体の推定矩形を返す。
     * 吹き出しはアンカー座標の上方に展開されるため、矩形は座標の上側に配置する。
     */
    private fun toScreenRect(
        mapboxMap: MapboxMap,
        routePoint: RoutePoint,
        calloutSize: CalloutSize,
        marginPx: Double,
    ): Pair<Point, ScreenRect> {
        val geoPoint = Point.fromLngLat(routePoint.longitude, routePoint.latitude)
        val screen: ScreenCoordinate = mapboxMap.pixelForCoordinate(geoPoint)
        val halfW = calloutSize.widthPx / 2.0 + marginPx
        val rect = ScreenRect(
            left = screen.x - halfW,
            top = screen.y - calloutSize.heightPx - marginPx,
            right = screen.x + halfW,
            bottom = screen.y + marginPx,
        )
        return geoPoint to rect
    }
}
