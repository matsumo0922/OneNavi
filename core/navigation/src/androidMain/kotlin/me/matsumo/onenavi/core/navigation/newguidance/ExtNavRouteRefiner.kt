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
     * [forcedWaypoints] が空でない場合は spec/23 §8.5.2 の `buildCustomSentWaypoints` 相当の
     * 動作になる。各 forced waypoint を polyline 上の最近傍頂点に projection し、その累積距離を
     * FPS の seed に追加した上で残り頂点を埋め、最後に 5m 以内の近接 pick を dedup (forced 優先で
     * 残す)。これにより「ユーザが指定した経由地は必ず通る + その間は polyline 形状を補助点で
     * 追従させる」test polyline が得られる。
     *
     * @param extPolyline 外部ルート polyline (WGS84)。先頭が origin、末尾が destination
     * @param forcedWaypoints 必ず通したい中間地点 (空可)。座標そのものを使い、heading は polyline
     *                        への projection 接線方向で補完する
     * @param targetGapMeters waypoint 間隔の目標値。spec/23 で実測 4000m が最適
     * @return origin と destination を端点に含む waypoint 列。中間 waypoint には局所接線方向の
     *         heading が付く
     */
    fun samplePolylineWaypoints(
        extPolyline: List<RoutePoint>,
        forcedWaypoints: List<RoutePoint> = emptyList(),
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
        val originWaypoint = RoutesApiWaypoint(point = extPolyline.first())
        val destinationWaypoint = RoutesApiWaypoint(point = extPolyline.last())

        if (totalLength <= 0.0 || extPolyline.size == 2) {
            val forcedMiddle = forcedWaypoints.map { RoutesApiWaypoint(point = it) }
            return listOf(originWaypoint) + forcedMiddle + listOf(destinationWaypoint)
        }

        val forcedProjections = forcedWaypoints.map { forced ->
            projectForcedWaypoint(forced, extPolyline, cumDistances)
        }

        val totalDesired = ceil(totalLength / targetGapMeters).toInt().coerceAtLeast(1)
        val additionalDesired = (totalDesired - forcedProjections.size).coerceAtLeast(0)

        val pickedInteriorIndices = pickInteriorByFps(
            cumDistances = cumDistances,
            totalLength = totalLength,
            targetGapMeters = targetGapMeters,
            additionalSeedDistances = forcedProjections.map { it.cumDist },
            desiredCount = additionalDesired,
        )

        val interiorPicks = pickedInteriorIndices.map { index ->
            WaypointPick(
                cumDist = cumDistances[index],
                point = extPolyline[index],
                heading = computeLocalHeading(extPolyline, index),
                isForced = false,
            )
        }
        val forcedPicks = forcedProjections.map { projection ->
            WaypointPick(
                cumDist = projection.cumDist,
                point = projection.point,
                heading = projection.heading,
                isForced = true,
            )
        }

        val sortedPicks = (interiorPicks + forcedPicks).sortedBy { it.cumDist }
        val dedupedPicks = mergeNearbyPicks(sortedPicks, DEDUP_THRESHOLD_METERS)

        val middleWaypoints = dedupedPicks.map { pick ->
            RoutesApiWaypoint(point = pick.point, heading = pick.heading)
        }
        return listOf(originWaypoint) + middleWaypoints + listOf(destinationWaypoint)
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
        intermediates: List<RoutePoint> = emptyList(),
        targetGapMeters: Double = DEFAULT_TARGET_GAP_METERS,
        intermediateMax: Int = ROUTES_API_INTERMEDIATE_MAX,
        useVia: Boolean = true,
    ): RefinedRoute {
        val sampled = samplePolylineWaypoints(
            extPolyline = extPolyline,
            forcedWaypoints = intermediates,
            targetGapMeters = targetGapMeters,
        )
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
        additionalSeedDistances: List<Double> = emptyList(),
        desiredCount: Int = ceil(totalLength / targetGapMeters).toInt().coerceAtLeast(1),
    ): List<Int> {
        val interiorIndices = (1..cumDistances.lastIndex - 1).toList()
        if (interiorIndices.isEmpty() || desiredCount <= 0) return emptyList()

        val seedDistances = mutableListOf(0.0, totalLength)
        seedDistances += additionalSeedDistances
        val pickedIndices = mutableListOf<Int>()
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

    private fun projectForcedWaypoint(
        forced: RoutePoint,
        polyline: List<RoutePoint>,
        cumDistances: DoubleArray,
    ): ForcedProjection {
        var nearestIndex = 0
        var nearestDistance = Double.POSITIVE_INFINITY
        for (index in polyline.indices) {
            val distance = haversineDistanceMeters(forced, polyline[index])
            if (distance < nearestDistance) {
                nearestDistance = distance
                nearestIndex = index
            }
        }
        val headingIndex = nearestIndex.coerceIn(1, polyline.lastIndex - 1)
        return ForcedProjection(
            point = forced,
            cumDist = cumDistances[nearestIndex],
            heading = computeLocalHeading(polyline, headingIndex),
        )
    }

    private fun mergeNearbyPicks(
        picks: List<WaypointPick>,
        thresholdMeters: Double,
    ): List<WaypointPick> {
        val merged = mutableListOf<WaypointPick>()
        for (pick in picks) {
            val last = merged.lastOrNull()
            if (last != null && abs(last.cumDist - pick.cumDist) < thresholdMeters) {
                if (pick.isForced && !last.isForced) {
                    merged[merged.lastIndex] = pick
                }
                continue
            }
            merged += pick
        }
        return merged
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

    /** spec/23 §8.5.2 のユーザ click 点を polyline 上に projection した結果。 */
    private data class ForcedProjection(
        val point: RoutePoint,
        val cumDist: Double,
        val heading: Int,
    )

    /** sort + dedup 用の中間表現。`isForced` で dedup 時の優先度を区別する。 */
    private data class WaypointPick(
        val cumDist: Double,
        val point: RoutePoint,
        val heading: Int?,
        val isForced: Boolean,
    )

    companion object {
        const val DEFAULT_TARGET_GAP_METERS = 4_000.0
        const val ROUTES_API_INTERMEDIATE_MAX = 25

        private const val EARTH_RADIUS_METERS = 6_371_000.0
        private const val BEARING_FULL_TURN = 360
        private const val EARLY_TERMINATION_FRACTION = 0.4
        private const val DEDUP_THRESHOLD_METERS = 5.0
    }
}
