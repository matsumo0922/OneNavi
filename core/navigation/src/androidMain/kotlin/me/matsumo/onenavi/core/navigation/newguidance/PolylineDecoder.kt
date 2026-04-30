package me.matsumo.onenavi.core.navigation.newguidance

import me.matsumo.onenavi.core.model.RoutePoint

/**
 * Google encoded polyline algorithm のデコーダ。
 *
 * Routes API v2 は `polyline.encodedPolyline` 文字列を返すので、これを [RoutePoint] 列に戻す。
 * decimal precision 5 (`1e-5`) で encode されているため、5 桁スケールで除算する。
 *
 * 参考: https://developers.google.com/maps/documentation/utilities/polylinealgorithm
 *
 * dev tool 側の TypeScript 実装 (`dev-tools/route-compare/src/polyline.ts`) と
 * バイト互換になるように移植している。
 */
internal object PolylineDecoder {

    private const val ASCII_OFFSET = 63
    private const val FIVE_BIT_MASK = 0x1f
    private const val CONTINUATION_BIT = 0x20
    private const val PRECISION_SCALE = 1e5

    /** [encoded] が空なら空リスト、それ以外は decode 後の [RoutePoint] リストを返す。 */
    fun decode(encoded: String): List<RoutePoint> {
        if (encoded.isEmpty()) return emptyList()

        val points = mutableListOf<RoutePoint>()
        var cursor = 0
        var latE5 = 0
        var lngE5 = 0

        while (cursor < encoded.length) {
            val latDelta = decodeSignedVarInt(encoded, cursor)
            latE5 += latDelta.value
            cursor = latDelta.next

            val lngDelta = decodeSignedVarInt(encoded, cursor)
            lngE5 += lngDelta.value
            cursor = lngDelta.next

            points += RoutePoint(
                latitude = latE5 / PRECISION_SCALE,
                longitude = lngE5 / PRECISION_SCALE,
            )
        }
        return points
    }

    private fun decodeSignedVarInt(encoded: String, start: Int): SignedVarInt {
        var result = 0
        var shift = 0
        var cursor = start
        var byte: Int

        do {
            byte = encoded[cursor].code - ASCII_OFFSET
            cursor += 1
            result = result or ((byte and FIVE_BIT_MASK) shl shift)
            shift += 5
        } while (byte >= CONTINUATION_BIT)

        // ZigZag decode: LSB が立っていれば負数 (補数表現)
        val value = if (result and 1 != 0) (result shr 1).inv() else result shr 1
        return SignedVarInt(value = value, next = cursor)
    }

    private data class SignedVarInt(
        val value: Int,
        val next: Int,
    )
}
