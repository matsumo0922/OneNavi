package me.matsumo.onenavi.core.datasource

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.matsumo.onenavi.core.model.RouteItem
import me.matsumo.onenavi.core.model.RoutePoint

class MapboxRouteDataSource(
    private val httpClient: HttpClient,
    private val accessToken: String,
) : RouteDataSource {

    override suspend fun searchRoutes(
        originLatitude: Double,
        originLongitude: Double,
        destinationLatitude: Double,
        destinationLongitude: Double,
    ): Result<List<RouteItem>> = runCatching {
        val coordinates = "$originLongitude,$originLatitude;$destinationLongitude,$destinationLatitude"
        val response = httpClient.get("$BASE_URL/$coordinates") {
            parameter("access_token", accessToken)
            parameter("alternatives", "true")
            parameter("overview", "full")
            parameter("geometries", "geojson")
        }

        val body = response.body<DirectionsResponse>()

        requireNotNull(body.routes) { "No routes found" }

        body.routes.map { route ->
            RouteItem(
                durationSeconds = route.duration,
                distanceMeters = route.distance,
                geometry = route.geometry.coordinates.map { coordinate ->
                    RoutePoint(
                        latitude = coordinate[1],
                        longitude = coordinate[0],
                    )
                },
                summary = route.legs.firstOrNull()?.summary.orEmpty(),
            )
        }
    }

    companion object {
        private const val BASE_URL = "https://api.mapbox.com/directions/v5/mapbox/driving"
    }
}

@Serializable
internal data class DirectionsResponse(
    @SerialName("routes")
    val routes: List<DirectionsRoute>? = null,
    @SerialName("code")
    val code: String? = null,
)

@Serializable
internal data class DirectionsRoute(
    @SerialName("duration")
    val duration: Double,
    @SerialName("distance")
    val distance: Double,
    @SerialName("geometry")
    val geometry: RouteGeometry,
    @SerialName("legs")
    val legs: List<RouteLeg>,
)

@Serializable
internal data class RouteGeometry(
    @SerialName("type")
    val type: String,
    @SerialName("coordinates")
    val coordinates: List<List<Double>>,
)

@Serializable
internal data class RouteLeg(
    @SerialName("summary")
    val summary: String = "",
    @SerialName("duration")
    val duration: Double = 0.0,
    @SerialName("distance")
    val distance: Double = 0.0,
)
