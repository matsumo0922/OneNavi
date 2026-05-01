package me.matsumo.onenavi.core.navigation.newguidance

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import me.matsumo.onenavi.core.navigation.newguidance.dto.ComputeRoutesRequest
import me.matsumo.onenavi.core.navigation.newguidance.dto.ComputeRoutesResponse
import me.matsumo.onenavi.core.navigation.newguidance.dto.LatLngDto
import me.matsumo.onenavi.core.navigation.newguidance.dto.LocationDto
import me.matsumo.onenavi.core.navigation.newguidance.dto.WaypointDto
import me.matsumo.onenavi.core.navigation.newguidance.model.RoutesApiWaypoint
import java.security.MessageDigest

/**
 * [RoutesApiClient] の Ktor 実装。
 *
 * Routes API v2 の `directions/v2:computeRoutes` を POST し、`X-Goog-FieldMask`
 * で必要な項目のみ要求する。intermediate には [useVia] が true のとき `via:true` を
 * 立てて pass-through 扱い (spec/23 §7.3) にする。origin/destination には付けない。
 *
 * Android アプリ制限付きの API key を通すため、`X-Android-Package` と `X-Android-Cert`
 * ヘッダを毎リクエストに付与する (legacy `GoogleRoutesDataSource` と同様)。
 *
 * @param context Android パッケージ名と署名証明書の SHA-1 を取り出すために使う
 * @param httpClient JSON ContentNegotiation 設定済みの HttpClient
 * @param apiKey [me.matsumo.onenavi.core.model.AppConfig.googleApiKey] を渡す
 */
internal class DefaultRoutesApiClient(
    private val context: Context,
    private val httpClient: HttpClient,
    private val apiKey: String,
) : RoutesApiClient {

    override suspend fun computeRoute(
        chunk: List<RoutesApiWaypoint>,
        useVia: Boolean,
    ): Result<RoutesApiResponse> = runCatching {
        require(chunk.size >= 2) {
            "chunk must contain at least origin and destination, got ${chunk.size}"
        }

        val origin = chunk.first().toWaypointDto(via = null)
        val destination = chunk.last().toWaypointDto(via = null)
        val intermediates = chunk
            .subList(fromIndex = 1, toIndex = chunk.size - 1)
            .map { it.toWaypointDto(via = if (useVia) true else null) }

        val request = ComputeRoutesRequest(
            origin = origin,
            destination = destination,
            intermediates = intermediates,
        )

        val response: ComputeRoutesResponse = httpClient.post(ENDPOINT) {
            header(API_KEY_HEADER, apiKey)
            header(FIELD_MASK_HEADER, FIELD_MASK)
            header(ANDROID_PACKAGE_HEADER, context.packageName)
            header(ANDROID_CERT_HEADER, context.signingCertificateSha1())
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

        val route = response.routes.firstOrNull() ?: error("Routes API returned no routes")

        RoutesApiResponse(
            polyline = PolylineDecoder.decode(route.polyline.encodedPolyline),
            routeToken = route.routeToken,
            distanceMeters = route.distanceMeters,
            durationSeconds = parseDurationSeconds(route.duration),
        )
    }

    private fun RoutesApiWaypoint.toWaypointDto(via: Boolean?): WaypointDto = WaypointDto(
        location = LocationDto(
            latLng = LatLngDto(
                latitude = point.latitude,
                longitude = point.longitude,
            ),
            heading = heading,
        ),
        via = via,
    )

    /**
     * Routes API は duration を `"1234s"` のような文字列で返す。末尾 `s` を外して秒数に
     * 変換する。負値や parse 不能は 0 にする (案内続行に不要なため)。
     */
    private fun parseDurationSeconds(duration: String): Long {
        val numeric = duration.removeSuffix("s").trim()
        return numeric.toDoubleOrNull()?.toLong()?.coerceAtLeast(0L) ?: 0L
    }

    companion object {
        private const val ENDPOINT = "https://routes.googleapis.com/directions/v2:computeRoutes"
        private const val API_KEY_HEADER = "X-Goog-Api-Key"
        private const val FIELD_MASK_HEADER = "X-Goog-FieldMask"
        private const val ANDROID_PACKAGE_HEADER = "X-Android-Package"
        private const val ANDROID_CERT_HEADER = "X-Android-Cert"
        private const val FIELD_MASK =
            "routes.polyline.encodedPolyline,routes.routeToken,routes.distanceMeters,routes.duration"
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
    val signature = packageInfo.signingInfo?.apkContentsSigners?.firstOrNull() ?: error("No signing certificate found for package $packageName")
    val digest = MessageDigest.getInstance("SHA-1").digest(signature.toByteArray())

    return digest.joinToString(separator = "") { byte -> "%02X".format(byte) }
}
