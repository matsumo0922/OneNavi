package me.matsumo.onenavi.core.common.car

import java.net.URLDecoder

/**
 * geo URI をアシスタントナビ要求として解釈する。
 *
 * @param uri `geo:` scheme の URI 文字列
 * @param action intent action
 * @return 解釈できた要求。対象外または情報不足なら null
 */
fun parseAssistantNavUri(uri: String, action: String?): AssistantNavRequest? {
    if (!uri.startsWith(GEO_SCHEME_PREFIX, ignoreCase = true)) {
        return null
    }

    val geoPayload = uri.drop(GEO_SCHEME_PREFIX.length)
    val coordinate = parseGeoCoordinate(geoPayload.substringBefore(QUERY_SEPARATOR))
    val parameters = parseQueryParameters(geoPayload.substringAfter(QUERY_SEPARATOR, ""))
    val query = parameters[QUERY_PARAMETER_Q]?.takeIf { value -> value.isNotBlank() }

    return when (action) {
        ACTION_VIEW -> query?.let(AssistantNavRequest::Search)
        ACTION_CAR_NAVIGATE,
        ACTION_PHONE_NAVIGATE,
        -> parseNavigateRequest(
            intentValue = parameters[QUERY_PARAMETER_INTENT],
            query = query,
            coordinate = coordinate,
        )

        else -> null
    }
}

private fun parseNavigateRequest(
    intentValue: String?,
    query: String?,
    coordinate: AssistantNavCoordinate?,
): AssistantNavRequest? {
    if (query == null && coordinate == null) {
        return null
    }

    return when (intentValue?.lowercase()) {
        null,
        "",
        NAV_INTENT_NAVIGATION,
        -> AssistantNavRequest.Navigate(
            query = query,
            coordinate = coordinate,
        )

        NAV_INTENT_DIRECTIONS -> AssistantNavRequest.Preview(
            query = query,
            coordinate = coordinate,
        )

        NAV_INTENT_ADD_A_STOP -> AssistantNavRequest.AddStop(
            query = query,
            coordinate = coordinate,
        )

        else -> null
    }
}

private fun parseGeoCoordinate(value: String): AssistantNavCoordinate? {
    val parts = value.split(COORDINATE_SEPARATOR)
    if (parts.size < COORDINATE_PART_COUNT) {
        return null
    }

    val latitude = parts[COORDINATE_LATITUDE_INDEX].trim().toDoubleOrNull() ?: return null
    val longitude = parts[COORDINATE_LONGITUDE_INDEX].trim().toDoubleOrNull() ?: return null
    if (latitude == SENTINEL_COORDINATE && longitude == SENTINEL_COORDINATE) {
        return null
    }

    return AssistantNavCoordinate(
        latitude = latitude,
        longitude = longitude,
    )
}

private fun parseQueryParameters(query: String): Map<String, String> {
    if (query.isBlank()) {
        return emptyMap()
    }

    return query
        .split(QUERY_PARAMETER_SEPARATOR)
        .mapNotNull(::parseQueryParameter)
        .toMap()
}

private fun parseQueryParameter(parameter: String): Pair<String, String>? {
    if (parameter.isBlank()) {
        return null
    }

    val rawKey = parameter.substringBefore(QUERY_KEY_VALUE_SEPARATOR)
    val rawValue = parameter.substringAfter(QUERY_KEY_VALUE_SEPARATOR, "")
    return decodeQueryComponent(rawKey) to decodeQueryComponent(rawValue)
}

private fun decodeQueryComponent(value: String): String {
    return runCatching {
        URLDecoder.decode(value, Charsets.UTF_8.name())
    }.getOrDefault(value)
}

/** geo scheme の接頭辞。 */
private const val GEO_SCHEME_PREFIX = "geo:"

/** query string の開始文字。 */
private const val QUERY_SEPARATOR = "?"

/** query parameter の区切り文字。 */
private const val QUERY_PARAMETER_SEPARATOR = "&"

/** query parameter の key/value 区切り文字。 */
private const val QUERY_KEY_VALUE_SEPARATOR = "="

/** 検索語を表す query parameter 名。 */
private const val QUERY_PARAMETER_Q = "q"

/** intent 種別を表す query parameter 名。 */
private const val QUERY_PARAMETER_INTENT = "intent"

/** 座標の区切り文字。 */
private const val COORDINATE_SEPARATOR = ","

/** geo 座標を構成する要素数。 */
private const val COORDINATE_PART_COUNT = 2

/** 緯度要素の index。 */
private const val COORDINATE_LATITUDE_INDEX = 0

/** 経度要素の index。 */
private const val COORDINATE_LONGITUDE_INDEX = 1

/** 座標なしを表す sentinel 値。 */
private const val SENTINEL_COORDINATE = 0.0

/** 車載向けナビ intent action。 */
private const val ACTION_CAR_NAVIGATE = "androidx.car.app.action.NAVIGATE"

/** スマホ向けナビ intent action。 */
private const val ACTION_PHONE_NAVIGATE = "android.intent.action.NAVIGATE"

/** 場所検索 intent action。 */
private const val ACTION_VIEW = "android.intent.action.VIEW"

/** 目的地設定と案内開始を表す intent 値。 */
private const val NAV_INTENT_NAVIGATION = "navigation"

/** ルートプレビューを表す intent 値。 */
private const val NAV_INTENT_DIRECTIONS = "directions"

/** 経由地追加を表す intent 値。 */
private const val NAV_INTENT_ADD_A_STOP = "add_a_stop"
