package me.matsumo.onenavi.debug

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.SystemClock
import android.util.Log
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * デバッグ用 Fake GPS サーバー。
 * Ktor CIO エンジンでポート 5556 に HTTP サーバーを起動し、
 * PC 上の Web アプリから ADB forward 経由で受信した位置情報を
 * LocationManager の TestProvider として注入する。
 */
class FakeGpsServer(
    private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private var isProviderActive = false
    private var lastLocation: LocationData? = null
    private var repeatJob: Job? = null

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
                    setMockLocation(data)
                    lastLocation = data
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
                        active = isProviderActive,
                        lastLocation = lastLocation,
                    ),
                )
            }

            post("/stop") {
                runCatching {
                    removeMockProvider()
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
    }

    private fun ensureTestProvider() {
        if (isProviderActive) return

        for (provider in PROVIDERS) {
            runCatching {
                locationManager.removeTestProvider(provider)
            }

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
            Log.d(TAG, "Test provider activated: $provider")
        }

        isProviderActive = true
    }

    private fun setMockLocation(data: LocationData) {
        ensureTestProvider()
        pushLocation(data)
        startRepeatLoop(data)
        Log.d(TAG, "Location set: ${data.lat}, ${data.lng} bearing=${data.bearing} speed=${data.speed}")
    }

    /**
     * 実 GPS ハードウェアに勝つために、最後の mock 位置を高頻度で再注入し続ける。
     * Web から新しい位置が来たらループを再起動する。
     */
    private fun startRepeatLoop(data: LocationData) {
        repeatJob?.cancel()
        repeatJob = scope.launch {
            while (isActive) {
                delay(REPEAT_INTERVAL_MS)
                pushLocation(data)
            }
        }
    }

    private fun pushLocation(data: LocationData) {
        val now = System.currentTimeMillis()
        val elapsedNanos = SystemClock.elapsedRealtimeNanos()

        for (provider in PROVIDERS) {
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
            locationManager.setTestProviderLocation(provider, location)
        }
    }

    private fun removeMockProvider() {
        if (!isProviderActive) return

        repeatJob?.cancel()
        repeatJob = null

        for (provider in PROVIDERS) {
            runCatching {
                locationManager.setTestProviderEnabled(provider, false)
                locationManager.removeTestProvider(provider)
            }
        }

        isProviderActive = false
        lastLocation = null

        Log.d(TAG, "Mock providers deactivated")
    }

    companion object {
        private const val TAG = "FakeGpsServer"
        private const val PORT = 5556
        private const val REPEAT_INTERVAL_MS = 100L
        private const val MOCK_ACCURACY = 1.0f
        private val PROVIDERS = listOf(
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
