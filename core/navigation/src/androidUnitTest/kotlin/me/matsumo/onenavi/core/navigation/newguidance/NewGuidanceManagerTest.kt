package me.matsumo.onenavi.core.navigation.newguidance

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.matsumo.drive.supporter.api.guidance.domain.DsrRouteSummary
import me.matsumo.drive.supporter.api.guidance.domain.RouteGuidance
import me.matsumo.onenavi.core.datasource.location.UserLocation
import me.matsumo.onenavi.core.model.RoadClass
import me.matsumo.onenavi.core.model.RoadClassSegment
import me.matsumo.onenavi.core.model.RouteDetail
import me.matsumo.onenavi.core.model.RoutePoint
import me.matsumo.onenavi.core.navigation.extnav.ExtNavGuidanceTracker
import me.matsumo.onenavi.core.navigation.extnav.ExtNavRoutePayload
import me.matsumo.onenavi.core.navigation.extnav.ExtNavRouteRegistry
import me.matsumo.onenavi.core.navigation.newguidance.model.GuidanceEvent
import me.matsumo.onenavi.core.navigation.newguidance.model.GuidanceState
import me.matsumo.onenavi.core.navigation.newguidance.model.VehiclePositionSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * [NewGuidanceManager] の state machine テスト。
 */
class NewGuidanceManagerTest {

