package me.matsumo.onenavi.core.navigation.server

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess

/**
 * server route API の接続設定。
 */
internal class GuidanceApiConfig(
    val baseUrl: String,
    val cloudflareAccessClientId: String = "",
    val cloudflareAccessClientSecret: String = "",
) {
    /**
     * route endpoint の URL を返す。
     */
    fun routeEndpointUrl(): String {
        val normalizedBaseUrl = baseUrl.trimEnd('/')
        require(normalizedBaseUrl.isNotBlank()) { "server route base url is not configured" }

        return "$normalizedBaseUrl/api/v1/route"
    }

    /**
     * Cloudflare Access service token のヘッダペアを返す。
     */
    fun cloudflareAccessHeaders(): List<Pair<String, String>> {
        val hasClientId = cloudflareAccessClientId.isNotBlank()
        val hasClientSecret = cloudflareAccessClientSecret.isNotBlank()
        if (!hasClientId || !hasClientSecret) return emptyList()

        return listOf(
            CF_ACCESS_CLIENT_ID_HEADER to cloudflareAccessClientId,
            CF_ACCESS_CLIENT_SECRET_HEADER to cloudflareAccessClientSecret,
        )
    }

    private companion object {
        const val CF_ACCESS_CLIENT_ID_HEADER = "CF-Access-Client-Id"
        const val CF_ACCESS_CLIENT_SECRET_HEADER = "CF-Access-Client-Secret"
    }
}

/**
 * server route API を呼び出す client。
 */
internal interface GuidanceApiClient {

    /**
     * 経路探索を実行する。
     */
    suspend fun route(request: RoutePlanRequestDto): Result<RoutePlanResponseDto>
}

/**
 * Ktor で server route API を呼び出す client。
 */
internal class HttpGuidanceApiClient(
    private val httpClient: HttpClient,
    private val config: GuidanceApiConfig,
) : GuidanceApiClient {

    override suspend fun route(request: RoutePlanRequestDto): Result<RoutePlanResponseDto> =
        runCatching {
            val response = httpClient.post(config.routeEndpointUrl()) {
                contentType(ContentType.Application.Json)
                config.cloudflareAccessHeaders().forEach { cloudflareAccessHeader ->
                    header(cloudflareAccessHeader.first, cloudflareAccessHeader.second)
                }
                setBody(request)
            }
            response.ensureSuccess()
            response.body<RoutePlanResponseDto>()
        }

    /**
     * HTTP 成功応答でなければ例外に変換する。
     */
    private fun HttpResponse.ensureSuccess() {
        if (status.isSuccess()) return

        throw GuidanceApiException(statusCode = status.value)
    }
}

/**
 * server route API の HTTP 失敗。
 */
internal class GuidanceApiException(
    val statusCode: Int,
) : Exception("server route request failed: HTTP $statusCode")
