package me.matsumo.onenavi.feature.map.state

import me.matsumo.onenavi.core.model.RoutePoint

/**
 * Route geometry と累積距離 index。
 *
 * @param points route geometry
 * @param cumulativeMeters 各 geometry 点までの累積距離
 */
internal class RouteMeterIndex private constructor(
    private val points: List<RoutePoint>,
    private val cumulativeMeters: List<Double>,
) {
    private val totalMeters: Double = cumulativeMeters.last()

    /**
     * route 上の距離を geometry 範囲内に丸める。
     *
     * @param distanceMeters route 始点からの距離
     * @return route geometry 範囲に収まる距離
     */
    fun coerceDistance(distanceMeters: Double): Double = distanceMeters.coerceIn(0.0, totalMeters)

    /**
     * route 上の距離から表示 pose を作る。
     *
     * @param distanceMeters route 始点からの距離
     * @param fallbackBearingDegrees segment 方位が使えない場合の向き
     * @return route geometry 上に補間した表示 pose
     */
    fun poseAt(
        distanceMeters: Double,
        fallbackBearingDegrees: Float?,
    ): VehiclePose {
        val targetMeters = coerceDistance(distanceMeters)
        val segmentIndex = segmentIndexAt(targetMeters)
        val segmentStart = points[segmentIndex]
        val segmentEnd = points[segmentIndex + 1]
        val segmentStartMeters = cumulativeMeters[segmentIndex]
        val segmentEndMeters = cumulativeMeters[segmentIndex + 1]
        val segmentMeters = segmentEndMeters - segmentStartMeters
        val fraction = if (segmentMeters > 0.0) {
            ((targetMeters - segmentStartMeters) / segmentMeters).coerceIn(0.0, 1.0)
        } else {
            0.0
        }
        val segmentBearingDegrees = if (segmentMeters > 0.0) {
            MapGeodesy.bearingDegrees(segmentStart, segmentEnd)
        } else {
            fallbackBearingDegrees
        }

        return VehiclePose(
            location = RoutePoint(
                latitude = MapInterpolation.lerp(segmentStart.latitude, segmentEnd.latitude, fraction),
                longitude = MapInterpolation.lerp(segmentStart.longitude, segmentEnd.longitude, fraction),
            ),
            bearingDegrees = segmentBearingDegrees,
        )
    }

    /**
     * 指定距離を含む geometry segment index を二分探索で返す。
     *
     * @param distanceMeters route 始点からの距離
     * @return [points] の segment 開始 index
     */
    private fun segmentIndexAt(distanceMeters: Double): Int {
        var low = 0
        var high = cumulativeMeters.lastIndex

        while (low <= high) {
            val mid = (low + high).ushr(1)
            val midValue = cumulativeMeters[mid]

            when {
                midValue < distanceMeters -> low = mid + 1
                midValue > distanceMeters -> high = mid - 1
                else -> return mid.coerceAtMost(points.lastIndex - 1)
            }
        }

        return (low - 1).coerceIn(0, points.lastIndex - 1)
    }

    companion object {

        /** Route geometry として扱うために必要な最小点数。 */
        private const val MIN_ROUTE_GEOMETRY_POINT_COUNT = 2

        /**
         * route geometry が累積距離 index を作れる点数を持つかを返す。
         *
         * @param points route geometry
         * @return index 作成に必要な点数がある場合 true
         */
        fun canBuild(points: List<RoutePoint>): Boolean = points.size >= MIN_ROUTE_GEOMETRY_POINT_COUNT

        /**
         * route geometry から累積距離 index を作る。
         *
         * @param points route geometry
         * @return 2 点以上の geometry から作った index。点数不足の場合は null
         */
        fun from(points: List<RoutePoint>): RouteMeterIndex? {
            if (!canBuild(points)) return null

            val cumulativeMeters = buildList(capacity = points.size) {
                var totalMeters = 0.0
                add(totalMeters)

                for (index in 1 until points.size) {
                    totalMeters += MapGeodesy.haversineMeters(points[index - 1], points[index])
                    add(totalMeters)
                }
            }

            return RouteMeterIndex(
                points = points,
                cumulativeMeters = cumulativeMeters,
            )
        }
    }
}
