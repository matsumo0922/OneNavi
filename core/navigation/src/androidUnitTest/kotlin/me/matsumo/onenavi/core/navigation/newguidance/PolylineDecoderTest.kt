package me.matsumo.onenavi.core.navigation.newguidance

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [PolylineDecoder] の単体テスト。
 *
 * Google Maps 公式ドキュメントが例示している `_p~iF~ps|U_ulLnnqC_mqNvxq``\``` を
 * 公式の expected 出力 `(38.5, -120.2), (40.7, -120.95), (43.252, -126.453)` と比較する。
 */
class PolylineDecoderTest {

    @Test
    fun `空文字を渡すと空リストを返す`() {
        assertEquals(emptyList(), PolylineDecoder.decode(""))
    }

    @Test
    fun `Google 公式テストベクトルをデコードできる`() {
        // 公式 docs の例: 3 頂点を持つルートをエンコードした文字列
        val encoded = "_p~iF~ps|U_ulLnnqC_mqNvxq`@"

        val decoded = PolylineDecoder.decode(encoded)

        assertEquals(3, decoded.size)
        assertLatLngClose(expectedLat = 38.5, expectedLng = -120.2, actual = decoded[0])
        assertLatLngClose(expectedLat = 40.7, expectedLng = -120.95, actual = decoded[1])
        assertLatLngClose(expectedLat = 43.252, expectedLng = -126.453, actual = decoded[2])
    }

    @Test
    fun `単一頂点もデコードできる`() {
        // (38.5, -120.2) のみ
        val encoded = "_p~iF~ps|U"

        val decoded = PolylineDecoder.decode(encoded)

        assertEquals(1, decoded.size)
        assertLatLngClose(expectedLat = 38.5, expectedLng = -120.2, actual = decoded[0])
    }

    private fun assertLatLngClose(
        expectedLat: Double,
        expectedLng: Double,
        actual: me.matsumo.onenavi.core.model.RoutePoint,
        tolerance: Double = 1e-5,
    ) {
        assertTrue(
            abs(actual.latitude - expectedLat) < tolerance,
            "latitude expected=$expectedLat actual=${actual.latitude}",
        )
        assertTrue(
            abs(actual.longitude - expectedLng) < tolerance,
            "longitude expected=$expectedLng actual=${actual.longitude}",
        )
    }
}
