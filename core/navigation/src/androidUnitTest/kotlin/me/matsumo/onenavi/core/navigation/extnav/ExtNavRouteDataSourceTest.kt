package me.matsumo.onenavi.core.navigation.extnav

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.test.runTest
import me.matsumo.drive.supporter.api.core.model.Coord
import me.matsumo.drive.supporter.api.guidance.domain.DsrRouteSummary
import me.matsumo.drive.supporter.api.guidance.domain.Intersection
import me.matsumo.drive.supporter.api.guidance.domain.ManeuverDirection
import me.matsumo.drive.supporter.api.guidance.domain.RouteGuidance
import me.matsumo.drive.supporter.api.guidance.domain.StreetSegment
import me.matsumo.drive.supporter.api.route.domain.RouteSearchCriteria
import me.matsumo.onenavi.core.model.RoadClass
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [ExtNavRouteDataSource] の route summary 削減と IC 名 fallback のテスト。
 */
class ExtNavRouteDataSourceTest {

    @Test
    fun `通常検索では priority 別 route summary を呼ばない`() = runTest {
        val backend = FakeRouteDataSourceBackend(
            routeGuidances = persistentListOf(buildInterchangeFallbackGuidance()),
        )
        val dataSource = ExtNavRouteDataSource(
            backend = backend,
            registry = ExtNavRouteRegistry(),
        )

        val routes = dataSource.searchRoutes(
            originLatitude = ROUTE_LATITUDE,
            originLongitude = ROUTE_LONGITUDE,
            destinationLatitude = ROUTE_LATITUDE,
            destinationLongitude = ROUTE_LONGITUDE + LONGITUDE_STEP * 3,
            intermediateWaypoints = emptyList(),
            originDirectionDegrees = null,
        ).getOrThrow()

        assertEquals(1, routes.size)
        assertEquals(1, backend.guidanceCallCount)
        assertEquals(0, backend.routeSearchCallCount)
    }

    @Test
    fun `IC 名は交差点名 fallback から補う`() = runTest {
        val backend = FakeRouteDataSourceBackend(
            routeGuidances = persistentListOf(buildInterchangeFallbackGuidance()),
        )
        val dataSource = ExtNavRouteDataSource(
            backend = backend,
            registry = ExtNavRouteRegistry(),
        )

        val routes = dataSource.searchRoutes(
            originLatitude = ROUTE_LATITUDE,
            originLongitude = ROUTE_LONGITUDE,
            destinationLatitude = ROUTE_LATITUDE,
            destinationLongitude = ROUTE_LONGITUDE + LONGITUDE_STEP * 3,
            intermediateWaypoints = emptyList(),
            originDirectionDegrees = null,
        ).getOrThrow()

        val highwaySegment = routes
            .single()
            .detail
            .roadClassSegments
            .single { segment -> segment.roadClass == RoadClass.HIGHWAY }

        assertEquals(ENTRY_INTERCHANGE_NAME, highwaySegment.entryInterchangeName)
        assertEquals(EXIT_INTERCHANGE_NAME, highwaySegment.exitInterchangeName)
    }

    private fun buildInterchangeFallbackGuidance(): RouteGuidance {
        val polyline = (0..3)
            .map { pointIndex ->
                Coord.fromDegrees(
                    latDeg = ROUTE_LATITUDE,
                    lonDeg = ROUTE_LONGITUDE + LONGITUDE_STEP * pointIndex,
                )
            }
            .toImmutableList()

        return RouteGuidance(
            index = 1,
            priority = null,
            summary = buildRouteSummary(),
            guidancePoints = persistentListOf(),
            intersections = persistentListOf(
                buildInterchangeIntersection(
                    id = 1,
                    name = ENTRY_INTERCHANGE_NAME,
                    longitude = ROUTE_LONGITUDE + LONGITUDE_STEP,
                ),
                buildInterchangeIntersection(
                    id = 2,
                    name = EXIT_INTERCHANGE_NAME,
                    longitude = ROUTE_LONGITUDE + LONGITUDE_STEP * 2,
                ),
            ),
            imageIds = persistentListOf(),
            polyline = polyline,
        )
    }

