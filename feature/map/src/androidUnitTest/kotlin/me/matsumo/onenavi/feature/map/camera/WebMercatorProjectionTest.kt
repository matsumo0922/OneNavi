package me.matsumo.onenavi.feature.map.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebMercatorProjectionTest {

    private val tolerance = 1e-9

    @Test
    fun `経度はワールドピクセルの X 軸に線形写像される`() {
        assertEquals(0.0, WebMercatorProjection.longitudeToWorldX(-180.0), tolerance)
        assertEquals(128.0, WebMercatorProjection.longitudeToWorldX(0.0), tolerance)
        assertEquals(256.0, WebMercatorProjection.longitudeToWorldX(180.0), tolerance)
    }

    @Test
    fun `赤道はワールドピクセルの中央にくる`() {
        assertEquals(128.0, WebMercatorProjection.latitudeToWorldY(0.0), tolerance)
    }

    @Test
    fun `北の緯度ほど Y は小さくなる`() {
        val tokyo = WebMercatorProjection.latitudeToWorldY(35.681236)
        val equator = WebMercatorProjection.latitudeToWorldY(0.0)
        assertTrue(tokyo < equator)
    }

    @Test
    fun `経度の往復変換で値が保たれる`() {
        for (longitude in listOf(-179.9, -90.0, -12.3, 0.0, 45.6, 139.767125, 179.9)) {
            val roundTrip = WebMercatorProjection.worldXToLongitude(
                WebMercatorProjection.longitudeToWorldX(longitude),
            )
            assertEquals(longitude, roundTrip, tolerance)
        }
    }

    @Test
    fun `緯度の往復変換で値が保たれる`() {
        for (latitude in listOf(-80.0, -35.0, -1.2, 0.0, 12.3, 35.681236, 80.0)) {
            val roundTrip = WebMercatorProjection.worldYToLatitude(
                WebMercatorProjection.latitudeToWorldY(latitude),
            )
            assertEquals(latitude, roundTrip, 1e-7)
        }
    }

    @Test
    fun `Web Mercator の上限を超える緯度はクランプされる`() {
        val clamped = WebMercatorProjection.latitudeToWorldY(89.9)
        val limit = WebMercatorProjection.latitudeToWorldY(85.05112878)
        assertEquals(limit, clamped, tolerance)
    }
}
