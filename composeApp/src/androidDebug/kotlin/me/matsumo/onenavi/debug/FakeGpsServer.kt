package me.matsumo.onenavi.debug

import android.content.Context
import android.location.Location
import android.location.LocationManager
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
 * Ktor CIO エンジンでポート 5556 に HTTP サーバーを起動し、
 * PC 上の Web アプリから ADB forward 経由で受信した位置情報を注入する。
 *
 * FusedLocationProviderClient.setMockLocation() を使い、
 * Google Maps 等の FusedLocation 利用アプリにもリアルタイムで反映させる。
 */
class FakeGpsServer(
    private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fusedClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private var isMockMode = false

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
                    pushLocation(data)
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
                        active = isMockMode,
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

        // 永続ループ: currentLocation を 100ms ごとに再注入
        scope.launch {
            while (isActive) {
                val data = currentLocation
                if (data != null && isMockMode) {
                    pushLocation(data)
                }
                delay(REPEAT_INTERVAL_MS)
            }
        }
    }

    @Suppress("MissingPermission")
    private fun enableMockMode() {
        if (isMockMode) return

        runCatching {
            fusedClient.setMockMode(true)
            Log.d(TAG, "FusedLocation mock mode enabled")
        }.onFailure { error ->
            Log.e(TAG, "Failed to enable mock mode", error)
        }

        isMockMode = true
    }

    @Suppress("MissingPermission")
    private fun pushLocation(data: LocationData) {
        val location = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = data.lat
            longitude = data.lng
            bearing = data.bearing
            speed = data.speed
            accuracy = MOCK_ACCURACY
            altitude = data.altitude
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }

        fusedClient.setMockLocation(location)
    }

    @Suppress("MissingPermission")
    private fun disableMockMode() {
        currentLocation = null

        runCatching {
            fusedClient.setMockMode(false)
        }

        isMockMode = false
        Log.d(TAG, "FusedLocation mock mode disabled")
    }

    companion object {
        private const val TAG = "FakeGpsServer"
        private const val PORT = 5556
        private const val REPEAT_INTERVAL_MS = 100L
        private const val MOCK_ACCURACY = 3.0f
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
