package me.matsumo.onenavi.core.navigation.newguidance

import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.test.runTest
import me.matsumo.onenavi.core.model.RoutePoint
import me.matsumo.onenavi.core.navigation.newguidance.model.RoutesApiWaypoint
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [ExtNavRouteRefiner] の単体テスト。
 *
 * Routes API は [FakeRoutesApiClient] に差し替えてサンプリング・chunk 分割・refine の
 * 純粋ロジックだけ検証する。実 HTTP は本テスト対象外。
 */
class ExtNavRouteRefinerTest {

    private val fakeClient = FakeRoutesApiClient()
    private val refiner = ExtNavRouteRefiner(routesApiClient = fakeClient)

    @Test
    fun `頂点 2 点だけの polyline は origin と destination だけを返す`() {
        val polyline = listOf(
            RoutePoint(latitude = 35.0, longitude = 139.0),
            RoutePoint(latitude = 36.0, longitude = 140.0),
        )

        val sampled = refiner.samplePolylineWaypoints(polyline)

        assertEquals(2, sampled.size)
        assertEquals(polyline[0], sampled[0].point)
        assertEquals(polyline[1], sampled[1].point)
        assertNull(sampled[0].heading, "origin に heading は付かない")
        assertNull(sampled.last().heading, "destination に heading は付かない")
    }

    @Test
    fun `長い直線 polyline では FPS で間引いた中間 waypoint が出る`() {
        // 0.01 度 (約 1.1km) おきに 30 点並べた東向き直線
        val polyline = (0..29).map { step ->
            RoutePoint(latitude = 35.0, longitude = 139.0 + 0.01 * step)
        }

        val sampled = refiner.samplePolylineWaypoints(polyline, targetGapMeters = 4_000.0)

        assertTrue(
            sampled.size in 3..10,
            "expected modest number of waypoints, got ${sampled.size}",
        )
        assertEquals(polyline.first(), sampled.first().point, "先頭は origin")
        assertEquals(polyline.last(), sampled.last().point, "末尾は destination")
        sampled.drop(1).dropLast(1).forEach { waypoint ->
            val heading = assertNotNull(waypoint.heading, "中間 waypoint には heading が付く")
            assertTrue(
                heading in 0 until 360,
                "compass bearing 範囲: actual=$heading",
            )
        }
    }

    @Test
    fun `forcedWaypoints は polyline 上に projection されて waypoint 列に必ず混入する`() {
        val polyline = (0..29).map { step ->
            RoutePoint(latitude = 35.0, longitude = 139.0 + 0.01 * step)
        }
        // polyline 上の真ん中近辺の座標 (頂点 15 と完全一致しないが最近傍頂点 15 に projection される)
        val forced = RoutePoint(latitude = 35.0001, longitude = 139.0 + 0.01 * 15 + 0.0002)

        val sampled = refiner.samplePolylineWaypoints(
            extPolyline = polyline,
            forcedWaypoints = listOf(forced),
            targetGapMeters = 4_000.0,
        )

        // 強制注入された waypoint は座標そのもの (polyline 頂点ではない) で含まれる
        val forcedInWaypoints = sampled.firstOrNull { it.point == forced }
        assertNotNull(forcedInWaypoints, "forced waypoint must appear in sampled list")
        assertNotNull(forcedInWaypoints.heading, "forced waypoint には projection 接線 heading が付く")
        assertEquals(polyline.first(), sampled.first().point, "先頭は origin")
        assertEquals(polyline.last(), sampled.last().point, "末尾は destination")
    }

    @Test
    fun `forcedWaypoints が空なら従来挙動と同じ`() {
        val polyline = (0..29).map { step ->
            RoutePoint(latitude = 35.0, longitude = 139.0 + 0.01 * step)
        }

        val withoutForced = refiner.samplePolylineWaypoints(polyline, targetGapMeters = 4_000.0)
        val emptyForced = refiner.samplePolylineWaypoints(
            extPolyline = polyline,
            forcedWaypoints = emptyList(),
            targetGapMeters = 4_000.0,
        )

        assertEquals(withoutForced.size, emptyForced.size)
        withoutForced.zip(emptyForced).forEach { (lhs, rhs) ->
            assertEquals(lhs.point, rhs.point)
            assertEquals(lhs.heading, rhs.heading)
        }
    }

