package me.matsumo.onenavi.feature.map.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 自車追従の viewport アンカー計算を検証するテスト。 */
class MapCameraViewportAnchorTest {

    @Test
    fun `右パネル分の padding がある場合は左側地図領域の中心をアンカーにする`() {
        val anchorPoint = MapCameraViewportAnchor.resolveAnchorPoint(
            viewportWidthPx = 2400,
            viewportHeightPx = 1080,
            startPaddingPx = 24,
            endPaddingPx = 824,
            topPaddingPx = 0,
            bottomPaddingPx = 0,
        )

        assertEquals(800f, anchorPoint.xPx)
        assertEquals(540f, anchorPoint.yPx)
    }

    @Test
    fun `案内用 top padding がある場合は下寄せアンカーを返す`() {
        val anchorPoint = MapCameraViewportAnchor.resolveAnchorPoint(
            viewportWidthPx = 2400,
            viewportHeightPx = 1080,
            startPaddingPx = 24,
            endPaddingPx = 824,
            topPaddingPx = 540,
            bottomPaddingPx = 0,
        )

        assertEquals(800f, anchorPoint.xPx)
        assertEquals(810f, anchorPoint.yPx)
    }

    @Test
    fun `projection 上の自車位置からアンカーへの scroll 差分を返す`() {
        val scrollDelta = MapCameraViewportAnchor.resolveScrollDelta(
            vehicleScreenPoint = MapCameraScreenPoint(
                xPx = 1200f,
                yPx = 540f,
            ),
            anchorPoint = MapCameraScreenPoint(
                xPx = 800f,
                yPx = 810f,
            ),
        )

        assertEquals(400f, scrollDelta.xPx)
        assertEquals(-270f, scrollDelta.yPx)
        assertTrue(scrollDelta.shouldApply)
    }

    @Test
    fun `小さすぎる scroll 差分は適用しない`() {
        val scrollDelta = MapCameraViewportAnchor.resolveScrollDelta(
            vehicleScreenPoint = MapCameraScreenPoint(
                xPx = 800.25f,
                yPx = 810.25f,
            ),
            anchorPoint = MapCameraScreenPoint(
                xPx = 800f,
                yPx = 810f,
            ),
        )

        assertFalse(scrollDelta.shouldApply)
    }

    @Test
    fun `自車がアンカー許容範囲を超えた場合は離脱扱いにする`() {
        val isAwayFromAnchor = MapCameraViewportAnchor.isVehicleAwayFromAnchor(
            vehicleScreenPoint = MapCameraScreenPoint(
                xPx = 800f,
                yPx = 700f,
            ),
            anchorPoint = MapCameraScreenPoint(
                xPx = 800f,
                yPx = 810f,
            ),
            viewportHeightPx = 900,
        )

        assertTrue(isAwayFromAnchor)
    }

    @Test
    fun `自車がアンカー許容範囲内なら追従維持扱いにする`() {
        val isAwayFromAnchor = MapCameraViewportAnchor.isVehicleAwayFromAnchor(
            vehicleScreenPoint = MapCameraScreenPoint(
                xPx = 800f,
                yPx = 730f,
            ),
            anchorPoint = MapCameraScreenPoint(
                xPx = 800f,
                yPx = 810f,
            ),
            viewportHeightPx = 900,
        )

        assertFalse(isAwayFromAnchor)
    }
}
