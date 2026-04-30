package me.matsumo.onenavi.core.navigation.newguidance

import kotlinx.collections.immutable.toImmutableList
import me.matsumo.onenavi.core.model.RoutePoint
import me.matsumo.onenavi.core.navigation.newguidance.model.RefinedChunk
import me.matsumo.onenavi.core.navigation.newguidance.model.RefinedRoute
import me.matsumo.onenavi.core.navigation.newguidance.model.RoutesApiWaypoint
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 外部ナビ API ライブラリの polyline を Google Routes API で再現する純粋関数群。
 *
 * spec/23 のアルゴリズム (TypeScript dev tool) を Kotlin に移植したもの。proto / guide は
 * 触らず、入力は polyline (List<[RoutePoint]>) と origin / destination のみで完結する。
 *
 * 入出力:
 * 1. [samplePolylineWaypoints]: polyline 内側頂点を [Farthest-Point Sampling][1] で間引いて
 *    waypoint 列を作る。各 waypoint には局所接線方向 (compass bearing) を heading として付与。
 * 2. [chunkWaypoints]: Routes API v2 の intermediate 25 個ハードキャップに合わせて chunk 分割。
 *    隣接 chunk は終点・始点を共有する。
 * 3. [computeChunkedRoute]: 各 chunk を Routes API に逐次投げて [RefinedChunk] のリストにする。
 * 4. [refine]: 高レベルエントリ。1〜3 を組み合わせて [RefinedRoute] を返す。
 *
 * [1]: https://en.wikipedia.org/wiki/Farthest-first_traversal
 */
