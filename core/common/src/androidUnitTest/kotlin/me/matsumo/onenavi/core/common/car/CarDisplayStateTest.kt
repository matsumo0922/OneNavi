package me.matsumo.onenavi.core.common.car

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 車載表示状態フラグの回帰テスト。 */
class CarDisplayStateTest {

    @AfterTest
    fun tearDown() {
        while (CarDisplayState.isOnCar) {
            CarDisplayState.unregisterCarDisplay()
        }
    }

    @Test
    fun isOnCarIsFalseByDefault() {
        assertFalse(CarDisplayState.isOnCar)
    }

    @Test
    fun registerCarDisplayMarksCarDisplayActive() {
        CarDisplayState.registerCarDisplay()

        assertTrue(CarDisplayState.isOnCar)
    }

    @Test
    fun unregisterCarDisplayKeepsActiveUntilAllEntryPointsLeave() {
        CarDisplayState.registerCarDisplay()
        CarDisplayState.registerCarDisplay()

        CarDisplayState.unregisterCarDisplay()

        assertTrue(CarDisplayState.isOnCar)

        CarDisplayState.unregisterCarDisplay()

        assertFalse(CarDisplayState.isOnCar)
    }

    @Test
    fun unregisterCarDisplayKeepsInactiveStateWhenNoEntryPointIsActive() {
        CarDisplayState.unregisterCarDisplay()

        assertFalse(CarDisplayState.isOnCar)
    }
}
