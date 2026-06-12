package me.matsumo.onenavi.core.datasource

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import me.matsumo.onenavi.core.datasource.helper.PreferenceHelper
import me.matsumo.onenavi.core.model.AppSetting
import me.matsumo.onenavi.core.model.DeveloperFeature
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** アプリ設定 data source の開発者向け機能保存を検証するテスト。 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppSettingDataSourceTest {

    @Test
    fun `developer feature write after disabling developer mode does not leave latent feature`() = runTest {
        val dataSource = AppSettingDataSource(
            preferenceHelper = InMemoryPreferenceHelper(),
            formatter = Json,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            applicationScope = backgroundScope,
        )

        dataSource.setDeveloperMode(true)
        dataSource.setDeveloperFeatureEnabled(DeveloperFeature.FAKE_GPS, true)
        assertTrue(dataSource.currentSetting().isDeveloperFeatureEnabled(DeveloperFeature.FAKE_GPS))

        dataSource.setDeveloperMode(false)
        dataSource.setDeveloperFeatureEnabled(DeveloperFeature.FAKE_GPS, true)

        val disabledSetting = dataSource.currentSetting()
        assertFalse(disabledSetting.developerMode)
        assertTrue(disabledSetting.enabledDeveloperFeatures.isEmpty())

        dataSource.setDeveloperMode(true)

        val unlockedSetting = dataSource.currentSetting()
        assertFalse(unlockedSetting.isDeveloperFeatureEnabled(DeveloperFeature.FAKE_GPS))
        assertTrue(unlockedSetting.enabledDeveloperFeatures.isEmpty())
    }

    @Test
    fun `tts override settings trim voice name and clamp speaking rate`() = runTest {
        val dataSource = AppSettingDataSource(
            preferenceHelper = InMemoryPreferenceHelper(),
            formatter = Json,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            applicationScope = backgroundScope,
        )

        dataSource.setTtsVoiceNameOverride(" ja-JP-Chirp3-HD-Kore ")
        dataSource.setTtsSpeakingRateOverride(AppSetting.TTS_SPEAKING_RATE_OVERRIDE_MAX + 1.0)

        val setting = dataSource.currentSetting()

        assertEquals("ja-JP-Chirp3-HD-Kore", setting.ttsVoiceNameOverride)
        assertEquals(AppSetting.TTS_SPEAKING_RATE_OVERRIDE_MAX, setting.ttsSpeakingRateOverride)
    }
}

/** テスト用に単一の DataStore を返す PreferenceHelper。 */
private class InMemoryPreferenceHelper : PreferenceHelper {
    private val dataStore = InMemoryPreferencesDataStore()

    override fun create(name: String): DataStore<Preferences> = dataStore

    override fun delete(name: String) = Unit
}

/** テスト用のメモリ上 Preferences DataStore。 */
private class InMemoryPreferencesDataStore : DataStore<Preferences> {
    private val mutex = Mutex()
    private val mutablePreferences = MutableStateFlow<Preferences>(emptyPreferences())

    override val data: Flow<Preferences> = mutablePreferences

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        return mutex.withLock {
            val updatedPreferences = transform(mutablePreferences.value)
            mutablePreferences.value = updatedPreferences

            updatedPreferences
        }
    }
}
