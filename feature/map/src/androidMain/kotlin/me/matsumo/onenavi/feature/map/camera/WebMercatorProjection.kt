package me.matsumo.onenavi.feature.map.camera

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/**
 * Web Mercator のズーム 0 ワールドピクセル空間（GoogleMap と同じ 256px タイル基準）と
 * 緯度経度を相互変換するユーティリティ。
 *
 * GoogleMap のカメラ位置を van Wijk–Nuij の fly-to 経路（[VanWijkZoomPath]）に渡すには、
 * 緯度経度をここで「ズーム 0 のワールドピクセル」に変換し、戻すときは逆変換を使う。
 */
internal object WebMercatorProjection {

    /** ズーム 0 でのワールドの一辺の長さ（ピクセル）。GoogleMap のタイルサイズに合わせて 256。 */
    const val WORLD_SIZE_PX = 256.0

    private const val DEGREES_PER_HALF_TURN = 180.0
    private const val DEGREES_PER_FULL_TURN = 360.0

    /** Web Mercator が表現できる緯度の上限（度）。これを超える緯度はクランプする。 */
    private const val MAX_LATITUDE_DEGREES = 85.05112878

    /** 経度を、ズーム 0 ワールドピクセル空間の X 座標 `[0, WORLD_SIZE_PX]` に変換する。 */
    fun longitudeToWorldX(longitude: Double): Double =
        (longitude + DEGREES_PER_HALF_TURN) / DEGREES_PER_FULL_TURN * WORLD_SIZE_PX

    /** 緯度を、ズーム 0 ワールドピクセル空間の Y 座標 `[0, WORLD_SIZE_PX]` に変換する。 */
    fun latitudeToWorldY(latitude: Double): Double {
        val latitudeRad = degreesToRadians(latitude.coerceIn(-MAX_LATITUDE_DEGREES, MAX_LATITUDE_DEGREES))
        val mercatorY = ln(tan(latitudeRad) + 1.0 / cos(latitudeRad))
        return (1.0 - mercatorY / PI) / 2.0 * WORLD_SIZE_PX
    }

    /** ズーム 0 ワールドピクセル空間の X 座標を経度に戻す。 */
    fun worldXToLongitude(worldX: Double): Double =
        worldX / WORLD_SIZE_PX * DEGREES_PER_FULL_TURN - DEGREES_PER_HALF_TURN

    /** ズーム 0 ワールドピクセル空間の Y 座標を緯度に戻す。 */
    fun worldYToLatitude(worldY: Double): Double {
        val mercatorY = (1.0 - worldY / WORLD_SIZE_PX * 2.0) * PI
        return radiansToDegrees(atan(sinh(mercatorY)))
    }

    private fun degreesToRadians(degrees: Double): Double = degrees * PI / DEGREES_PER_HALF_TURN

    private fun radiansToDegrees(radians: Double): Double = radians * DEGREES_PER_HALF_TURN / PI
}