    @Test
    fun `初期状態は Idle`() {
        val manager = NewGuidanceManager()

        assertEquals(GuidanceState.Idle, manager.state.value)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `startGuidance で Guiding になる`() = runTest {
        val manager = NewGuidanceManager(scope = this)
        val route = buildRoute()
        manager.startGuidance(route = route)
        runCurrent()

        val state = assertIs<GuidanceState.Guiding>(manager.state.value)
        assertEquals(route, state.route)
        assertEquals(route.distanceMeters.toInt(), state.progress.distanceRemainingMeters)
        assertEquals(route.durationSeconds.toInt(), state.progress.durationRemainingSeconds)
        assertEquals(route.origin, state.progress.snappedLocation)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `stopGuidance で Idle に戻る`() = runTest {
        val manager = NewGuidanceManager(scope = this)
        manager.startGuidance(route = buildRoute())
        runCurrent()
        manager.stopGuidance()

        assertEquals(GuidanceState.Idle, manager.state.value)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `stopGuidance で停止イベントを通知する`() = runTest {
        val manager = NewGuidanceManager(scope = this)
        val events = mutableListOf<GuidanceEvent>()
        val collectJob = launch {
            manager.events
                .take(1)
                .toList(events)
        }
        runCurrent()

        manager.startGuidance(route = buildRoute())
        manager.stopGuidance()
        collectJob.join()

        assertEquals(listOf<GuidanceEvent>(GuidanceEvent.Stopped), events)
    }

    @Test
    fun `release は二重呼び出しでも例外にならない`() {
        val manager = NewGuidanceManager()

        manager.release()
        manager.release()

        assertEquals(GuidanceState.Idle, manager.state.value)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `DR snapshot だけでは目的地到着と経由地通過を確定しない`() = runTest {
        val route = buildShortRouteWithWaypoint()
        val routeRegistry = ExtNavRouteRegistry()
        val tracker = ExtNavGuidanceTracker()
        routeRegistry.put(
            ExtNavRoutePayload(
                id = route.id,
                routeGuidance = buildRouteGuidance(),
            ),
        )
        val manager = NewGuidanceManager(
            routeRegistry = routeRegistry,
            guidanceTracker = tracker,
            scope = this,
        )
        val events = mutableListOf<GuidanceEvent>()
        val eventJob = launch {
            manager.events
                .take(1)
                .toList(events)
        }

        manager.startGuidance(route = route)
        runCurrent()
        tracker.onLocation(locationAt(route.origin, speedMps = 30f))
        runCurrent()
        tracker.advanceDeadReckoning(
            nowElapsedRealtimeNanos = 10L * NANOS_PER_SECOND,
            nowWallClockMillis = 10L * MILLIS_PER_SECOND,
        )
        runCurrent()

        val state = assertIs<GuidanceState.Guiding>(manager.state.value)
        assertEquals(route.intermediateWaypoints, state.route.intermediateWaypoints)
        assertEquals(VehiclePositionSource.DEAD_RECKONING, state.progress.positionSource)
        assertEquals(emptyList(), events)

        eventJob.cancel()
        manager.stopGuidance()
        runCurrent()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `初期進捗の currentRoadClass は route geometry の先頭 roadClassSegments から決まる`() = runTest {
        val route = buildHighwayRoute()
        val manager = NewGuidanceManager(scope = this)

        manager.startGuidance(route = route)
        runCurrent()

        val state = assertIs<GuidanceState.Guiding>(manager.state.value)
        assertEquals(RoadClass.HIGHWAY, state.progress.currentRoadClass)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `案内中に位置 tick を投入しても currentRoadClass は route geometry 由来のまま維持される`() = runTest {
        val route = buildHighwayRoute()
        val routeRegistry = ExtNavRouteRegistry()
        val tracker = ExtNavGuidanceTracker()
        routeRegistry.put(
            ExtNavRoutePayload(
                id = route.id,
                routeGuidance = buildRouteGuidance(),
            ),
        )
        val manager = NewGuidanceManager(
            routeRegistry = routeRegistry,
            guidanceTracker = tracker,
            scope = this,
        )

        manager.startGuidance(route = route)
        runCurrent()
        tracker.onLocation(locationAt(route.origin, speedMps = 30f))
        runCurrent()

        val state = assertIs<GuidanceState.Guiding>(manager.state.value)
        // 道路種別 API polling が行われていないため、route geometry 由来の HIGHWAY がそのまま維持される。
        assertEquals(RoadClass.HIGHWAY, state.progress.currentRoadClass)

        manager.stopGuidance()
        runCurrent()
    }

    private fun buildRoute(): RouteDetail {
        val origin = RoutePoint(latitude = 35.0, longitude = 139.0)
        val destination = RoutePoint(latitude = 35.5, longitude = 139.5)
        return RouteDetail(
            id = "route-0",
            origin = origin,
            destination = destination,
            intermediateWaypoints = persistentListOf(),
            geometry = persistentListOf(origin, destination),
            distanceMeters = 5_000.0,
            durationSeconds = 600.0,
            steps = persistentListOf(),
        )
    }

    private fun buildHighwayRoute(): RouteDetail {
        val origin = RoutePoint(latitude = 35.0, longitude = 139.0)
        val destination = RoutePoint(latitude = 35.5, longitude = 139.5)
        val geometry = persistentListOf(origin, destination)
        return RouteDetail(
            id = "route-highway",
            origin = origin,
            destination = destination,
            intermediateWaypoints = persistentListOf(),
            geometry = geometry,
            distanceMeters = 5_000.0,
            durationSeconds = 600.0,
            steps = persistentListOf(),
            roadClassSegments = persistentListOf(
                RoadClassSegment(
                    startPointIndex = 0,
                    endPointIndex = geometry.lastIndex,
                    roadClass = RoadClass.HIGHWAY,
                ),
            ),
        )
    }

    private fun buildShortRouteWithWaypoint(): RouteDetail {
        val origin = RoutePoint(latitude = 35.0, longitude = 139.0)
        val waypoint = RoutePoint(latitude = 35.0, longitude = 139.0005)
        val destination = RoutePoint(latitude = 35.0, longitude = 139.001)
        return RouteDetail(
            id = "route-short",
            origin = origin,
            destination = destination,
            intermediateWaypoints = persistentListOf(waypoint),
            geometry = listOf(origin, waypoint, destination).toImmutableList(),
            distanceMeters = 100.0,
            durationSeconds = 30.0,
            steps = persistentListOf(),
        )
    }

    private fun buildRouteGuidance(): RouteGuidance = RouteGuidance(
        index = 1,
        priority = null,
        summary = DsrRouteSummary(
            depth = 0,
            distanceMetres = 100,
            timeSeconds = 30,
            fuelLitres = 0f,
            tollYen = 0,
            tollDetails = persistentListOf(),
            streets = persistentListOf(),
            priority = 0,
            trafficCongestionAvoidanceRate = 0f,
        ),
        guidancePoints = persistentListOf(),
        intersections = persistentListOf(),
        imageIds = persistentListOf(),
        polyline = persistentListOf(),
        speedLimitSegments = persistentListOf(),
    )

    private fun locationAt(point: RoutePoint, speedMps: Float): UserLocation = UserLocation(
        latitude = point.latitude,
        longitude = point.longitude,
        bearingDegrees = null,
        speedMps = speedMps,
        accuracyMeters = 3f,
        timestampMillis = 0L,
        elapsedRealtimeNanos = 0L,
    )

    private companion object {

        /** 1 秒をミリ秒へ変換する係数。 */
        private const val MILLIS_PER_SECOND = 1_000L

        /** 1 秒をナノ秒へ変換する係数。 */
        private const val NANOS_PER_SECOND = 1_000_000_000L
    }
}
