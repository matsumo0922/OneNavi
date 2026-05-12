package me.matsumo.onenavi.core.common

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow

/**
 * Open Location Code（Plus Code）のエンコード・デコードを行うユーティリティ。
 *
 * Google の公式 Java 実装（Apache 2.0）を Kotlin Multiplatform 向けに移植したもの。
 * 緯度経度から Plus Code 文字列を生成し、またその逆変換を行う。
 *
 * @see <a href="https://github.com/google/open-location-code">google/open-location-code</a>
 */
object OpenLocationCode {

    private const val CODE_ALPHABET = "23456789CFGHJMPQRVWX"
    private const val ENCODING_BASE = 20
    private const val SEPARATOR = '+'
    private const val SEPARATOR_POSITION = 8
    private const val PADDING_CHARACTER = '0'
    private const val PAIR_CODE_LENGTH = 10
    private const val MAX_DIGIT_COUNT = 15
    private const val GRID_COLUMNS = 4
    private const val GRID_ROWS = 5

    private const val LAT_INTEGER_MULTIPLIER = 8000L * 3125
    private const val LNG_INTEGER_MULTIPLIER = 8000L * 1024
    private const val LAT_MSP_VALUE = LAT_INTEGER_MULTIPLIER * ENCODING_BASE * ENCODING_BASE
    private const val LNG_MSP_VALUE = LNG_INTEGER_MULTIPLIER * ENCODING_BASE * ENCODING_BASE

    private const val LATITUDE_MAX = 90.0
    private const val LONGITUDE_MAX = 180.0

    /**
     * Plus Code のデコード結果を表すデータクラス。
     *
     * @param southLatitude 南端の緯度
     * @param westLongitude 西端の経度
     * @param northLatitude 北端の緯度
     * @param eastLongitude 東端の経度
     * @param codeLength コード長
     */
    data class CodeArea(
        val southLatitude: Double,
        val westLongitude: Double,
        val northLatitude: Double,
        val eastLongitude: Double,
        val codeLength: Int,
    ) {
        /** 中心の緯度 */
        val centerLatitude: Double
            get() = (southLatitude + northLatitude) / 2.0

        /** 中心の経度 */
        val centerLongitude: Double
            get() = (westLongitude + eastLongitude) / 2.0
    }

    /**
     * 緯度経度を Plus Code にエンコードする。
     *
     * @param latitude 緯度（-90.0 ～ 90.0）
     * @param longitude 経度（-180.0 ～ 180.0）
     * @param codeLength コード長（2, 4, 6, 8, 10 ～ 15）。デフォルトは 10
     * @return Plus Code 文字列
     */
    fun encode(
        latitude: Double,
        longitude: Double,
        codeLength: Int = PAIR_CODE_LENGTH,
    ): String {
        require(codeLength in 2..MAX_DIGIT_COUNT && (codeLength >= PAIR_CODE_LENGTH || codeLength % 2 == 0)) {
            "Invalid code length: $codeLength"
        }

        val clampedLat = clipLatitude(latitude)
        val clampedLng = normalizeLongitude(longitude)

        val adjustedLat = if (clampedLat == LATITUDE_MAX) clampedLat - computeLatPrecision(codeLength) else clampedLat
        val latVal = ((adjustedLat + LATITUDE_MAX) * LAT_INTEGER_MULTIPLIER * 1e6).toLong() / 1_000_000L
        val lngVal = ((clampedLng + LONGITUDE_MAX) * LNG_INTEGER_MULTIPLIER * 1e6).toLong() / 1_000_000L

        return buildString(MAX_DIGIT_COUNT + 1) {
            encodeIntegers(latVal, lngVal, codeLength, this)
        }
    }

