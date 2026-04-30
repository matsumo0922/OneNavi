package me.matsumo.onenavi.core.navigation.newguidance

import me.matsumo.onenavi.core.model.RoutePoint
import me.matsumo.onenavi.core.navigation.newguidance.model.RoutesApiWaypoint

/**
 * Google Routes API v2 (`directions/v2:computeRoutes`) の薄いクライアント。
 *
 * spec/23 で確立した「外部ナビ API ライブラリの polyline を Routes API 上に再現する」
 * フローのうち、1 chunk = 1 回分の HTTP 呼び出しを担当する。
 *
 * 実装は [DefaultRoutesApiClient]。テストでは fake に差し替える。
 */
interface RoutesApiClient {

    /**
     * 1 chunk 分の waypoints を Routes API に投げて polyline と route_token を取得する。
     *
     * @param chunk 先頭が origin、末尾が destination、それ以外は intermediates として扱う
     * @param useVia intermediates に `via:true` を付けるか (spec/23 §7.3)。origin/destination
     *               には付けない (Routes API 仕様)
     * @return polyline + route_token + distance + duration を含む結果。HTTP/JSON エラーは
     *         [Result.failure] で返る
     */
    suspend fun computeRoute(
        chunk: List<RoutesApiWaypoint>,
        useVia: Boolean,
    ): Result<RoutesApiResponse>
}

/**
 * Routes API のレスポンスから取り出した必要最小限のデータ。
 */
data class RoutesApiResponse(
    val polyline: List<RoutePoint>,
    val routeToken: String,
    val distanceMeters: Int,
    val durationSeconds: Long,
)
