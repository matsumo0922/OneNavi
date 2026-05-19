package me.matsumo.onenavi.core.navigation.newguidance

import kotlinx.collections.immutable.persistentListOf
import me.matsumo.onenavi.core.model.RouteDetail
import me.matsumo.onenavi.core.model.RoutePoint
import me.matsumo.onenavi.core.navigation.newguidance.model.GuidanceState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * [NewGuidanceManager] の state machine テスト。
 */
class NewGuidanceManagerTest {

    private val manager = NewGuidanceManager()

    @Test
    fun `初期状態は Idle`() {
        assertEquals(GuidanceState.Idle, manager.state.value)
    }

    @Test
    fun `startGuidance で Guiding になる`() {
        val route = buildRoute()
        manager.startGuidance(route = route)

        val state = assertIs<GuidanceState.Guiding>(manager.state.value)
        assertEquals(route, state.route)
        assertEquals(route.distanceMeters.toInt(), state.progress.distanceRemainingMeters)
        assertEquals(route.durationSeconds.toInt(), state.progress.durationRemainingSeconds)
        assertEquals(route.origin, state.progress.snappedLocation)
    }

    @Test
    fun `stopGuidance で Idle に戻る`() {
        manager.startGuidance(route = buildRoute())
        manager.stopGuidance()

        assertEquals(GuidanceState.Idle, manager.state.value)
    }

    @Test
    fun `release は二重呼び出しでも例外にならない`() {
        manager.release()
        manager.release()

        assertEquals(GuidanceState.Idle, manager.state.value)
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
}
