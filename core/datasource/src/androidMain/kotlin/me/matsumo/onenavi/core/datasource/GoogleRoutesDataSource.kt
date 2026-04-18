package me.matsumo.onenavi.core.datasource

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.collections.immutable.toImmutableList
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.matsumo.onenavi.core.model.CongestionSegment
import me.matsumo.onenavi.core.model.CongestionSeverity
import me.matsumo.onenavi.core.model.GoogleRoute
import me.matsumo.onenavi.core.model.RouteItem
import me.matsumo.onenavi.core.model.RoutePoint
import me.matsumo.onenavi.core.model.RouteResult
import me.matsumo.onenavi.core.model.RouteStepInfo
import java.security.MessageDigest

/**
 * Google Routes API を使用したルート検索データソース。
 *
 * Navigation SDK には、Routes API の `routeToken` を渡して同一ルートで案内を開始する。
 */
class GoogleRoutesDataSource(
    private val context: Context,
    private val httpClient: HttpClient,
    private val googleApiKey: String,
) : RouteDataSource {

    override suspend fun searchRoutes(
        originLatitude: Double,
        originLongitude: Double,
        destinationLatitude: Double,
        destinationLongitude: Double,
        intermediateWaypoints: List<Pair<Double, Double>>,
    ): Result<List<RouteResult>> = runCatching {
        val origin = RoutePoint(originLatitude, originLongitude)
        val destination = RoutePoint(destinationLatitude, destinationLongitude)
        val intermediates = intermediateWaypoints.map { (lat, lng) -> RoutePoint(lat, lng) }

        val defaultRoutes = requestRoutes(
            origin = origin,
            destination = destination,
            intermediates = intermediates,
            avoidTolls = false,
            computeAlternativeRoutes = true,
        )

        val hasTollFreeRoute = defaultRoutes.any { !it.item.hasTolls }
        val routes = if (hasTollFreeRoute) {
            defaultRoutes.sortedBy { it.item.hasTolls }
        } else {
            val tollFreeRoutes = runCatching {
                requestRoutes(
                    origin = origin,
                    destination = destination,
                    intermediates = intermediates,
                    avoidTolls = true,
                    computeAlternativeRoutes = false,
                )
            }.getOrDefault(emptyList())

            tollFreeRoutes + defaultRoutes
        }

        routes.distinctBy { (it.platformRoute as GoogleRoute).id }
    }

    private suspend fun requestRoutes(
        origin: RoutePoint,
        destination: RoutePoint,
        intermediates: List<RoutePoint>,
        avoidTolls: Boolean,
        computeAlternativeRoutes: Boolean,
    ): List<RouteResult> {
        val request = ComputeRoutesRequest(
            origin = RouteWaypoint(location = RouteLocation(LatLng(origin.latitude, origin.longitude))),
            destination = RouteWaypoint(location = RouteLocation(LatLng(destination.latitude, destination.longitude))),
            intermediates = intermediates.map { point ->
                RouteWaypoint(location = RouteLocation(LatLng(point.latitude, point.longitude)))
            }.takeIf { it.isNotEmpty() },
            travelMode = "DRIVE",
            routingPreference = "TRAFFIC_AWARE",
            computeAlternativeRoutes = computeAlternativeRoutes,
            // Google Routes API は routeModifiers を付けると routeToken を返さない仕様があるため、
            // 有料道路回避が必要な時だけフィールドを含める。
            routeModifiers = if (avoidTolls) RouteModifiers(avoidTolls = true) else null,
            languageCode = "ja-JP",
            units = "METRIC",
            polylineEncoding = "ENCODED_POLYLINE",
            polylineQuality = "HIGH_QUALITY",
            extraComputations = listOf("TOLLS", "TRAFFIC_ON_POLYLINE"),
        )

        val response = httpClient.post(ROUTES_ENDPOINT) {
            contentType(ContentType.Application.Json)
            header("X-Goog-Api-Key", googleApiKey)
            header("X-Goog-FieldMask", ROUTES_FIELD_MASK)
            header("X-Android-Package", context.packageName)
            header("X-Android-Cert", context.signingCertificateSha1())
            setBody(request)
        }
        val responseBody = response.bodyAsText()
        check(response.status.isSuccess()) {
            "Routes API request failed. status=${response.status.value}, bodyPreview=${responseBody.sanitizedLogSnippet()}"
        }
        val decodedResponse = json.decodeFromString<ComputeRoutesResponse>(responseBody)

        return decodedResponse.routes.orEmpty().mapIndexed { index, route ->
            val geometry = route.polyline?.encodedPolyline
                ?.decodePolyline()
                .orEmpty()
                .toImmutableList()
            val steps = route.legs.orEmpty()
                .flatMap { it.steps.orEmpty() }
                .toRouteSteps()
                .toImmutableList()
            val hasTolls = route.travelAdvisory?.tollInfo != null ||
                route.routeLabels.orEmpty().any { it.contains("TOLL", ignoreCase = true) }
            val distanceMeters = route.distanceMeters?.toDouble() ?: 0.0
            val durationSeconds = route.duration.parseDurationSeconds()
            val congestionSegments = route.travelAdvisory?.speedReadingIntervals
                ?.toCongestionSegments(geometry.size)
                .orEmpty()
                .toImmutableList()

            val googleRoute = GoogleRoute(
                id = buildRouteId(index, route.routeToken, geometry),
                routeToken = route.routeToken,
                origin = origin,
                destination = destination,
                intermediateWaypoints = intermediates.toImmutableList(),
                geometry = geometry,
                distanceMeters = distanceMeters,
                durationSeconds = durationSeconds,
                steps = steps,
            )

            RouteResult(
                item = RouteItem(
                    durationSeconds = durationSeconds,
                    distanceMeters = distanceMeters,
                    geometry = geometry,
                    viaRoadNames = emptyList<String>().toImmutableList(),
                    hasTolls = hasTolls,
                    congestionSegments = congestionSegments,
                ),
                platformRoute = googleRoute,
            )
        }
    }

    private fun List<RouteStep>.toRouteSteps(): List<RouteStepInfo> {
        var cumulativeDistance = 0.0

        return mapNotNull { step ->
            val instruction = step.navigationInstruction?.instructions.orEmpty()
            val maneuver = step.navigationInstruction?.maneuver.orEmpty()
            val distance = step.distanceMeters?.toDouble() ?: 0.0
            val parsedManeuver = parseGoogleManeuver(maneuver)

            val maneuverLocation = step.startLocation?.latLng?.let { latLng ->
                RoutePoint(latitude = latLng.latitude, longitude = latLng.longitude)
            }

            val routeStepInfo = RouteStepInfo(
                maneuverType = parsedManeuver.type,
                modifier = parsedManeuver.modifier,
                distanceFromPreviousMeters = distance,
                cumulativeDistanceMeters = cumulativeDistance,
                maneuverLocation = maneuverLocation,
                instruction = instruction,
                roadName = "",
                roadRef = null,
                highwayInfo = null,
            )
            cumulativeDistance += distance
            routeStepInfo
        }
    }

    private fun buildRouteId(
        index: Int,
        routeToken: String?,
        geometry: List<RoutePoint>,
    ): String {
        return routeToken
            ?: "route-$index-${geometry.firstOrNull()?.latitude}-${geometry.lastOrNull()?.longitude}"
    }

    private fun String?.parseDurationSeconds(): Double {
        return this
            ?.removeSuffix("s")
            ?.toDoubleOrNull()
            ?: 0.0
    }

    companion object {
        private const val ROUTES_ENDPOINT = "https://routes.googleapis.com/directions/v2:computeRoutes"
        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        private const val ROUTES_FIELD_MASK =
            "routes.routeToken," +
                "routes.routeLabels," +
                "routes.distanceMeters," +
                "routes.duration," +
                "routes.polyline.encodedPolyline," +
                "routes.travelAdvisory.tollInfo," +
                "routes.travelAdvisory.speedReadingIntervals," +
                "routes.legs.steps.distanceMeters," +
                "routes.legs.steps.navigationInstruction," +
                "routes.legs.steps.startLocation"
    }
}

