package me.matsumo.onenavi.feature.map.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 案内中のカメラ padding 計算を検証するテスト。 */
class GuidanceCameraPaddingTest {

    @Test
    fun `案内中追従では padded 中心がカード上端から margin 上に来る上 padding を返す`() {
        val mapViewHeightPx = 2400
        val rawBottomPaddingPx = 480
        val density = 2.5f
        val marginPx = (VEHICLE_ANCHOR_MARGIN_FROM_BOTTOM_DP * density).toInt()

        val topPaddingPx = GuidanceCameraPadding.resolveTopPaddingPx(
            isGuidanceFollowActive = true,
            mapViewHeightPx = mapViewHeightPx,
            rawTopPaddingPx = 120,
            rawBottomPaddingPx = rawBottomPaddingPx,
            density = density,
        )

        // top = H - bottom - 2*margin
        assertEquals(mapViewHeightPx - rawBottomPaddingPx - 2 * marginPx, topPaddingPx)

        // padded 中心 = (H + top - bottom) / 2 が「カード上端(H - bottom) - margin」に一致する
        val paddedCenterY = (mapViewHeightPx + topPaddingPx - rawBottomPaddingPx) / 2
        val cardTopY = mapViewHeightPx - rawBottomPaddingPx
        assertEquals(cardTopY - marginPx, paddedCenterY)
    }

    @Test
    fun `案内中追従でない場合は実 top padding をそのまま返す`() {
        val rawTopPaddingPx = 132

        val topPaddingPx = GuidanceCameraPadding.resolveTopPaddingPx(
            isGuidanceFollowActive = false,
            mapViewHeightPx = 2400,
            rawTopPaddingPx = rawTopPaddingPx,
            rawBottomPaddingPx = 480,
            density = 2.5f,
        )

        assertEquals(rawTopPaddingPx, topPaddingPx)
    }

    @Test
    fun `地図ビューの高さが未確定なら案内中でも実 top padding を返す`() {
        val rawTopPaddingPx = 96

        val topPaddingPx = GuidanceCameraPadding.resolveTopPaddingPx(
            isGuidanceFollowActive = true,
            mapViewHeightPx = 0,
            rawTopPaddingPx = rawTopPaddingPx,
            rawBottomPaddingPx = 480,
            density = 2.5f,
        )

        assertEquals(rawTopPaddingPx, topPaddingPx)
    }

    @Test
    fun `下 padding と margin が高さを超える場合は 0 にクランプする`() {
        val topPaddingPx = GuidanceCameraPadding.resolveTopPaddingPx(
            isGuidanceFollowActive = true,
            mapViewHeightPx = 100,
            rawTopPaddingPx = 48,
            rawBottomPaddingPx = 200,
            density = 2f,
        )

        assertEquals(0, topPaddingPx)
    }

    @Test
    fun `上部パネル相当の実 top padding が変わっても案内中の上 padding は変化しない`() {
        val mapViewHeightPx = 2400
        val rawBottomPaddingPx = 480
        val density = 2.5f

        val collapsed = GuidanceCameraPadding.resolveTopPaddingPx(
            isGuidanceFollowActive = true,
            mapViewHeightPx = mapViewHeightPx,
            rawTopPaddingPx = 120,
            rawBottomPaddingPx = rawBottomPaddingPx,
            density = density,
        )
        val expanded = GuidanceCameraPadding.resolveTopPaddingPx(
            isGuidanceFollowActive = true,
            mapViewHeightPx = mapViewHeightPx,
            rawTopPaddingPx = 640,
            rawBottomPaddingPx = rawBottomPaddingPx,
            density = density,
        )

        assertEquals(collapsed, expanded)
        assertTrue(collapsed > 0)
    }

    @Test
    fun `分割案内では padded 中心が画面下端から指定割合の位置に来る上 padding を返す`() {
        val mapViewHeightPx = 1000
        val rawBottomPaddingPx = 48

        val topPaddingPx = GuidanceCameraPadding.resolveTopPaddingPx(
            isGuidanceFollowActive = true,
            mapViewHeightPx = mapViewHeightPx,
            rawTopPaddingPx = 24,
            rawBottomPaddingPx = rawBottomPaddingPx,
            density = 2f,
            anchorFractionFromBottom = 0.25f,
        )

        assertEquals(548, topPaddingPx)

        val paddedCenterY = (topPaddingPx + mapViewHeightPx - rawBottomPaddingPx) / 2
        assertEquals(750, paddedCenterY)
    }

    @Test
    fun `分割案内で低身長なら navigation bar と puck の下限を優先する`() {
        val mapViewHeightPx = 360
        val rawBottomPaddingPx = 96
        val density = 3f

        val topPaddingPx = GuidanceCameraPadding.resolveTopPaddingPx(
            isGuidanceFollowActive = true,
            mapViewHeightPx = mapViewHeightPx,
            rawTopPaddingPx = 24,
            rawBottomPaddingPx = rawBottomPaddingPx,
            density = density,
            anchorFractionFromBottom = 0.25f,
        )

        assertEquals(24, topPaddingPx)

        val paddedCenterY = (topPaddingPx + mapViewHeightPx - rawBottomPaddingPx) / 2
        val distanceFromBottomPx = mapViewHeightPx - paddedCenterY
        assertEquals(216, distanceFromBottomPx)
    }
}