    /**
     * Plus Code をデコードして [CodeArea] を返す。
     *
     * @param code Plus Code 文字列
     * @return デコードされた [CodeArea]
     */
    fun decode(code: String): CodeArea {
        require(isFullCode(code)) { "Not a valid full Plus Code: $code" }

        val stripped = code.replace(SEPARATOR.toString(), "")
            .replace(PADDING_CHARACTER.toString(), "")
            .uppercase()
        val length = min(stripped.length, MAX_DIGIT_COUNT)

        var latValue = 0L
        var lngValue = 0L
        var latPlaceValue = LAT_MSP_VALUE
        var lngPlaceValue = LNG_MSP_VALUE
        var index = 0

        while (index < length) {
            if (index < PAIR_CODE_LENGTH) {
                latPlaceValue /= ENCODING_BASE
                lngPlaceValue /= ENCODING_BASE
                latValue += CODE_ALPHABET.indexOf(stripped[index]) * latPlaceValue
                lngValue += CODE_ALPHABET.indexOf(stripped[index + 1]) * lngPlaceValue
                index += 2
            } else {
                latPlaceValue /= GRID_ROWS
                lngPlaceValue /= GRID_COLUMNS
                val digit = CODE_ALPHABET.indexOf(stripped[index])
                latValue += (digit / GRID_COLUMNS) * latPlaceValue
                lngValue += (digit % GRID_COLUMNS) * lngPlaceValue
                index++
            }
        }

        return CodeArea(
            southLatitude = latValue.toDouble() / LAT_INTEGER_MULTIPLIER - LATITUDE_MAX,
            westLongitude = lngValue.toDouble() / LNG_INTEGER_MULTIPLIER - LONGITUDE_MAX,
            northLatitude = (latValue + latPlaceValue).toDouble() / LAT_INTEGER_MULTIPLIER - LATITUDE_MAX,
            eastLongitude = (lngValue + lngPlaceValue).toDouble() / LNG_INTEGER_MULTIPLIER - LONGITUDE_MAX,
            codeLength = length,
        )
    }

    /**
     * Plus Code 文字列が有効かどうかを検証する。
     *
     * @param code 検証対象の文字列
     * @return 有効なら true
     */
    fun isValidCode(code: String): Boolean {
        if (code.isEmpty()) return false

        val separatorIndex = code.indexOf(SEPARATOR)
        if (separatorIndex == -1 || separatorIndex != code.lastIndexOf(SEPARATOR)) return false
        if (separatorIndex % 2 != 0 || separatorIndex > SEPARATOR_POSITION) return false

        val paddingStart = code.indexOf(PADDING_CHARACTER)
        if (paddingStart != -1) {
            if (separatorIndex < SEPARATOR_POSITION) return false
            if (paddingStart == 0 || paddingStart % 2 != 0) return false
            if (code.length > separatorIndex + 1) return false
            for (index in paddingStart until separatorIndex) {
                if (code[index] != PADDING_CHARACTER) return false
            }
        }

        return code.all { char ->
            char == SEPARATOR || char == PADDING_CHARACTER || CODE_ALPHABET.contains(char.uppercaseChar())
        }
    }

    /**
     * 完全な Plus Code（短縮されていない）かどうかを判定する。
     *
     * @param code 判定対象の Plus Code 文字列
     * @return 完全な Plus Code なら true
     */
    fun isFullCode(code: String): Boolean {
        if (!isValidCode(code)) return false
        val separatorIndex = code.indexOf(SEPARATOR)
        if (separatorIndex != SEPARATOR_POSITION) return false
        return true
    }

    /**
     * 短縮された Plus Code かどうかを判定する。
     *
     * @param code 判定対象の Plus Code 文字列
     * @return 短縮コードなら true
     */
    fun isShortCode(code: String): Boolean {
        if (!isValidCode(code)) return false
        return code.indexOf(SEPARATOR) in 0 until SEPARATOR_POSITION
    }

    /**
     * 完全な Plus Code を参照地点を使って短縮する。
     *
     * @param code 完全な Plus Code
     * @param latitude 参照地点の緯度
     * @param longitude 参照地点の経度
     * @return 短縮された Plus Code。短縮できない場合は元のコードを返す
     */
    fun shorten(
        code: String,
        latitude: Double,
        longitude: Double,
    ): String {
        require(isFullCode(code)) { "Not a valid full Plus Code: $code" }

        val codeArea = decode(code)
        if (codeArea.codeLength < 6) return code

        val range = maxOf(
            abs(latitude - codeArea.centerLatitude),
            abs(longitude - codeArea.centerLongitude),
        )

        for (index in 4 downTo 1) {
            val removal = index * 2
            val areaEdge = computeLatPrecision(removal) * 0.3
            if (range < areaEdge) {
                return code.substring(removal)
            }
        }

        return code
    }