private fun Context.signingCertificateSha1(): String {
    val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.getPackageInfo(
            packageName,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
        )
    } else {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
    }
    val signature = packageInfo.signingInfo?.apkContentsSigners?.firstOrNull()
        ?: error("No signing certificate found for package $packageName")
    val digest = MessageDigest.getInstance("SHA-1").digest(signature.toByteArray())
    return digest.joinToString(separator = "") { byte -> "%02X".format(byte) }
}

private fun String.sanitizedLogSnippet(maxLength: Int = 200): String {
    return replace("\n", " ").replace("\r", " ").take(maxLength)
}

private fun List<SpeedReadingInterval>.toCongestionSegments(geometrySize: Int): List<CongestionSegment> {
    if (geometrySize <= 0) return emptyList()
    val lastIndex = geometrySize - 1
    return mapNotNull { interval ->
        val startIndex = interval.startPolylinePointIndex ?: 0
        val endIndex = interval.endPolylinePointIndex ?: return@mapNotNull null
        val clampedStart = startIndex.coerceIn(0, lastIndex)
        val clampedEnd = endIndex.coerceIn(0, lastIndex)
        if (clampedEnd <= clampedStart) return@mapNotNull null
        CongestionSegment(
            startPolylinePointIndex = clampedStart,
            endPolylinePointIndex = clampedEnd,
            severity = interval.speed.toCongestionSeverity(),
        )
    }
}

