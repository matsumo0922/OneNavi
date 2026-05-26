package me.matsumo.onenavi.core.navigation.extnav

import me.matsumo.onenavi.core.model.RoutePoint
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * route geometry (polyline) 上の距離・方位・座標補間を行う純粋な計算ヘルパ。
 *
 * tick 時の位置投影 ([ExtNavGuidanceTracker]) と attach 時の案内イベント構築
 * (semantic mapper) の双方から使う共通実装。状態を持たず、引数の geometry と
 * 累積距離配列だけで完結する。
 */
internal object RouteGeometryMath {

    /** haversine 距離計算で使う地球半径メートル。 */
    private const val EARTH_RADIUS_METRES: Double = 6_371_000.0

    /** 方位角の 1 周分の度数。 */
    private const val FULL_CIRCLE_DEGREES: Float = 360f

    /** 方位差を正規化するときの半周分の度数。 */
    private const val HALF_CIRCLE_DEGREES: Float = 180f

    /**
     * route geometry の各点までの累積距離を作る。
     *
     * @param geometry route polyline
     * @return geometry index と同じ順序の累積距離配列
     */
    fun cumulativeMetres(geometry: List<RoutePoint>): DoubleArray {
        if (geometry.isEmpty()) return DoubleArray(0)

        val cumulativeMetres = DoubleArray(geometry.size)
        for (index in 1 until geometry.size) {
            val previousMetres = cumulativeMetres[index - 1]
            val stepMetres = haversineMetres(geometry[index - 1], geometry[index])
            cumulativeMetres[index] = previousMetres + stepMetres
        }
        return cumulativeMetres
    }

    /**
     * 累積距離から、その距離を含む segment index を二分探索で求める。
     *
     * @param cumulativeMetres geometry index ごとの累積距離
     * @param targetMetres 探索する geometry 累積距離
     * @return targetMetres を含む segment index
     */
    fun segmentIndexAt(cumulativeMetres: DoubleArray, targetMetres: Double): Int {
        if (cumulativeMetres.size <= 1) return 0
        if (targetMetres <= 0.0) return 0
        if (targetMetres >= cumulativeMetres.last()) return cumulativeMetres.lastIndex - 1

        var lowIndex = 1
        var highIndex = cumulativeMetres.lastIndex
        while (lowIndex < highIndex) {
            val middleIndex = (lowIndex + highIndex) / 2
            if (cumulativeMetres[middleIndex] < targetMetres) {
                lowIndex = middleIndex + 1
            } else {
                highIndex = middleIndex
            }
        }
        return (lowIndex - 1).coerceIn(0, cumulativeMetres.lastIndex - 1)
    }

    /**
     * route geometry 上の累積距離から座標を補間する。
     *
     * @param geometry 座標を求める route polyline
     * @param cumulativeMetres geometry index ごとの累積距離
     * @param targetMetres 座標を求める geometry 累積距離
     * @param fallback geometry が空のときに返す座標
     * @return route geometry 上の座標
     */
    fun pointAt(
        geometry: List<RoutePoint>,
        cumulativeMetres: DoubleArray,
        targetMetres: Double,
        fallback: RoutePoint,
    ): RoutePoint {
        if (geometry.isEmpty()) return fallback
        if (geometry.size == 1 || cumulativeMetres.size <= 1) return geometry.first()

        val coercedTargetMetres = targetMetres.coerceIn(0.0, cumulativeMetres.last())
        val segmentIndex = segmentIndexAt(cumulativeMetres, coercedTargetMetres).coerceIn(0, geometry.lastIndex - 1)
        val segmentStartMetres = cumulativeMetres[segmentIndex]
        val segmentEndMetres = cumulativeMetres[segmentIndex + 1]
        val ratio = segmentRatio(
            targetMetres = coercedTargetMetres,
            segmentStartMetres = segmentStartMetres,
            segmentEndMetres = segmentEndMetres,
        )
        return interpolateRoutePoint(
            start = geometry[segmentIndex],
            end = geometry[segmentIndex + 1],
            ratio = ratio,
        )
    }

