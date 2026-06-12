package me.matsumo.onenavi.core.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** アプリ設定の開発者向け機能判定を検証するテスト。 */
class AppSettingTest {

    @Test
    fun `developer feature is disabled when developer mode is locked`() {
        val setting = AppSetting.DEFAULT.copy(
            developerMode = false,
            enabledDeveloperFeatures = setOf(DeveloperFeature.FAKE_GPS),
        )

        assertFalse(setting.isDeveloperFeatureEnabled(DeveloperFeature.FAKE_GPS))
    }

    @Test
    fun `developer feature is enabled only when developer mode is unlocked and feature is selected`() {
        val setting = AppSetting.DEFAULT.copy(
            developerMode = true,
            enabledDeveloperFeatures = setOf(DeveloperFeature.MAP_DIAGNOSTICS),
        )

        assertTrue(setting.isDeveloperFeatureEnabled(DeveloperFeature.MAP_DIAGNOSTICS))
        assertFalse(setting.isDeveloperFeatureEnabled(DeveloperFeature.FAKE_GPS))
    }

    @Test
    fun `force plus privilege requires developer feature toggle`() {
        val unlockedSetting = AppSetting.DEFAULT.copy(
            developerMode = true,
            enabledDeveloperFeatures = setOf(DeveloperFeature.FORCE_PLUS_PRIVILEGE),
        )
        val lockedSetting = unlockedSetting.copy(developerMode = false)
        val plusSetting = AppSetting.DEFAULT.copy(plusMode = true)

        assertTrue(unlockedSetting.hasPrivilege)
        assertFalse(lockedSetting.hasPrivilege)
        assertTrue(plusSetting.hasPrivilege)
    }
}