    @Test
    fun `chunkWaypoints は intermediateMax+1 ステップで境界を共有する`() {
        // 60 点 (origin + 58 中間 + destination) → intermediateMax=25 で 3 chunk
        val waypoints = (0 until 60).map { index ->
            RoutesApiWaypoint(point = RoutePoint(latitude = index.toDouble(), longitude = 0.0))
        }

        val chunks = refiner.chunkWaypoints(waypoints, intermediateMax = 25)

        assertEquals(3, chunks.size)
        // chunk0: indices 0..26 (27 entries)
        assertEquals(27, chunks[0].size)
        assertEquals(waypoints[0], chunks[0].first())
        assertEquals(waypoints[26], chunks[0].last())
        // chunk1: indices 26..52 (27 entries) — 始点は chunk0 の終点と一致
        assertEquals(27, chunks[1].size)
        assertEquals(waypoints[26], chunks[1].first())
        assertEquals(waypoints[52], chunks[1].last())
        // chunk2: indices 52..59 (8 entries)
        assertEquals(8, chunks[2].size)
        assertEquals(waypoints[52], chunks[2].first())
        assertEquals(waypoints[59], chunks[2].last())
    }

    @Test
    fun `refine は polyline を chunk 分割し RefinedRoute を組み立てる`() = runTest {
        val polyline = (0..30).map { step ->
            RoutePoint(latitude = 35.0, longitude = 139.0 + 0.01 * step)
        }
        val origin = polyline.first()
        val destination = polyline.last()

        val refined = refiner.refine(
            extPolyline = polyline,
            origin = origin,
            destination = destination,
            targetGapMeters = 4_000.0,
        )

        assertEquals(origin, refined.origin)
        assertEquals(destination, refined.destination)
        assertTrue(refined.chunks.isNotEmpty())
        refined.chunks.forEach { chunk ->
            assertTrue(chunk.routeToken.startsWith("fake-token"))
            assertTrue(chunk.polyline.isNotEmpty())
            assertTrue(chunk.distanceMeters > 0)
        }
        // mergedPolyline は chunk0..n-1 の末尾を落として連結された結果
        val expectedPolylineSize = refined.chunks.sumOf { it.polyline.size } -
            (refined.chunks.size - 1)
        assertEquals(expectedPolylineSize, refined.mergedPolyline.size)
    }

    @Test
    fun `computeChunkedRoute は useVia フラグを intermediates だけに伝える`() = runTest {
        val chunk = listOf(
            RoutesApiWaypoint(point = RoutePoint(35.0, 139.0)),
            RoutesApiWaypoint(point = RoutePoint(35.5, 139.5), heading = 45),
            RoutesApiWaypoint(point = RoutePoint(36.0, 140.0)),
        )

        refiner.computeChunkedRoute(chunks = listOf(chunk), useVia = true)

        val recordedCalls = fakeClient.calls
        assertEquals(1, recordedCalls.size)
        assertContentEquals(chunk, recordedCalls.first().chunk)
        assertEquals(true, recordedCalls.first().useVia)
    }
}

/**
 * [RoutesApiClient] の決定的 fake。受信した chunk を順に記録し、固定の polyline / token を返す。
 */
private class FakeRoutesApiClient : RoutesApiClient {

    data class Recorded(
        val chunk: List<RoutesApiWaypoint>,
        val useVia: Boolean,
    )

    val calls: MutableList<Recorded> = mutableListOf()

    override suspend fun computeRoute(
        chunk: List<RoutesApiWaypoint>,
        useVia: Boolean,
    ): Result<RoutesApiResponse> {
        if (chunk.size < 2) {
            return Result.failure(IllegalArgumentException("chunk too small"))
        }
        calls += Recorded(chunk = chunk.toImmutableList(), useVia = useVia)
        return Result.success(
            RoutesApiResponse(
                polyline = chunk.map { it.point },
                routeToken = "fake-token-${calls.size}",
                distanceMeters = chunk.size * 1_000,
                durationSeconds = chunk.size * 60L,
            ),
        )
    }
}