class ExtNavRouteRefiner(
    private val routesApiClient: RoutesApiClient,
) {

    /**
     * polyline 内側頂点を FPS で間引いて waypoint 列を作る。
     *
     * @param extPolyline 外部ルート polyline (WGS84)。先頭が origin、末尾が destination
     * @param targetGapMeters waypoint 間隔の目標値。spec/23 で実測 4000m が最適
     * @return origin と destination を端点に含む waypoint 列。中間 waypoint には局所接線方向の
     *         heading が付く
     */
    fun samplePolylineWaypoints(
        extPolyline: List<RoutePoint>,
        targetGapMeters: Double = DEFAULT_TARGET_GAP_METERS,
    ): List<RoutesApiWaypoint> {
        require(extPolyline.size >= 2) {
            "extPolyline must contain at least 2 points (origin + destination)"
        }
        require(targetGapMeters > 0.0) {
            "targetGapMeters must be positive: $targetGapMeters"
        }

        val cumDistances = computeCumulativeDistances(extPolyline)
        val totalLength = cumDistances.last()
        if (totalLength <= 0.0 || extPolyline.size == 2) {
            return listOf(
                RoutesApiWaypoint(point = extPolyline.first()),
                RoutesApiWaypoint(point = extPolyline.last()),
            )
        }

        val pickedIndices = pickInteriorByFps(
            cumDistances = cumDistances,
            totalLength = totalLength,
            targetGapMeters = targetGapMeters,
        )

        val orderedIndices = (listOf(0) + pickedIndices.sorted() + listOf(extPolyline.lastIndex))
        return orderedIndices.map { index ->
            val isEndpoint = index == 0 || index == extPolyline.lastIndex
            RoutesApiWaypoint(
                point = extPolyline[index],
                heading = if (isEndpoint) null else computeLocalHeading(extPolyline, index),
            )
        }
    }

    /**
     * Routes API の intermediate 上限に合わせて waypoint 列を chunk に分割する。
     *
     * 隣接 chunk は終点と始点で waypoint を共有する。例: 60 点 / [intermediateMax]=25 →
     * chunk0=[0..26], chunk1=[26..52], chunk2=[52..59]。
     */
    fun chunkWaypoints(
        waypoints: List<RoutesApiWaypoint>,
        intermediateMax: Int = ROUTES_API_INTERMEDIATE_MAX,
    ): List<List<RoutesApiWaypoint>> {
        require(waypoints.size >= 2) { "waypoints must have at least 2 entries" }
        require(intermediateMax >= 1) { "intermediateMax must be >= 1" }

        val stepSize = intermediateMax + 1
        val chunks = mutableListOf<List<RoutesApiWaypoint>>()
        var start = 0
        while (start < waypoints.lastIndex) {
            val end = (start + stepSize).coerceAtMost(waypoints.lastIndex)
            chunks += waypoints.subList(fromIndex = start, toIndex = end + 1)
            start = end
        }
        return chunks
    }

    /**
     * 各 chunk を Routes API に逐次投げて [RefinedChunk] にする。並列化はしない (rate limit
     * 配慮 + 失敗時の特定容易性)。途中で失敗すると例外を投げる。
     */
    suspend fun computeChunkedRoute(
        chunks: List<List<RoutesApiWaypoint>>,
        useVia: Boolean = true,
    ): List<RefinedChunk> = chunks.map { chunk ->
        val response = routesApiClient.computeRoute(chunk, useVia).getOrThrow()
        RefinedChunk(
            waypoints = chunk.toImmutableList(),
            routeToken = response.routeToken,
            polyline = response.polyline.toImmutableList(),
            distanceMeters = response.distanceMeters,
            durationSeconds = response.durationSeconds,
        )
    }

    /**
     * 高レベルエントリ。サンプリング → chunk 化 → Routes API 呼び出しを通して [RefinedRoute]
     * を返す。
     */
    suspend fun refine(
        extPolyline: List<RoutePoint>,
        origin: RoutePoint,
        destination: RoutePoint,
        targetGapMeters: Double = DEFAULT_TARGET_GAP_METERS,
        intermediateMax: Int = ROUTES_API_INTERMEDIATE_MAX,
        useVia: Boolean = true,
    ): RefinedRoute {
        val sampled = samplePolylineWaypoints(extPolyline, targetGapMeters)
        val chunked = chunkWaypoints(sampled, intermediateMax)
        val refinedChunks = computeChunkedRoute(chunked, useVia)
        return RefinedRoute(
            chunks = refinedChunks.toImmutableList(),
            origin = origin,
            destination = destination,
        )
    }

    private fun pickInteriorByFps(
        cumDistances: DoubleArray,
        totalLength: Double,
        targetGapMeters: Double,
    ): List<Int> {
        val interiorIndices = (1..cumDistances.lastIndex - 1).toList()
        if (interiorIndices.isEmpty()) return emptyList()

        val seedDistances = mutableListOf(0.0, totalLength)
        val pickedIndices = mutableListOf<Int>()
        val desiredCount = ceil(totalLength / targetGapMeters).toInt().coerceAtLeast(1)
        val earlyStopThreshold = targetGapMeters * EARLY_TERMINATION_FRACTION

        repeat(desiredCount) {
            var bestIndex = -1
            var bestMinGap = Double.NEGATIVE_INFINITY
            for (candidateIndex in interiorIndices) {
                if (candidateIndex in pickedIndices) continue
                val candidateDistance = cumDistances[candidateIndex]
                val minGap = seedDistances.minOf { abs(candidateDistance - it) }
                if (minGap > bestMinGap) {
                    bestMinGap = minGap
                    bestIndex = candidateIndex
                }
            }
            if (bestIndex < 0 || bestMinGap < earlyStopThreshold) {
                return pickedIndices
            }
            pickedIndices += bestIndex
            seedDistances += cumDistances[bestIndex]
        }
        return pickedIndices
    }

    private fun computeCumulativeDistances(polyline: List<RoutePoint>): DoubleArray {
        val cum = DoubleArray(polyline.size)
        for (index in 1..polyline.lastIndex) {
            cum[index] = cum[index - 1] + haversineDistanceMeters(polyline[index - 1], polyline[index])
        }
        return cum
    }

    private fun computeLocalHeading(polyline: List<RoutePoint>, index: Int): Int {
        val previous = polyline[index - 1]
        val next = polyline[index + 1]
        return computeBearingDegrees(from = previous, to = next).roundToInt().mod(BEARING_FULL_TURN)
    }

    /**
     * 2 点間の haversine 距離 (m)。地球半径 6,371km を使う。
     */
    private fun haversineDistanceMeters(a: RoutePoint, b: RoutePoint): Double {
        val deltaLatRad = Math.toRadians(b.latitude - a.latitude)
        val deltaLngRad = Math.toRadians(b.longitude - a.longitude)
        val sinHalfLat = sin(deltaLatRad / 2.0)
        val sinHalfLng = sin(deltaLngRad / 2.0)
        val component = sinHalfLat * sinHalfLat +
            cos(Math.toRadians(a.latitude)) *
            cos(Math.toRadians(b.latitude)) *
            sinHalfLng * sinHalfLng
        val centralAngle = 2.0 * atan2(sqrt(component), sqrt(1.0 - component))
        return EARTH_RADIUS_METERS * centralAngle
    }

    /**
     * compass bearing (北 0、東 90、南 180、西 270)。`atan2(y, x)` を度に変換し、負値を補正。
     */
    private fun computeBearingDegrees(from: RoutePoint, to: RoutePoint): Double {
        val lat1Rad = Math.toRadians(from.latitude)
        val lat2Rad = Math.toRadians(to.latitude)
        val deltaLngRad = Math.toRadians(to.longitude - from.longitude)
        val y = sin(deltaLngRad) * cos(lat2Rad)
        val x = cos(lat1Rad) * sin(lat2Rad) -
            sin(lat1Rad) * cos(lat2Rad) * cos(deltaLngRad)
        val thetaDeg = Math.toDegrees(atan2(y, x))
        return (thetaDeg + BEARING_FULL_TURN) % BEARING_FULL_TURN
    }

    companion object {
        const val DEFAULT_TARGET_GAP_METERS = 4_000.0
        const val ROUTES_API_INTERMEDIATE_MAX = 25

        private const val EARTH_RADIUS_METERS = 6_371_000.0
        private const val BEARING_FULL_TURN = 360
        private const val EARLY_TERMINATION_FRACTION = 0.4
    }
}
