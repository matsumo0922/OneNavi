package me.matsumo.onenavi.core.common.car

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [CarPhoneSessionCoordinator] の状態同期テスト。 */
class CarPhoneSessionCoordinatorTest {

    @Test
    fun registerSurfaceMarksSurfaceActive() {
        val coordinator = CarPhoneSessionCoordinator()

        coordinator.registerSurface(OneNaviDisplaySurface.AndroidAutoVirtualDisplay)

        assertTrue(coordinator.state.value.isAndroidAutoVirtualDisplayActive)
        assertFalse(coordinator.state.value.isPhoneActive)
    }

    @Test
    fun unregisterSurfaceKeepsSurfaceActiveUntilAllRegistrationsLeave() {
        val coordinator = CarPhoneSessionCoordinator()

        coordinator.registerSurface(OneNaviDisplaySurface.Phone)
        coordinator.registerSurface(OneNaviDisplaySurface.Phone)

        coordinator.unregisterSurface(OneNaviDisplaySurface.Phone)

        assertTrue(coordinator.state.value.isPhoneActive)

        coordinator.unregisterSurface(OneNaviDisplaySurface.Phone)

        assertFalse(coordinator.state.value.isPhoneActive)
    }

    @Test
    fun requestPhoneDestinationSearchPublishesConsumableCommand() {
        val coordinator = CarPhoneSessionCoordinator()

        val commandId = coordinator.requestPhoneDestinationSearch()

        val command = coordinator.phoneCommand.value

        assertEquals(1L, commandId)
        assertEquals(1L, command?.id)
        assertIs<CarPhoneSessionCommand.OpenDestinationSearch>(command?.command)

        coordinator.consumePhoneCommand(1L)

        assertNull(coordinator.phoneCommand.value)
    }

    @Test
    fun requestPhoneAddWaypointSearchPublishesConsumableCommand() {
        val coordinator = CarPhoneSessionCoordinator()

        val commandId = coordinator.requestPhoneAddWaypointSearch()

        val command = coordinator.phoneCommand.value

        assertEquals(1L, commandId)
        assertEquals(1L, command?.id)
        assertIs<CarPhoneSessionCommand.OpenAddWaypointSearch>(command?.command)
    }

    @Test
    fun consumePhoneCommandIgnoresDifferentCommandId() {
        val coordinator = CarPhoneSessionCoordinator()

        coordinator.requestPhoneDestinationSearch()
        coordinator.consumePhoneCommand(2L)

        assertEquals(1L, coordinator.phoneCommand.value?.id)
    }
}