private fun String?.toCongestionSeverity(): CongestionSeverity {
    return when (this) {
        "NORMAL" -> CongestionSeverity.NORMAL
        "SLOW" -> CongestionSeverity.SLOW
        "TRAFFIC_JAM" -> CongestionSeverity.TRAFFIC_JAM
        else -> CongestionSeverity.UNKNOWN
    }
}

private fun String.decodePolyline(): List<RoutePoint> {
    val points = mutableListOf<RoutePoint>()
    var index = 0
    var latitude = 0
    var longitude = 0

    while (index < length) {
        var result = 0
        var shift = 0
        var byte: Int

        do {
            byte = this[index++].code - 63
            result = result or ((byte and 0x1f) shl shift)
            shift += 5
        } while (byte >= 0x20)

        latitude += if ((result and 1) != 0) (result shr 1).inv() else result shr 1

        result = 0
        shift = 0

        do {
            byte = this[index++].code - 63
            result = result or ((byte and 0x1f) shl shift)
            shift += 5
        } while (byte >= 0x20)

        longitude += if ((result and 1) != 0) (result shr 1).inv() else result shr 1

        points += RoutePoint(
            latitude = latitude / 1E5,
            longitude = longitude / 1E5,
        )
    }

    return points
}

@Serializable
private data class ComputeRoutesRequest(
    val origin: RouteWaypoint,
    val destination: RouteWaypoint,
    val intermediates: List<RouteWaypoint>? = null,
    val travelMode: String,
    val routingPreference: String,
    val computeAlternativeRoutes: Boolean,
    val routeModifiers: RouteModifiers? = null,
    val languageCode: String,
    val units: String,
    val polylineEncoding: String,
    val polylineQuality: String,
    val extraComputations: List<String>,
)

@Serializable
private data class RouteWaypoint(
    val location: RouteLocation,
)

@Serializable
private data class RouteLocation(
    val latLng: LatLng,
)

@Serializable
private data class LatLng(
    val latitude: Double,
    val longitude: Double,
)

@Serializable
private data class RouteModifiers(
    val avoidTolls: Boolean,
)

@Serializable
private data class ComputeRoutesResponse(
    val routes: List<Route>? = null,
)

@Serializable
private data class Route(
    val routeToken: String? = null,
    val routeLabels: List<String>? = null,
    val distanceMeters: Int? = null,
    val duration: String? = null,
    val polyline: RoutePolyline? = null,
    val legs: List<RouteLeg>? = null,
    val travelAdvisory: RouteTravelAdvisory? = null,
)

@Serializable
private data class RoutePolyline(
    val encodedPolyline: String? = null,
)

@Serializable
private data class RouteLeg(
    val steps: List<RouteStep>? = null,
)

@Serializable
private data class RouteStep(
    val distanceMeters: Int? = null,
    val navigationInstruction: NavigationInstruction? = null,
    val startLocation: RouteStepLocation? = null,
)

@Serializable
private data class RouteStepLocation(
    val latLng: LatLng? = null,
)

@Serializable
private data class NavigationInstruction(
    val maneuver: String? = null,
    val instructions: String? = null,
)

@Serializable
private data class RouteTravelAdvisory(
    val tollInfo: TollInfo? = null,
    val speedReadingIntervals: List<SpeedReadingInterval>? = null,
)

@Serializable
private data class SpeedReadingInterval(
    val startPolylinePointIndex: Int? = null,
    val endPolylinePointIndex: Int? = null,
    val speed: String? = null,
)

@Serializable
private data class TollInfo(
    @SerialName("estimatedPrice")
    val estimatedPrices: List<TollPrice>? = null,
)

@Serializable
private data class TollPrice(
    val currencyCode: String? = null,
    val units: String? = null,
    val nanos: Int? = null,
)
