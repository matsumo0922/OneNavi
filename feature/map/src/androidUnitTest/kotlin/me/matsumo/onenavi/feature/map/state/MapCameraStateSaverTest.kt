package me.matsumo.onenavi.feature.map.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 地図カメラ状態の保存復元を検証するテスト。 */
class MapCameraStateSaverTest {

    @Test
    fun `現行復元データは案内カメラ状態を復元する`() {
        val restore = requireNotNull(
            MapCameraState.restoreStateFromSavedFields(
                saved = currentRestoreFields(isGuidanceCameraActive = true),
            ),
        )

        assertEquals(true, restore.isFollowingMyLocation)
        assertEquals(true, restore.isGuidanceCameraActive)
    }

    @Test
    fun `旧復元データは案内カメラ状態なしとして復元する`() {
        val restore = requireNotNull(
            MapCameraState.restoreStateFromSavedFields(
                saved = legacyRestoreFields(),
            ),
        )

        assertEquals(true, restore.isFollowingMyLocation)
        assertEquals(false, restore.isGuidanceCameraActive)
    }

    @Test
    fun `不正なフィールド数は復元しない`() {
        val restore = MapCameraState.restoreStateFromSavedFields(
            saved = listOf(
                RESTORE_LATITUDE,
                RESTORE_LONGITUDE,
            ),
        )

        assertNull(restore)
    }

    private fun currentRestoreFields(
        isGuidanceCameraActive: Boolean,
    ): List<Any?> = listOf(
        RESTORE_LATITUDE,
        RESTORE_LONGITUDE,
        RESTORE_ZOOM,
        MapCameraPerspective.TILTED,
        true,
        isGuidanceCameraActive,
    )

    private fun legacyRestoreFields(): List<Any?> = listOf(
        RESTORE_LATITUDE,
        RESTORE_LONGITUDE,
        RESTORE_ZOOM,
        MapCameraPerspective.TILTED,
        true,
    )

    /** 地図カメラ保存復元テスト用の定数。 */
    private companion object {

        /** 復元テスト用の緯度。 */
        const val RESTORE_LATITUDE = 35.0

        /** 復元テスト用の経度。 */
        const val RESTORE_LONGITUDE = 139.0

        /** 復元テスト用のズーム値。 */
        const val RESTORE_ZOOM = 16f
    }
}
