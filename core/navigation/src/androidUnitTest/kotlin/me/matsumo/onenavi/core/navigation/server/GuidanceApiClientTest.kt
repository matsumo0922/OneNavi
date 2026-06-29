package me.matsumo.onenavi.core.navigation.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [HttpGuidanceApiClient] の HTTP wire contract テスト。
 */
class GuidanceApiClientTest {

    @Test
    fun `route は Cloudflare Access header と JSON body を付けて POST する`() = runTest {
        var capturedRequest: HttpRequestData? = null
        val httpClient = mockHttpClient { scope, request ->
            capturedRequest = request
            scope.respondJson("""{"candidates":[]}""")
        }
        val apiClient = HttpGuidanceApiClient(
            httpClient = httpClient,
            config = GuidanceApiConfig(
                baseUrl = "https://route.example.test/",
                cloudflareAccessClientId = "client-id",
                cloudflareAccessClientSecret = "client-secret",
            ),
        )

        val result = apiClient.route(routeRequest())
        val request = assertNotNull(capturedRequest)
        val requestBody = json.decodeFromString<RoutePlanRequestDto>(request.bodyText())

        assertTrue(result.isSuccess)
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("https://route.example.test/api/v1/route", request.url.toString())
        assertEquals("client-id", request.headers[CF_ACCESS_CLIENT_ID_HEADER])
        assertEquals("client-secret", request.headers[CF_ACCESS_CLIENT_SECRET_HEADER])
        assertEquals(RoutePriorityDto.RECOMMENDED, requestBody.requestedPriorities.single())
    }

    @Test
    fun `Cloudflare Access token が揃っていない場合は header を付けない`() = runTest {
        var capturedRequest: HttpRequestData? = null
        val httpClient = mockHttpClient { scope, request ->
            capturedRequest = request
            scope.respondJson("""{"candidates":[]}""")
        }
        val apiClient = HttpGuidanceApiClient(
            httpClient = httpClient,
            config = GuidanceApiConfig(
                baseUrl = "https://route.example.test",
                cloudflareAccessClientId = "client-id",
                cloudflareAccessClientSecret = "",
            ),
        )

        val result = apiClient.route(routeRequest())
        val request = assertNotNull(capturedRequest)

        assertTrue(result.isSuccess)
        assertNull(request.headers[CF_ACCESS_CLIENT_ID_HEADER])
        assertNull(request.headers[CF_ACCESS_CLIENT_SECRET_HEADER])
    }

    @Test
    fun `non 2xx は GuidanceApiException に変換する`() = runTest {
        val httpClient = mockHttpClient { scope, _ ->
            scope.respond(
                content = """{"message":"forbidden"}""",
                status = HttpStatusCode.Forbidden,
                headers = jsonHeaders(),
            )
        }
        val apiClient = HttpGuidanceApiClient(
            httpClient = httpClient,
            config = GuidanceApiConfig(baseUrl = "https://route.example.test"),
        )

        val result = apiClient.route(routeRequest())
        val exception = result.exceptionOrNull()

        assertTrue(result.isFailure)
        assertTrue(exception is GuidanceApiException)
        assertEquals(HttpStatusCode.Forbidden.value, exception.statusCode)
    }

    private fun mockHttpClient(handler: MockEngineHandler): HttpClient {
        val engine = MockEngine { request -> handler.respond(this, request) }

        return HttpClient(engine) {
            install(ContentNegotiation) {
                json(json)
            }
        }
    }

    private fun MockRequestHandleScope.respondJson(content: String) =
        respond(
            content = content,
            headers = jsonHeaders(),
        )

    private fun jsonHeaders() =
        headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private suspend fun HttpRequestData.bodyText(): String =
        body.toByteArray().decodeToString()

    private fun routeRequest(): RoutePlanRequestDto =
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

    private fun interface MockEngineHandler {

        /**
         * mock request に対する response を返す。
         */
        fun respond(
            scope: MockRequestHandleScope,
            request: HttpRequestData,
        ): HttpResponseData
    }

    private companion object {

        /** Cloudflare Access service token の client id header。 */
        const val CF_ACCESS_CLIENT_ID_HEADER: String = "CF-Access-Client-Id"

        /** Cloudflare Access service token の client secret header。 */
        const val CF_ACCESS_CLIENT_SECRET_HEADER: String = "CF-Access-Client-Secret"

        /** test 用 JSON 設定。 */
        val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        }
    }
}
