package me.matsumo.onenavi.core.navigation.extnav

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import me.matsumo.drive.supporter.api.core.model.Coord
import me.matsumo.drive.supporter.api.core.result.ApiResult
import me.matsumo.drive.supporter.api.guidance.domain.Guidance
import me.matsumo.drive.supporter.api.route.domain.CarPriority
import me.matsumo.drive.supporter.api.route.domain.Route
import me.matsumo.drive.supporter.api.route.domain.RouteSearchCriteria
import me.matsumo.drive.supporter.api.route.domain.RouteWaypoint
import me.matsumo.onenavi.core.datasource.RouteDataSource
import me.matsumo.onenavi.core.model.RouteItem
import me.matsumo.onenavi.core.model.RoutePoint
import me.matsumo.onenavi.core.model.RouteResult

/**
 * drive-supporter-api を使ったルート検索データソース。
 * `RouteClient.search` と `GuidanceClient.resolveGuidance` を並列に発行して [ExtNavRoutePayload] を組み立てる。
 *
 * Phase 1 では `priority=Recommended` 固定、候補 1 本のみ。
 */
class ExtNavRouteDataSource(
    private val clientProvider: ExtNavClientProvider,
    private val authGateway: ExtNavAuthGateway,
) : RouteDataSource {

    override suspend fun searchRoutes(
        originLatitude: Double,
        originLongitude: Double,
        destinationLatitude: Double,
        destinationLongitude: Double,
        intermediateWaypoints: List<Pair<Double, Double>>,
    ): Result<List<RouteResult>> = runCatching {
        authGateway.ensureSignedIn().getOrThrow()

        val client = clientProvider.get()
        val criteria = RouteSearchCriteria(
            start = Coord.fromDegrees(originLatitude, originLongitude),
            goal = Coord.fromDegrees(destinationLatitude, destinationLongitude),
            waypoints = intermediateWaypoints
                .map { (lat, lng) -> RouteWaypoint(coord = Coord.fromDegrees(lat, lng)) }
                .toImmutableList(),
            limit = 1,
            priority = CarPriority.Recommended,
        )

        val (routesResult, guidanceResult) = coroutineScope {
            val routesDeferred = async { client.route.search(criteria) }
            val guidanceDeferred = async { client.guidance.resolveGuidance(criteria) }
            routesDeferred.await() to guidanceDeferred.await()
        }

        val routes = routesResult.unwrap("route.search")
        val guidance = guidanceResult.unwrap("guidance.resolveGuidance")
        val primaryRoute = routes.firstOrNull()
            ?: error("route.search returned no routes")

        val geometry = guidance.intersections
            .map { intersection ->
                RoutePoint(intersection.position.latDegrees, intersection.position.lonDegrees)
            }
            .toImmutableList()

        val tollYen = guidance.summary.tollYen.takeIf { it > 0 }

        val item = RouteItem(
            durationSeconds = guidance.summary.timeSeconds.toDouble(),
            distanceMeters = guidance.summary.distanceMetres.toDouble(),
            geometry = geometry,
            viaRoadNames = persistentListOf(),
            hasTolls = tollYen != null && tollYen > 0,
            tollFee = tollYen,
        )

        val payload = ExtNavRoutePayload(
            id = primaryRoute.id,
            route = primaryRoute,
            guidance = guidance,
        )

        listOf(
            RouteResult(
                item = item,
                platformRoute = payload,
            ),
        )
    }

    private fun <T> ApiResult<T>.unwrap(hint: String): T = when (this) {
        is ApiResult.Success -> value
        is ApiResult.Failure -> error("$hint failed: $failure")
    }
}

/**
 * [RouteResult.platformRoute] に詰めるための Android 固有ペイロード。
 * drive-supporter-api の [Route] + [Guidance] を UI / ナビ層へ受け渡す。
 */
@Immutable
data class ExtNavRoutePayload(
    val id: String,
    val route: Route,
    val guidance: Guidance,
)
