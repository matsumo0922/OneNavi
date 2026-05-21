package me.matsumo.onenavi.feature.map.state

import com.google.android.gms.maps.model.LatLng
import me.matsumo.onenavi.core.model.RoutePoint
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 地図表示で使う軽量な球面幾何 helper。
 */
internal object MapGeodesy {

    /** Haversine 計算に使う平均地球半径（m）。 */
    private const val EARTH_RADIUS_METERS = 6_371_008.8

    /** 経度正規化で 0 未満の剰余を避けるための offset。 */
    private const val LONGITUDE_NORMALIZE_OFFSET_DEGREES = 540.0

    /** 経度正規化に使う半周分の角度。 */
    private const val HALF_CIRCLE_DEGREES = 180.0

    /** 経度正規化に使う 1 周分の角度。 */
    private const val FULL_CIRCLE_DEGREES = 360.0

    /** 方位差を -180〜180 度へ正規化するための offset。 */
    private const val ANGLE_DELTA_NORMALIZE_OFFSET_DEGREES = 540.0

    /**
     * 2 点間の球面距離を Haversine で計算する。
     *
     * @param from 始点
     * @param to 終点
     * @return 2 点間距離（m）
     */
    fun haversineMeters(from: RoutePoint, to: RoutePoint): Double = haversineMeters(
        fromLatitude = from.latitude,
        fromLongitude = from.longitude,
        toLatitude = to.latitude,
        toLongitude = to.longitude,
    )

    /**
     * 2 点間の球面距離を Haversine で計算する。
     *
     * @param from 始点
     * @param to 終点
     * @return 2 点間距離（m）
     */
    fun haversineMeters(from: LatLng, to: LatLng): Double = haversineMeters(
        fromLatitude = from.latitude,
        fromLongitude = from.longitude,
        toLatitude = to.latitude,
        toLongitude = to.longitude,
    )

    /**
     * 2 点を結ぶ初期方位を返す。
     *
     * @param from 始点
     * @param to 終点
     * @return 北を 0 度とする時計回り方位
     */
    fun bearingDegrees(from: RoutePoint, to: RoutePoint): Float {
        val fromLatitude = Math.toRadians(from.latitude)
        val toLatitude = Math.toRadians(to.latitude)
        val longitudeDelta = Math.toRadians(to.longitude - from.longitude)
        val y = sin(longitudeDelta) * cos(toLatitude)
        val x = cos(fromLatitude) * sin(toLatitude) -
            sin(fromLatitude) * cos(toLatitude) * cos(longitudeDelta)

        return normalizeBearingDegrees(Math.toDegrees(atan2(y, x)).toFloat())
    }

    /**
     * 2 点を結ぶ初期方位を返す。
     *
     * @param from 始点。null の場合は null
     * @param to 終点
     * @return 北を 0 度とする時計回り方位。始点が無い場合や同一点の場合は null
     */
    fun bearingDegreesOrNull(from: RoutePoint?, to: RoutePoint): Float? {
        if (from == null || from == to) return null

        return bearingDegrees(from = from, to = to)
    }

    /**
     * 方位角を 0〜360 度へ正規化する。
     *
     * @param bearingDegrees 正規化前の方位
     * @return 正規化後の方位
     */
    fun normalizeBearingDegrees(bearingDegrees: Float): Float =
        ((bearingDegrees + FULL_CIRCLE_DEGREES.toFloat()) % FULL_CIRCLE_DEGREES.toFloat())

    /**
     * 2 つの方位角の最短差分を返す。
     *
     * @param from 開始方位
     * @param to 目標方位
     * @return -180〜180 度の差分
     */
    fun shortestAngleDeltaDegrees(from: Float, to: Float): Float =
        ((to - from + ANGLE_DELTA_NORMALIZE_OFFSET_DEGREES) % FULL_CIRCLE_DEGREES - HALF_CIRCLE_DEGREES)
            .toFloat()

    /**
     * 2 つの方位角の最短差分を絶対値で返す。
     *
     * @param from 開始方位
     * @param to 目標方位
     * @return 0〜180 度の差分
     */
    fun angleDistanceDegrees(from: Float, to: Float): Float =
        kotlin.math.abs(shortestAngleDeltaDegrees(from = from, to = to))

    /**
     * 始点から指定距離だけ指定方位へ進んだ地点を返す。
     *
     * @param origin 始点
     * @param bearingDegrees 北を 0 度とする時計回り方位
     * @param distanceMeters 移動距離（m）
     * @return 移動後の緯度経度
     */
    fun destinationPoint(
        origin: RoutePoint,
        bearingDegrees: Float,
        distanceMeters: Double,
    ): RoutePoint {
        val target = destinationLatLng(
            origin = origin,
            bearingDegrees = bearingDegrees,
            distanceMeters = distanceMeters,
        )

        return RoutePoint(
            latitude = target.latitude,
            longitude = target.longitude,
        )
    }

    /**
     * 始点から指定距離だけ指定方位へ進んだ地点を GoogleMap 座標で返す。
     *
     * @param origin 始点
     * @param bearingDegrees 北を 0 度とする時計回り方位
     * @param distanceMeters 移動距離（m）
     * @return 移動後の GoogleMap 座標
     */
    fun destinationLatLng(
        origin: RoutePoint,
        bearingDegrees: Float,
        distanceMeters: Double,
    ): LatLng {
        val angularDistance = distanceMeters / EARTH_RADIUS_METERS
        val bearingRadians = Math.toRadians(bearingDegrees.toDouble())
        val originLatitude = Math.toRadians(origin.latitude)
        val originLongitude = Math.toRadians(origin.longitude)
        val targetLatitude = asin(
            sin(originLatitude) * cos(angularDistance) +
                cos(originLatitude) * sin(angularDistance) * cos(bearingRadians),
        )
        val targetLongitude = originLongitude + atan2(
            sin(bearingRadians) * sin(angularDistance) * cos(originLatitude),
            cos(angularDistance) - sin(originLatitude) * sin(targetLatitude),
        )

        return LatLng(
            Math.toDegrees(targetLatitude),
            normalizeLongitude(Math.toDegrees(targetLongitude)),
        )
    }

    /**
     * 経度を -180〜180 度へ正規化する。
     *
     * @param longitude 正規化前の経度
     * @return 正規化後の経度
     */
    fun normalizeLongitude(longitude: Double): Double =
        ((longitude + LONGITUDE_NORMALIZE_OFFSET_DEGREES) % FULL_CIRCLE_DEGREES) -
            HALF_CIRCLE_DEGREES

    /**
     * 2 点間の球面距離を Haversine で計算する。
     *
     * @param fromLatitude 始点緯度
     * @param fromLongitude 始点経度
     * @param toLatitude 終点緯度
     * @param toLongitude 終点経度
     * @return 2 点間距離（m）
     */
    private fun haversineMeters(
        fromLatitude: Double,
        fromLongitude: Double,
        toLatitude: Double,
        toLongitude: Double,
    ): Double {
        val fromLatitudeRadians = Math.toRadians(fromLatitude)
        val toLatitudeRadians = Math.toRadians(toLatitude)
        val latitudeDelta = Math.toRadians(toLatitude - fromLatitude)
        val longitudeDelta = Math.toRadians(toLongitude - fromLongitude)
        val haversine = sin(latitudeDelta / 2).let { value -> value * value } +
            cos(fromLatitudeRadians) * cos(toLatitudeRadians) *
            sin(longitudeDelta / 2).let { value -> value * value }

        return EARTH_RADIUS_METERS * 2.0 * atan2(sqrt(haversine), sqrt(1.0 - haversine))
    }
}