    private fun buildRouteSummary(): DsrRouteSummary =
        DsrRouteSummary(
            depth = 0,
            distanceMetres = 3_000,
            timeSeconds = 300,
            fuelLitres = 0f,
            tollYen = 320,
            tollDetails = persistentListOf(),
            streets = persistentListOf(
                StreetSegment(
                    sequence = 0,
                    distanceMetres = 1_000,
                    timeSeconds = 100,
                    highway = false,
                    officialName = ORDINARY_ROAD_BEFORE_NAME,
                    nickname = null,
                ),
                StreetSegment(
                    sequence = 1,
                    distanceMetres = 1_000,
                    timeSeconds = 100,
                    highway = true,
                    officialName = HIGHWAY_ROAD_NAME,
                    nickname = null,
                ),
                StreetSegment(
                    sequence = 2,
                    distanceMetres = 1_000,
                    timeSeconds = 100,
                    highway = false,
                    officialName = ORDINARY_ROAD_AFTER_NAME,
                    nickname = null,
                ),
            ),
            priority = 0,
            trafficCongestionAvoidanceRate = 0f,
        )

    private fun buildInterchangeIntersection(
        id: Int,
        name: String,
        longitude: Double,
    ): Intersection =
        Intersection(
            id = id,
            name = name,
            nameRuby = "",
            roadName = "",
            roadNameOfficial = HIGHWAY_ROAD_NAME,
            roadNumberSign = HIGHWAY_ROAD_SIGN,
            directionSignA = "",
            directionSignAKana = "",
            directionSignB = "",
            directionSignBKana = "",
            position = Coord.fromDegrees(
                latDeg = ROUTE_LATITUDE,
                lonDeg = longitude,
            ),
            angleIn = 0,
            angleOut = 0,
            direction = ManeuverDirection.Straight,
            imageRefs = persistentListOf(),
            facilityHint = null,
        )

    /**
     * テスト用の固定値。
     */
    private companion object {
        /** ルート座標の緯度。 */
        private const val ROUTE_LATITUDE: Double = 35.0

        /** ルート開始点の経度。 */
        private const val ROUTE_LONGITUDE: Double = 139.0

        /** テスト用 polyline の経度差分。 */
        private const val LONGITUDE_STEP: Double = 0.01

        /** 高速入口として推定される IC 名。 */
        private const val ENTRY_INTERCHANGE_NAME: String = "入口テストIC"

        /** 高速出口として推定される IC 名。 */
        private const val EXIT_INTERCHANGE_NAME: String = "出口テストIC"

        /** 高速区間の道路名。 */
        private const val HIGHWAY_ROAD_NAME: String = "テスト高速道路"

        /** 高速区間の路線記号。 */
        private const val HIGHWAY_ROAD_SIGN: String = "E99"

        /** 高速入口前の一般道名。 */
        private const val ORDINARY_ROAD_BEFORE_NAME: String = "入口前テスト道路"

        /** 高速出口後の一般道名。 */
        private const val ORDINARY_ROAD_AFTER_NAME: String = "出口後テスト道路"
    }
}

/**
 * [ExtNavRouteDataSourceBackend] の fake。
 */
private class FakeRouteDataSourceBackend(
    private val routeGuidances: ImmutableList<RouteGuidance>,
) : ExtNavRouteDataSourceBackend {

    var guidanceCallCount: Int = 0
        private set

    var routeSearchCallCount: Int = 0
        private set

    override suspend fun ensureSignedIn(): Result<Unit> =
        Result.success(Unit)

    override suspend fun resolveGuidanceRoutes(criteria: RouteSearchCriteria): Result<ImmutableList<RouteGuidance>> {
        guidanceCallCount += 1
        return Result.success(routeGuidances)
    }
}
