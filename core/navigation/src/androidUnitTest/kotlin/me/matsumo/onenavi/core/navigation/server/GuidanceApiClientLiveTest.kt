package me.matsumo.onenavi.core.navigation.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Cloudflare Access 越しの server route API live e2e テスト。
 */
class GuidanceApiClientLiveTest {

    @Test
    fun `protected server route API を呼び出して候補を decode できる`() = runTest {
        if (!isLiveTestEnabled()) return@runTest

        val httpClient = liveHttpClient()
        val apiClient = HttpGuidanceApiClient(
            httpClient = httpClient,
            config = GuidanceApiConfig(
                baseUrl = requiredEnv(SERVER_ROUTE_BASE_URL_ENV),
                cloudflareAccessClientIdHeader = requiredEnv(SERVER_ROUTE_CF_ACCESS_CLIENT_ID_HEADER_ENV),
                cloudflareAccessClientSecretHeader = requiredEnv(SERVER_ROUTE_CF_ACCESS_CLIENT_SECRET_HEADER_ENV),
            ),
        )

        try {
            val response = apiClient.route(liveRouteRequest()).getOrThrow()

            assertTrue(response.candidates.isNotEmpty())
            val candidate = response.candidates.first()
            assertTrue(candidate.routePackageId.isNotBlank())
            assertTrue(candidate.geometry.isNotEmpty())
            assertTrue(candidate.summary.lengthMetres > 0)
            assertTrue(candidate.summary.durationSeconds > 0)
        } finally {
            httpClient.close()
        }
    }

    private fun liveHttpClient(): HttpClient =
        HttpClient(OkHttp) {
            install(HttpTimeout) {
                connectTimeoutMillis = 5_000
                requestTimeoutMillis = 20_000
                socketTimeoutMillis = 20_000
            }
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        isLenient = true
                        coerceInputValues = true
                        encodeDefaults = true
                    },
                )
            }
        }

    private fun liveRouteRequest(): RoutePlanRequestDto =
        RoutePlanRequestDto(
            origin = RouteCoordinateDto(
                latitude = 35.681236,
                longitude = 139.767125,
            ),
            destination = RouteCoordinateDto(
                latitude = 35.658581,
                longitude = 139.745433,
            ),
            requestedPriorities = listOf(RoutePriorityDto.RECOMMENDED),
        )

    private fun isLiveTestEnabled(): Boolean =
        System.getenv(SERVER_ROUTE_LIVE_TESTS_ENV).equals("true", ignoreCase = true)

    private fun requiredEnv(name: String): String {
        val value = System.getenv(name)
        require(!value.isNullOrBlank()) { "$name is required when $SERVER_ROUTE_LIVE_TESTS_ENV=true" }

        return value
    }

    private companion object {

        /** server route live e2e test 有効化フラグ。 */
        const val SERVER_ROUTE_LIVE_TESTS_ENV: String = "SERVER_ROUTE_LIVE_TESTS"

        /** live e2e test で叩く server route API の base URL。 */
        const val SERVER_ROUTE_BASE_URL_ENV: String = "SERVER_ROUTE_BASE_URL"

        /** Cloudflare Access service token の client id header 行。 */
        const val SERVER_ROUTE_CF_ACCESS_CLIENT_ID_HEADER_ENV: String = "SERVER_ROUTE_CF_ACCESS_CLIENT_ID_HEADER"

        /** Cloudflare Access service token の client secret header 行。 */
        const val SERVER_ROUTE_CF_ACCESS_CLIENT_SECRET_HEADER_ENV: String = "SERVER_ROUTE_CF_ACCESS_CLIENT_SECRET_HEADER"
    }
}