    /**
     * 指定距離付近の前後 segment 方位差を計算する。
     *
     * @param geometry 方位計算対象の route polyline
     * @param cumulativeMetres geometry index ごとの累積距離
     * @param targetMetres 方位差を求める geometry 累積距離
     * @return -180 度から 180 度に正規化した方位差
     */
    fun bearingDiffAt(
        geometry: List<RoutePoint>,
        cumulativeMetres: DoubleArray,
        targetMetres: Double,
    ): Float {
        if (geometry.size < 3 || cumulativeMetres.size < 3) return 0f

        val segmentIndex = segmentIndexAt(cumulativeMetres, targetMetres)
        val beforeIndex = (segmentIndex - 1).coerceAtLeast(0)
        val afterIndex = (segmentIndex + 1).coerceAtMost(geometry.lastIndex - 1)
        val beforeBearing = bearingDegrees(geometry[beforeIndex], geometry[beforeIndex + 1])
        val afterBearing = bearingDegrees(geometry[afterIndex], geometry[afterIndex + 1])
        return normalizeDegrees(afterBearing - beforeBearing)
    }

    /**
     * 2 点間の球面距離を haversine で計算する。
     *
     * @param from 始点
     * @param to 終点
     * @return 2 点間距離メートル
     */
    fun haversineMetres(from: RoutePoint, to: RoutePoint): Double {
        val fromLatRadians = latitudeRadians(from)
        val toLatRadians = latitudeRadians(to)
        val deltaLatRadians = Math.toRadians(to.latitude - from.latitude)
        val deltaLngRadians = Math.toRadians(to.longitude - from.longitude)
        val halfLatSinSquared = sin(deltaLatRadians / 2.0) * sin(deltaLatRadians / 2.0)
        val halfLngSinSquared = sin(deltaLngRadians / 2.0) * sin(deltaLngRadians / 2.0)
        val haversineTerm = halfLatSinSquared + cos(fromLatRadians) * cos(toLatRadians) * halfLngSinSquared
        return EARTH_RADIUS_METRES * 2.0 * atan2(sqrt(haversineTerm), sqrt(1.0 - haversineTerm))
    }

    /**
     * 2 点を結ぶ進行方位を計算する。
     *
     * @param from 始点
     * @param to 終点
     * @return 0 度以上 360 度未満の方位角
     */
    fun bearingDegrees(from: RoutePoint, to: RoutePoint): Float {
        val fromLatRadians = latitudeRadians(from)
        val toLatRadians = latitudeRadians(to)
        val deltaLngRadians = Math.toRadians(to.longitude - from.longitude)
        val y = sin(deltaLngRadians) * cos(toLatRadians)
        val x = cos(fromLatRadians) * sin(toLatRadians) - sin(fromLatRadians) * cos(toLatRadians) * cos(deltaLngRadians)
        return ((Math.toDegrees(atan2(y, x)) + FULL_CIRCLE_DEGREES) % FULL_CIRCLE_DEGREES).toFloat()
    }

    /**
     * 方位差を -180 度から 180 度の範囲へ正規化する。
     *
     * @param degrees 正規化前の角度
     * @return 正規化後の角度
     */
    fun normalizeDegrees(degrees: Float): Float {
        var normalized = degrees % FULL_CIRCLE_DEGREES
        if (normalized > HALF_CIRCLE_DEGREES) normalized -= FULL_CIRCLE_DEGREES
        if (normalized < -HALF_CIRCLE_DEGREES) normalized += FULL_CIRCLE_DEGREES
        return normalized
    }

    /**
     * segment 始点と終点を比率で線形補間する。
     *
     * @param start segment 始点
     * @param end segment 終点
     * @param ratio segment 内比率
     * @return 補間後の route point
     */
    fun interpolateRoutePoint(
        start: RoutePoint,
        end: RoutePoint,
        ratio: Double,
    ): RoutePoint = RoutePoint(
        latitude = start.latitude + (end.latitude - start.latitude) * ratio,
        longitude = start.longitude + (end.longitude - start.longitude) * ratio,
    )

    /**
     * targetMetres が segment 内のどの比率に当たるかを 0.0〜1.0 で返す。
     *
     * segment 長が 0 以下の場合は 0.0 を返す。
     */
    private fun segmentRatio(
        targetMetres: Double,
        segmentStartMetres: Double,
        segmentEndMetres: Double,
    ): Double {
        val segmentMetres = segmentEndMetres - segmentStartMetres
        if (segmentMetres <= 0.0) return 0.0
        val rawRatio = (targetMetres - segmentStartMetres) / segmentMetres
        return rawRatio.coerceIn(0.0, 1.0)
    }

    /** route point の緯度をラジアンへ変換する。 */
    private fun latitudeRadians(point: RoutePoint): Double = Math.toRadians(point.latitude)
}
