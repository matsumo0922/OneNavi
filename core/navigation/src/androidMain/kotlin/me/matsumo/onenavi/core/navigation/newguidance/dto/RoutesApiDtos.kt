package me.matsumo.onenavi.core.navigation.newguidance.dto

import kotlinx.serialization.Serializable

/**
 * Routes API v2 `directions/v2:computeRoutes` のリクエスト/レスポンス DTO。
 *
 * 公開しないので [internal]。kotlinx.serialization で JSON ボディを構築する。
 * `via` / `heading` のように省略可能なフィールドは nullable にし、
 * `Json { explicitNulls = false }` の設定下で null フィールドを省略する。
 */
@Serializable
internal data class ComputeRoutesRequest(
    val origin: WaypointDto,
    val destination: WaypointDto,
    val intermediates: List<WaypointDto> = emptyList(),
    val travelMode: String = "DRIVE",
    /** route_token を取るには TRAFFIC_AWARE 以上が必須 (TRAFFIC_UNAWARE では空文字が返る)。 */
    val routingPreference: String = "TRAFFIC_AWARE",
    val polylineQuality: String = "HIGH_QUALITY",
)

@Serializable
internal data class WaypointDto(
    val location: LocationDto,
    val via: Boolean? = null,
)

@Serializable
internal data class LocationDto(
    val latLng: LatLngDto,
    val heading: Int? = null,
)

@Serializable
internal data class LatLngDto(
    val latitude: Double,
    val longitude: Double,
)

@Serializable
internal data class ComputeRoutesResponse(
    val routes: List<RouteDto> = emptyList(),
)

@Serializable
internal data class RouteDto(
    val polyline: PolylineDto,
    val routeToken: String,
    val distanceMeters: Int,
    val duration: String,
)

@Serializable
internal data class PolylineDto(
    val encodedPolyline: String,
)
