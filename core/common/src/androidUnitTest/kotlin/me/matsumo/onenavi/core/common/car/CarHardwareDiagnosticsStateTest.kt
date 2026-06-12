package me.matsumo.onenavi.core.common.car

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** 車両ハードウェア診断状態ホルダーの単体テスト。 */
class CarHardwareDiagnosticsStateTest {

    @AfterTest
    fun tearDown() {
        CarHardwareDiagnosticsState.reset()
    }

    @Test
    fun updateSnapshotPublishesLatestDiagnostics() {
        val snapshot = CarHardwareDiagnosticsSnapshot(
            connectionStatus = CarHardwareConnectionStatus.CONNECTED,
            message = "connected",
        )

        CarHardwareDiagnosticsState.updateSnapshot(snapshot)

        assertEquals(snapshot, CarHardwareDiagnosticsState.snapshot.value)
    }

    @Test
    fun resetRestoresDisconnectedDiagnostics() {
        CarHardwareDiagnosticsState.updateSnapshot(
            CarHardwareDiagnosticsSnapshot(
                connectionStatus = CarHardwareConnectionStatus.CONNECTED,
                message = "connected",
            ),
        )

        CarHardwareDiagnosticsState.reset()

        assertEquals(CarHardwareDiagnosticsSnapshot(), CarHardwareDiagnosticsState.snapshot.value)
    }
}
