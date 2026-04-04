package me.matsumo.onenavi.debug

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.SystemClock
import io.github.aakira.napier.Napier
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
                    Napier.e("Failed to set mock location", error)
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
                    Napier.e("Failed to stop mock provider", error)
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
                Napier.d("FakeGpsServer started on port $PORT")
            }.onFailure { error ->
                Napier.e("Failed to start FakeGpsServer", error)
            }
        }
    }

    private fun ensureTestProvider() {
        if (isProviderActive) return

        runCatching {
            locationManager.removeTestProvider(LocationManager.GPS_PROVIDER)
        }

        locationManager.addTestProvider(
            LocationManager.GPS_PROVIDER,
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
        locationManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
        isProviderActive = true

        Napier.d("Mock GPS provider activated")
    }

    private fun setMockLocation(data: LocationData) {
        ensureTestProvider()

        val location = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = data.lat
            longitude = data.lng
            bearing = data.bearing
            speed = data.speed
            accuracy = data.accuracy
            altitude = data.altitude
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }

        locationManager.setTestProviderLocation(LocationManager.GPS_PROVIDER, location)
    }

    private fun removeMockProvider() {
        if (!isProviderActive) return

        runCatching {
            locationManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, false)
            locationManager.removeTestProvider(LocationManager.GPS_PROVIDER)
        }

        isProviderActive = false
        lastLocation = null

        Napier.d("Mock GPS provider deactivated")
    }

    companion object {
        private const val PORT = 5556
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
