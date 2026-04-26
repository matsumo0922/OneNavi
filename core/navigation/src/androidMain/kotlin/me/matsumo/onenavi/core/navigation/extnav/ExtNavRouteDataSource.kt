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
import me.matsumo.onenavi.core.model.GoogleRoute
import me.matsumo.onenavi.core.model.RouteItem
import me.matsumo.onenavi.core.model.RoutePoint
import me.matsumo.onenavi.core.model.RouteResult

/**
 * drive-supporter-api を使ったルート検索データソース。
 * `RouteClient.search` と `GuidanceClient.resolveGuidance` を並列に発行し、既存の [GoogleRoute]
 * モデルに射影する。ExtNav 固有情報 ([ExtNavRoutePayload]) は [ExtNavRouteRegistry] に保持する。
 *
 * Phase 1 では `priority=Recommended` 固定、候補 1 本のみ。
 */
class ExtNavRouteDataSource(
    private val clientProvider: ExtNavClientProvider,
    private val authGateway: ExtNavAuthGateway,
    private val registry: ExtNavRouteRegistry,
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

        val originPoint = RoutePoint(originLatitude, originLongitude)
        val destinationPoint = RoutePoint(destinationLatitude, destinationLongitude)
        val intermediates = intermediateWaypoints
            .map { (lat, lng) -> RoutePoint(lat, lng) }
            .toImmutableList()

        val geometry = buildGeometry(guidance, originPoint, destinationPoint)

        val tollYen = guidance.summary.tollYen.takeIf { it > 0 }
        val distanceMetres = guidance.summary.distanceMetres.toDouble()
        val timeSeconds = guidance.summary.timeSeconds.toDouble()

        val googleRoute = GoogleRoute(
            id = primaryRoute.id,
            routeToken = null,
            origin = originPoint,
            destination = destinationPoint,
            intermediateWaypoints = intermediates,
            geometry = geometry,
            distanceMeters = distanceMetres,
            durationSeconds = timeSeconds,
            steps = persistentListOf(),
        )

        registry.put(
            ExtNavRoutePayload(
                id = primaryRoute.id,
                route = primaryRoute,
                guidance = guidance,
            ),
        )

        val item = RouteItem(
            durationSeconds = timeSeconds,
            distanceMeters = distanceMetres,
            geometry = geometry,
            viaRoadNames = persistentListOf(),
            hasTolls = tollYen != null && tollYen > 0,
            tollFee = tollYen,
        )

        listOf(
            RouteResult(
                item = item,
                platformRoute = googleRoute,
            ),
        )
    }

    private fun buildGeometry(
        guidance: Guidance,
        originPoint: RoutePoint,
        destinationPoint: RoutePoint,
    ): kotlinx.collections.immutable.ImmutableList<RoutePoint> {
        // ROUTE バイナリ由来の dense polyline を最優先で使う (74.4km に 960 点 ≒ 77m 間隔)。
        // ROUTE 欠落 / decode 失敗時のみ intersection 連結 (≒ 500m 間隔) にフォールバック。
        val dense = guidance.polyline.map { coord ->
            RoutePoint(coord.latDegrees, coord.lonDegrees)
        }
        val raw = dense.ifEmpty {
            guidance.intersections.map { intersection ->
                RoutePoint(intersection.position.latDegrees, intersection.position.lonDegrees)
            }
        }
        if (raw.isEmpty()) {
            return listOf(originPoint, destinationPoint).toImmutableList()
        }
        return buildList {
            if (raw.first() != originPoint) add(originPoint)
            addAll(raw)
            if (raw.last() != destinationPoint) add(destinationPoint)
        }.toImmutableList()
    }

    private fun <T> ApiResult<T>.unwrap(hint: String): T = when (this) {
        is ApiResult.Success -> value
        is ApiResult.Failure -> error("$hint failed: $failure")
    }
}

/**
 * ExtNav 由来の [Route] + [Guidance] ペア。セッション管理層が [ExtNavRouteRegistry]
 * 経由で取得する。
 */
@Immutable
data class ExtNavRoutePayload(
    val id: String,
    val route: Route,
    val guidance: Guidance,
)
