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

    /**
     * 2 点間の球面距離を Haversine で計算する。
     *
     * @param from 始点
     * @param to 終点
     * @return 2 点間距離（m）
     */
    fun haversineMeters(from: RoutePoint, to: RoutePoint): Double {
        val fromLatitude = Math.toRadians(from.latitude)
        val toLatitude = Math.toRadians(to.latitude)
        val latitudeDelta = Math.toRadians(to.latitude - from.latitude)
        val longitudeDelta = Math.toRadians(to.longitude - from.longitude)
        val haversine = sin(latitudeDelta / 2).let { value -> value * value } +
            cos(fromLatitude) * cos(toLatitude) *
            sin(longitudeDelta / 2).let { value -> value * value }

        return EARTH_RADIUS_METERS * 2.0 * atan2(sqrt(haversine), sqrt(1.0 - haversine))
    }

    /**
     * 2 点間の球面距離を Haversine で計算する。
     *
     * @param from 始点
     * @param to 終点
     * @return 2 点間距離（m）
     */
    fun haversineMeters(from: LatLng, to: LatLng): Double {
        val fromLatitude = Math.toRadians(from.latitude)
        val toLatitude = Math.toRadians(to.latitude)
        val latitudeDelta = Math.toRadians(to.latitude - from.latitude)
        val longitudeDelta = Math.toRadians(to.longitude - from.longitude)
        val haversine = sin(latitudeDelta / 2).let { value -> value * value } +
            cos(fromLatitude) * cos(toLatitude) *
            sin(longitudeDelta / 2).let { value -> value * value }

        return EARTH_RADIUS_METERS * 2.0 * atan2(sqrt(haversine), sqrt(1.0 - haversine))
    }

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
}
