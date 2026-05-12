package me.matsumo.onenavi.core.navigation.extnav

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import me.matsumo.drive.supporter.api.core.model.Coord
import me.matsumo.drive.supporter.api.core.result.ApiResult
import me.matsumo.drive.supporter.api.guidance.domain.RouteGuidance
import me.matsumo.drive.supporter.api.route.domain.CarPriority
import me.matsumo.drive.supporter.api.route.domain.RouteSearchCriteria
import me.matsumo.drive.supporter.api.route.domain.RouteWaypoint
import me.matsumo.onenavi.core.datasource.RouteDataSource
import me.matsumo.onenavi.core.model.RouteDetail
import me.matsumo.onenavi.core.model.RouteItem
import me.matsumo.onenavi.core.model.RoutePoint
import me.matsumo.onenavi.core.model.RouteResult

/**
 * 外部ナビ API ライブラリを使ったルート検索データソース。
 * `RouteClient.search` と `GuidanceClient.resolveGuidance` を並列に発行し、中立な [RouteDetail]
 * モデルに射影する。ルート探索エンドポイントは priority 1 件しか返さないため、複数候補は
 * `resolveGuidance` の `Guidance.routes` から抽出する。各候補は独立した [ExtNavRoutePayload]
 * として [ExtNavRouteRegistry] に保持する。
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
            priorities = persistentSetOf(
                CarPriority.Recommended,
                CarPriority.AvoidCongestion,
                CarPriority.Express,
                CarPriority.Free,
            ),
        )

        val (_, guidanceResult) = coroutineScope {
            val routesDeferred = async { client.route.search(criteria) }
            val guidanceDeferred = async { client.guidance.resolveGuidance(criteria) }
            routesDeferred.await() to guidanceDeferred.await()
        }

        val guidance = guidanceResult.unwrap("guidance.resolveGuidance")
        if (guidance.routes.isEmpty()) {
            error("guidance.resolveGuidance returned no routes")
        }

        val originPoint = RoutePoint(originLatitude, originLongitude)
        val destinationPoint = RoutePoint(destinationLatitude, destinationLongitude)
        val intermediates = intermediateWaypoints
            .map { (lat, lng) -> RoutePoint(lat, lng) }
            .toImmutableList()

        guidance.routes.map { routeGuidance ->
            val routeId = routeIdFor(routeGuidance)
            val geometry = buildGeometry(routeGuidance, originPoint, destinationPoint)
            val tollYen = routeGuidance.summary.tollYen.takeIf { it > 0 }
            val distanceMetres = routeGuidance.summary.distanceMetres.toDouble()
            val timeSeconds = routeGuidance.summary.timeSeconds.toDouble()

            val routeDetail = RouteDetail(
                id = routeId,
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
                    id = routeId,
                    routeGuidance = routeGuidance,
                ),
            )

            val item = RouteItem(
                durationSeconds = timeSeconds,
                distanceMeters = distanceMetres,
                geometry = geometry,
                viaRoadNames = persistentListOf(),
                hasTolls = tollYen != null,
                tollFee = tollYen,
                priorityLabel = priorityLabelFor(routeGuidance.priority),
            )

            RouteResult(
                item = item,
                detail = routeDetail,
            )
        }
    }

    private fun routeIdFor(routeGuidance: RouteGuidance): String =
        routeGuidance.priority?.name ?: "route-${routeGuidance.index}"

    private fun buildGeometry(
        routeGuidance: RouteGuidance,
        originPoint: RoutePoint,
        destinationPoint: RoutePoint,
    ): ImmutableList<RoutePoint> {
        // ROUTE バイナリ由来の dense polyline を最優先で使う (74.4km に 960 点 ≒ 77m 間隔)。
        // ROUTE 欠落 / decode 失敗時のみ intersection 連結 (≒ 500m 間隔) にフォールバック。
        val dense = routeGuidance.polyline.map { coord ->
            RoutePoint(coord.latDegrees, coord.lonDegrees)
        }
        val raw = dense.ifEmpty {
            routeGuidance.intersections.map { intersection ->
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

    private fun priorityLabelFor(priority: CarPriority?): String? = when (priority) {
        CarPriority.Recommended -> "推奨"
        CarPriority.AiRoute -> "AI"
        CarPriority.Free -> "一般道優先"
        CarPriority.Express -> "高速優先"
        CarPriority.Distance -> "距離優先"
        CarPriority.AvoidCongestion -> "渋滞回避"
        CarPriority.EcoPriority -> "燃費優先"
        CarPriority.Scenic -> "景観優先"
        CarPriority.FreeDistance -> "一般道距離優先"
        CarPriority.UrbanExpress -> "都市高速優先"
        CarPriority.AvoidUrbanExpress -> "都市高速回避"
        CarPriority.SecondRecommended -> "第 2 推奨"
        null -> null
    }

    private fun <T> ApiResult<T>.unwrap(hint: String): T = when (this) {
        is ApiResult.Success -> value
        is ApiResult.Failure -> error("$hint failed: $failure")
    }
}

/**
 * ExtNav 由来のルート 1 本分のペイロード。`Guidance.routes` の 1 要素に対応する。
 * セッション管理層が [ExtNavRouteRegistry] 経由で取得する。
 */
@Immutable
data class ExtNavRoutePayload(
    val id: String,
    val routeGuidance: RouteGuidance,
)
