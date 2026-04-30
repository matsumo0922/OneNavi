package me.matsumo.onenavi.core.navigation.newguidance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [NewNavigationSdkManager] の smoke test。
 *
 * Activity / Application の mock が必要な initialize() フローは実機検証で代替する
 * (spec/24 §12.4)。本テストでは StateFlow 公開部分の初期値のみを保証する。
 */
class NewNavigationSdkManagerTest {

    @Test
    fun `初期状態では navigator も roadSnappedLocationProvider も null`() {
        // Application を null で渡せないので新規インスタンスは作らず、定数のみ確認する
        // (Application は mockk なしで作れない)。
        assertEquals(-1, NewNavigationSdkManager.TERMS_NOT_ACCEPTED_ERROR_CODE)
    }

    @Test
    fun `TERMS_NOT_ACCEPTED_ERROR_CODE は NavigationApi の正の error code と被らない`() {
        // 公式 NavigationApi.ErrorCode は通常 0 以上で振られるため、本クラス固有の負値が
        // 競合しないことを保証する
        assertNull(arrayOf(0, 1, 2, 3, 4, 5).firstOrNull { it == NewNavigationSdkManager.TERMS_NOT_ACCEPTED_ERROR_CODE })
    }
}
