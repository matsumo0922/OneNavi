package me.matsumo.onenavi.core.common.car

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
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

        val command = coordinator.phoneCommands.value[OneNaviDisplaySurface.Phone]

        assertEquals(1L, commandId)
        assertEquals(1L, command?.id)
        assertIs<CarPhoneSessionCommand.OpenDestinationSearch>(command?.command)

        coordinator.consumePhoneCommand(OneNaviDisplaySurface.Phone, 1L)

        assertTrue(coordinator.phoneCommands.value.isEmpty())
    }

    @Test
    fun requestPhoneAddWaypointSearchPublishesConsumableCommand() {
        val coordinator = CarPhoneSessionCoordinator()

        val commandId = coordinator.requestPhoneAddWaypointSearch()

        val command = coordinator.phoneCommands.value[OneNaviDisplaySurface.Phone]

        assertEquals(1L, commandId)
        assertEquals(1L, command?.id)
        assertIs<CarPhoneSessionCommand.OpenAddWaypointSearch>(command?.command)
    }

    @Test
    fun consumePhoneCommandIgnoresDifferentCommandId() {
        val coordinator = CarPhoneSessionCoordinator()

        coordinator.requestPhoneDestinationSearch()
        coordinator.consumePhoneCommand(OneNaviDisplaySurface.Phone, 2L)

        assertEquals(1L, coordinator.phoneCommands.value[OneNaviDisplaySurface.Phone]?.id)
    }

    @Test
    fun assistantCommandDoesNotOverwriteOtherSurfaceSlot() {
        val coordinator = CarPhoneSessionCoordinator()

        val phoneCommandId = coordinator.requestAssistantNavigation(
            request = AssistantNavRequest.Search(query = "cafe"),
            targetSurface = OneNaviDisplaySurface.Phone,
        )
        val carCommandId = coordinator.requestAssistantNavigation(
            request = AssistantNavRequest.Navigate(
                query = "station",
                coordinate = null,
            ),
            targetSurface = OneNaviDisplaySurface.AndroidAutoVirtualDisplay,
        )

        val commands = coordinator.phoneCommands.value
        val phoneCommand = commands[OneNaviDisplaySurface.Phone]
        val carCommand = commands[OneNaviDisplaySurface.AndroidAutoVirtualDisplay]

        assertEquals(phoneCommandId, phoneCommand?.id)
        assertEquals(carCommandId, carCommand?.id)
        assertIs<CarPhoneSessionCommand.SearchPlaces>(phoneCommand?.command)
        assertIs<CarPhoneSessionCommand.NavigateTo>(carCommand?.command)
    }

    @Test
    fun sameSurfaceAssistantCommandUsesLatestWins() {
        val coordinator = CarPhoneSessionCoordinator()

        coordinator.requestAssistantNavigation(
            request = AssistantNavRequest.Search(query = "cafe"),
            targetSurface = OneNaviDisplaySurface.Phone,
        )
        val latestCommandId = coordinator.requestAssistantNavigation(
            request = AssistantNavRequest.Search(query = "park"),
            targetSurface = OneNaviDisplaySurface.Phone,
        )

        val command = coordinator.phoneCommands.value[OneNaviDisplaySurface.Phone]
        val searchPlaces = assertIs<CarPhoneSessionCommand.SearchPlaces>(command?.command)

        assertEquals(latestCommandId, command?.id)
        assertEquals("park", searchPlaces.query)
    }

    @Test
    fun consumePhoneCommandClearsOnlyMatchingSurfaceSlot() {
        val coordinator = CarPhoneSessionCoordinator()

        coordinator.requestAssistantNavigation(
            request = AssistantNavRequest.Search(query = "cafe"),
            targetSurface = OneNaviDisplaySurface.Phone,
        )
        val carCommandId = coordinator.requestAssistantNavigation(
            request = AssistantNavRequest.Search(query = "station"),
            targetSurface = OneNaviDisplaySurface.AndroidAutoVirtualDisplay,
        )

        coordinator.consumePhoneCommand(OneNaviDisplaySurface.AndroidAutoVirtualDisplay, carCommandId)

        val commands = coordinator.phoneCommands.value

        assertTrue(OneNaviDisplaySurface.Phone in commands)
        assertFalse(OneNaviDisplaySurface.AndroidAutoVirtualDisplay in commands)
    }
}
