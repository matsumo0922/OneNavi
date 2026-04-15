package me.matsumo.onenavi.debug

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * デバッグ用 Fake GPS サーバー。
 *
 * 2 つの経路で mock 位置を同時注入する:
 * 1. FusedLocationProviderClient.setMockLocation() — Google Maps / Navigation SDK 向け
 * 2. LocationManager.setTestProviderLocation() — GPS/Network プロバイダー直接利用アプリ向け
 *
 * 永続ループで 100ms ごとに最新位置を再注入し、実 GPS との競合に勝つ。
 */
class FakeGpsServer(
    private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fusedClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)
    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private var isMockActive = false

    @Volatile
    private var currentLocation: LocationData? = null

    private val server = embeddedServer(
        factory = CIO,
        port = PORT,
    ) {
        install(CORS) {
            anyHost()
            allowHeader(HttpHeaders.ContentType)
            allowMethod(HttpMethod.Post)
            allowMethod(HttpMethod.Get)
        }

        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                },
            )
        }

        routing {
            post("/location") {
                runCatching {
                    val data = call.receive<LocationData>()
                    enableMockMode()
                    currentLocation = data
                    call.respond(SuccessResponse(success = true))
                }.onFailure { error ->
                    Log.e(TAG, "Failed to set mock location", error)
                    call.respond(
                        status = HttpStatusCode.InternalServerError,
                        message = ErrorResponse(error = error.message ?: "Unknown error"),
                    )
                }
            }

            get("/status") {
                call.respond(
                    StatusResponse(
                        active = isMockActive,
                        lastLocation = currentLocation,
                    ),
                )
            }

            post("/stop") {
                runCatching {
                    disableMockMode()
                    call.respond(SuccessResponse(success = true))
                }.onFailure { error ->
                    Log.e(TAG, "Failed to stop mock provider", error)
                    call.respond(
                        status = HttpStatusCode.InternalServerError,
                        message = ErrorResponse(error = error.message ?: "Unknown error"),
                    )
                }
            }
        }
    }

    fun start() {
        scope.launch {
            runCatching {
                server.start(wait = false)
                Log.d(TAG, "FakeGpsServer started on port $PORT")
            }.onFailure { error ->
                Log.e(TAG, "Failed to start FakeGpsServer", error)
            }
        }

        // 永続ループ: 100ms ごとに両経路で再注入
        scope.launch {
            while (isActive) {
                val data = currentLocation
                if (data != null && isMockActive) {
                    pushLocation(data)
                }
                delay(REPEAT_INTERVAL_MS)
            }
        }
    }

    @Suppress("MissingPermission")
    private fun enableMockMode() {
        if (isMockActive) return

        // FusedLocation mock mode
        runCatching {
            fusedClient.setMockMode(true)
            Log.d(TAG, "FusedLocation mock mode enabled")
        }.onFailure { error ->
            Log.e(TAG, "Failed to enable FusedLocation mock mode", error)
        }

        // LocationManager test providers
        for (provider in LM_PROVIDERS) {
            runCatching {
                locationManager.removeTestProvider(provider)
            }
            runCatching {
                locationManager.addTestProvider(
                    provider,
                    false,
                    false,
                    false,
                    false,
                    true,
                    true,
                    true,
                    ProviderProperties.POWER_USAGE_LOW,
                    ProviderProperties.ACCURACY_FINE,
                )
                locationManager.setTestProviderEnabled(provider, true)
                Log.d(TAG, "LocationManager test provider activated: $provider")
            }.onFailure { error ->
                Log.e(TAG, "Failed to activate test provider: $provider", error)
            }
        }

        isMockActive = true
    }

    @Suppress("MissingPermission")
    private fun pushLocation(data: LocationData) {
        val now = System.currentTimeMillis()
        val elapsedNanos = SystemClock.elapsedRealtimeNanos()

        // FusedLocation 経由
        val fusedLocation = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = data.lat
            longitude = data.lng
            bearing = data.bearing
            speed = data.speed
            accuracy = MOCK_ACCURACY
            altitude = data.altitude
            time = now
            elapsedRealtimeNanos = elapsedNanos
        }
        runCatching { fusedClient.setMockLocation(fusedLocation) }

        // LocationManager 経由 (gps + network)
        for (provider in LM_PROVIDERS) {
            val location = Location(provider).apply {
                latitude = data.lat
                longitude = data.lng
                bearing = data.bearing
                speed = data.speed
                accuracy = MOCK_ACCURACY
                altitude = data.altitude
                time = now
                elapsedRealtimeNanos = elapsedNanos
            }
            runCatching { locationManager.setTestProviderLocation(provider, location) }
        }
    }

    @Suppress("MissingPermission")
    private fun disableMockMode() {
        currentLocation = null

        runCatching { fusedClient.setMockMode(false) }

        for (provider in LM_PROVIDERS) {
            runCatching {
                locationManager.setTestProviderEnabled(provider, false)
                locationManager.removeTestProvider(provider)
            }
        }

        isMockActive = false
        Log.d(TAG, "All mock providers deactivated")
    }

    companion object {
        private const val TAG = "FakeGpsServer"
        private const val PORT = 5556
        private const val REPEAT_INTERVAL_MS = 100L
        private const val MOCK_ACCURACY = 3.0f
        private val LM_PROVIDERS = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
        )
    }
}

/** Web アプリから受信する位置データ */
@Serializable
data class LocationData(
    val lat: Double,
    val lng: Double,
    val bearing: Float = 0f,
    val speed: Float = 0f,
    val accuracy: Float = 5f,
    val altitude: Double = 0.0,
)

@Serializable
data class StatusResponse(
    val active: Boolean,
    val lastLocation: LocationData?,
)

@Serializable
data class SuccessResponse(
    val success: Boolean,
)

@Serializable
data class ErrorResponse(
    val error: String,
)
