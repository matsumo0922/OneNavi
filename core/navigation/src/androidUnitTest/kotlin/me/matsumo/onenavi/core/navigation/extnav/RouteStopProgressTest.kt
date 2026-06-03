package me.matsumo.onenavi.core.navigation.extnav

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import me.matsumo.onenavi.core.model.RouteDetail
import me.matsumo.onenavi.core.model.RoutePoint
import me.matsumo.onenavi.core.navigation.newguidance.model.RouteMatchState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [RouteStopProgress] の判定テスト。
 */
class RouteStopProgressTest {

    @Test
    fun `経由地手前では経由地を残す`() {
        val route = buildRoute()
        val cumulativeMeters = RouteGeometryMath.cumulativeMetres(route.geometry)
        val currentMeters = cumulativeMeters[FIRST_WAYPOINT_GEOMETRY_INDEX] - WAYPOINT_MARGIN_METERS - 1.0

        val remainingWaypoints = RouteStopProgress.remainingIntermediateWaypoints(
            route = route,
            currentCumulativeMeters = currentMeters,
            marginMeters = WAYPOINT_MARGIN_METERS,
        )

        assertEquals(route.intermediateWaypoints, remainingWaypoints)
    }

    @Test
    fun `経由地付近では到達した経由地だけ削除する`() {
        val route = buildRoute()
        val cumulativeMeters = RouteGeometryMath.cumulativeMetres(route.geometry)
        val currentMeters = cumulativeMeters[FIRST_WAYPOINT_GEOMETRY_INDEX]

        val remainingWaypoints = RouteStopProgress.remainingIntermediateWaypoints(
            route = route,
            currentCumulativeMeters = currentMeters,
            marginMeters = WAYPOINT_MARGIN_METERS,
        )

        assertEquals(persistentListOf(route.intermediateWaypoints[1]), remainingWaypoints)
    }

    @Test
    fun `複数経由地を越えた場合は通過済み経由地をまとめて削除する`() {
        val route = buildRoute()
        val cumulativeMeters = RouteGeometryMath.cumulativeMetres(route.geometry)
        val currentMeters = cumulativeMeters[SECOND_WAYPOINT_GEOMETRY_INDEX]

        val remainingWaypoints = RouteStopProgress.remainingIntermediateWaypoints(
            route = route,
            currentCumulativeMeters = currentMeters,
            marginMeters = WAYPOINT_MARGIN_METERS,
        )

        assertEquals(persistentListOf(), remainingWaypoints)
    }

    @Test
    fun `目的地付近で route 上なら目的地到達と判定する`() {
        val isReached = RouteStopProgress.isDestinationReached(
            distanceRemainingMeters = DESTINATION_REMAINING_METERS,
            routeMatchState = RouteMatchState.ON_ROUTE,
            thresholdMeters = DESTINATION_THRESHOLD_METERS,
        )

        assertTrue(isReached)
    }

    @Test
    fun `目的地付近でも route 逸脱確定なら目的地到達にしない`() {
        val isReached = RouteStopProgress.isDestinationReached(
            distanceRemainingMeters = DESTINATION_REMAINING_METERS,
            routeMatchState = RouteMatchState.OFF_ROUTE_CONFIRMED,
            thresholdMeters = DESTINATION_THRESHOLD_METERS,
        )

        assertFalse(isReached)
    }

    private fun buildRoute(): RouteDetail {
        val origin = RoutePoint(latitude = ORIGIN_LATITUDE, longitude = ORIGIN_LONGITUDE)
        val firstWaypoint = RoutePoint(latitude = ORIGIN_LATITUDE, longitude = ORIGIN_LONGITUDE + LONGITUDE_STEP)
        val secondWaypoint = RoutePoint(latitude = ORIGIN_LATITUDE, longitude = ORIGIN_LONGITUDE + LONGITUDE_STEP * 2)
        val destination = RoutePoint(latitude = ORIGIN_LATITUDE, longitude = ORIGIN_LONGITUDE + LONGITUDE_STEP * 3)
        return RouteDetail(
            id = "route-stop-progress-test",
            origin = origin,
            destination = destination,
            intermediateWaypoints = persistentListOf(firstWaypoint, secondWaypoint),
            geometry = listOf(origin, firstWaypoint, secondWaypoint, destination).toImmutableList(),
            distanceMeters = 300.0,
            durationSeconds = 180.0,
            steps = persistentListOf(),
        )
    }

    /** テスト用の固定値。 */
    private companion object {

        /** 経由地到達判定の余裕距離。 */
        private const val WAYPOINT_MARGIN_METERS: Double = 30.0

        /** 目的地到達判定の閾値。 */
        private const val DESTINATION_THRESHOLD_METERS: Double = 30.0

        /** 目的地付近の残距離。 */
        private const val DESTINATION_REMAINING_METERS: Double = 20.0

        /** テストルートの始点緯度。 */
        private const val ORIGIN_LATITUDE: Double = 35.0

        /** テストルートの始点経度。 */
        private const val ORIGIN_LONGITUDE: Double = 139.0

        /** テストルートの経度方向ステップ。 */
        private const val LONGITUDE_STEP: Double = 0.001

        /** 1 つ目の経由地に対応する geometry index。 */
        private const val FIRST_WAYPOINT_GEOMETRY_INDEX: Int = 1

        /** 2 つ目の経由地に対応する geometry index。 */
        private const val SECOND_WAYPOINT_GEOMETRY_INDEX: Int = 2
    }
}
