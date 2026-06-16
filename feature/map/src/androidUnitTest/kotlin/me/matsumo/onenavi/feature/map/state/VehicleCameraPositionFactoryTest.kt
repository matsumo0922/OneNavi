package me.matsumo.onenavi.feature.map.state

import kotlin.test.Test
import kotlin.test.assertEquals

/** 自車追従カメラ位置 factory の設定反映を検証するテスト。 */
class VehicleCameraPositionFactoryTest {

    @Test
    fun `tilted perspective uses configured camera degrees`() {
        val factory = VehicleCameraPositionFactory()

        factory.updateTiltedCameraDegrees(55f)

        assertEquals(55f, factory.vehicleTiltDegrees(MapCameraPerspective.TILTED))
        assertEquals(0f, factory.vehicleTiltDegrees(MapCameraPerspective.TOP_DOWN_NORTH_UP))
    }
}