    /**
     * 短縮された Plus Code を参照地点を使って完全なコードに復元する。
     *
     * @param shortCode 短縮された Plus Code
     * @param latitude 参照地点の緯度
     * @param longitude 参照地点の経度
     * @return 完全な Plus Code
     */
    fun recover(
        shortCode: String,
        latitude: Double,
        longitude: Double,
    ): String {
        require(isShortCode(shortCode)) { "Not a valid short Plus Code: $shortCode" }

        val referenceCode = encode(latitude, longitude)
        val paddingLength = SEPARATOR_POSITION - shortCode.indexOf(SEPARATOR)
        val prefix = referenceCode.substring(0, paddingLength)
        val recovered = prefix + shortCode

        val area = decode(recovered)
        var recoveredLat = area.centerLatitude
        var recoveredLng = area.centerLongitude

        val halfResolution = computeLatPrecision(paddingLength) / 2.0

        if (recoveredLat - latitude > halfResolution && recoveredLat - halfResolution * 2 >= -LATITUDE_MAX) {
            recoveredLat -= halfResolution * 2
        } else if (recoveredLat - latitude < -halfResolution && recoveredLat + halfResolution * 2 < LATITUDE_MAX) {
            recoveredLat += halfResolution * 2
        }
        if (recoveredLng - longitude > halfResolution) {
            recoveredLng -= halfResolution * 2
        } else if (recoveredLng - longitude < -halfResolution) {
            recoveredLng += halfResolution * 2
        }

        return encode(recoveredLat, recoveredLng, area.codeLength)
    }

    private fun encodeIntegers(
        latValue: Long,
        lngValue: Long,
        codeLength: Int,
        builder: StringBuilder,
    ) {
        var latVal = latValue
        var lngVal = lngValue
        var latPlaceValue = LAT_MSP_VALUE
        var lngPlaceValue = LNG_MSP_VALUE
        var digitCount = 0

        while (digitCount < codeLength) {
            if (digitCount < PAIR_CODE_LENGTH) {
                latPlaceValue /= ENCODING_BASE
                lngPlaceValue /= ENCODING_BASE
                builder.append(CODE_ALPHABET[(latVal / latPlaceValue).toInt()])
                builder.append(CODE_ALPHABET[(lngVal / lngPlaceValue).toInt()])
                latVal %= latPlaceValue
                lngVal %= lngPlaceValue
                digitCount += 2
            } else {
                latPlaceValue /= GRID_ROWS
                lngPlaceValue /= GRID_COLUMNS
                val row = (latVal / latPlaceValue).toInt()
                val col = (lngVal / lngPlaceValue).toInt()
                builder.append(CODE_ALPHABET[row * GRID_COLUMNS + col])
                latVal %= latPlaceValue
                lngVal %= lngPlaceValue
                digitCount++
            }
            if (digitCount == SEPARATOR_POSITION) {
                builder.append(SEPARATOR)
            }
        }

        if (digitCount < SEPARATOR_POSITION) {
            repeat(SEPARATOR_POSITION - digitCount) {
                builder.append(PADDING_CHARACTER)
            }
            builder.append(SEPARATOR)
        }
    }

    private fun clipLatitude(latitude: Double): Double =
        latitude.coerceIn(-LATITUDE_MAX, LATITUDE_MAX)

    private fun normalizeLongitude(longitude: Double): Double {
        var lng = longitude
        while (lng < -LONGITUDE_MAX) lng += 360.0
        while (lng >= LONGITUDE_MAX) lng -= 360.0
        return lng
    }

    private fun computeLatPrecision(codeLength: Int): Double {
        if (codeLength <= PAIR_CODE_LENGTH) {
            return ENCODING_BASE.toDouble().pow(((PAIR_CODE_LENGTH - codeLength) / 2).toDouble())
        }
        return (ENCODING_BASE.toDouble().pow(3.0)) / (GRID_ROWS.toDouble().pow((codeLength - PAIR_CODE_LENGTH).toDouble()))
    }
}
