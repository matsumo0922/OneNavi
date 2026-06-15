package me.matsumo.onenavi.core.datasource

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import me.matsumo.onenavi.core.datasource.helper.PreferenceHelper
import me.matsumo.onenavi.core.datasource.helper.deserialize
import me.matsumo.onenavi.core.model.AppSetting
import me.matsumo.onenavi.core.model.DeveloperFeature
import me.matsumo.onenavi.core.model.Theme
import java.security.SecureRandom
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * アプリ設定を DataStore から読み書きする data source。
 *
 * @param preferenceHelper DataStore を作成する helper
 * @param formatter 設定値の JSON 変換に使う formatter
 * @param ioDispatcher DataStore の読み書きに使う dispatcher
 * @param applicationScope [setting] を常駐 collect するアプリ共通 scope
 */
class AppSettingDataSource(
    private val preferenceHelper: PreferenceHelper,
    private val formatter: Json,
    private val ioDispatcher: CoroutineDispatcher,
    applicationScope: CoroutineScope,
) {
    private val preference = preferenceHelper.create(PreferencesName.SETTING)

    private val settingFlow = preference.data.map {
        it.deserialize(formatter, AppSetting.serializer(), AppSetting.DEFAULT)
    }

    private val initialLoad = CompletableDeferred<Unit>()

    private val mutableSetting = MutableStateFlow(AppSetting.DEFAULT)

    // UI 購読の有無に依存せず、プロセス起動直後から DataStore の読み込みを始めて
    // 同期的な `.value` 参照が DEFAULT を見る時間を最小化する。
    val setting: StateFlow<AppSetting> = mutableSetting.asStateFlow()

    init {
        applicationScope.launch {
            settingFlow.collect(::applyLoadedSetting)
        }
    }

    /**
     * DataStore から読み込み済みの設定値を返す。
     *
     * [setting] の `value` は初回読了まで [AppSetting.DEFAULT] を保持し続けるため、
     * 購読の有無に依存せず永続値そのものが必要な箇所ではこちらを使う。
     *
     * @return DataStore に永続化されている [AppSetting]
     */
    suspend fun currentSetting(): AppSetting = withContext(ioDispatcher) {
        settingFlow.first()
    }

    /**
     * 初回の DataStore 読了を待ち、読み込み済みの設定値を返す。
     *
     * [setting] への反映は [applyLoadedSetting] で読了確定より先に行われるため、この関数が返った後は
     * [setting] の `value` が永続値を反映していることが保証される。同期的な `value` 参照しかできない
     * 経路の前段 gate として使う。
     *
     * @return 初回読了を反映した [AppSetting]
     */
    suspend fun awaitInitialLoad(): AppSetting {
        initialLoad.await()

        return setting.value
    }

    /** DataStore から読んだ設定を [setting] へ反映し、その後に初回読了を確定する。 */
    private fun applyLoadedSetting(loadedSetting: AppSetting) {
        mutableSetting.value = loadedSetting
        initialLoad.complete(Unit)
    }

    @OptIn(ExperimentalUuidApi::class)
    suspend fun initializeIdIfNeeded() = withContext(ioDispatcher) {
        val current = currentSetting()
        if (current.id.isBlank()) {
            val uuid = Uuid.random().toString()
            preference.edit {
                it[stringPreferencesKey(AppSetting::id.name)] = uuid
            }
        }
    }

    suspend fun setId(id: String) = withContext(ioDispatcher) {
        if (currentSetting().id == id) return@withContext

        preference.edit {
            it[stringPreferencesKey(AppSetting::id.name)] = id
        }
    }

    suspend fun setTheme(theme: Theme) = withContext(ioDispatcher) {
        if (currentSetting().theme == theme) return@withContext

        preference.edit {
            it[stringPreferencesKey(AppSetting::theme.name)] = theme.name
        }
    }

    suspend fun setUseDynamicColor(useDynamicColor: Boolean) = withContext(ioDispatcher) {
        if (currentSetting().useDynamicColor == useDynamicColor) return@withContext

        preference.edit {
            it[booleanPreferencesKey(AppSetting::useDynamicColor.name)] = useDynamicColor
        }
    }

    suspend fun setSeedColor(color: Color) = withContext(ioDispatcher) {
        if (currentSetting().seedColor == color) return@withContext

        preference.edit {
            it[intPreferencesKey(AppSetting::seedColor.name)] = color.toArgb()
        }
    }

    suspend fun setPlusMode(plusMode: Boolean) = withContext(ioDispatcher) {
        if (currentSetting().plusMode == plusMode) return@withContext

        preference.edit {
            it[booleanPreferencesKey(AppSetting::plusMode.name)] = plusMode
        }
    }

    suspend fun setDeveloperMode(developerMode: Boolean) = withContext(ioDispatcher) {
        val current = currentSetting()
        val shouldClearDeveloperFeatures = !developerMode && current.enabledDeveloperFeatures.isNotEmpty()
        if (current.developerMode == developerMode && !shouldClearDeveloperFeatures) return@withContext

        preference.edit {
            it[booleanPreferencesKey(AppSetting::developerMode.name)] = developerMode
            if (!developerMode) {
                it[stringPreferencesKey(AppSetting::enabledDeveloperFeatures.name)] = encodeEnabledDeveloperFeatures(emptySet())
            }
        }
    }

    suspend fun setDeveloperFeatureEnabled(feature: DeveloperFeature, isEnabled: Boolean) = withContext(ioDispatcher) {
        val developerModeKey = booleanPreferencesKey(AppSetting::developerMode.name)
        val preferenceKey = stringPreferencesKey(AppSetting::enabledDeveloperFeatures.name)
        preference.edit { preferences ->
            val isDeveloperMode = preferences[developerModeKey] ?: AppSetting.DEFAULT.developerMode
            if (!isDeveloperMode) {
                if (preferences[preferenceKey] != null) {
                    preferences[preferenceKey] = encodeEnabledDeveloperFeatures(emptySet())
                }
                return@edit
            }

            val current = decodeEnabledDeveloperFeatures(preferences[preferenceKey])
            val updated = if (isEnabled) current + feature else current - feature
            if (current == updated) return@edit

            preferences[preferenceKey] = encodeEnabledDeveloperFeatures(updated)
        }
    }

    suspend fun setUseMediaAudioChannelOnCar(useMediaAudioChannelOnCar: Boolean) = withContext(ioDispatcher) {
        if (currentSetting().useMediaAudioChannelOnCar == useMediaAudioChannelOnCar) return@withContext

        preference.edit {
            it[booleanPreferencesKey(AppSetting::useMediaAudioChannelOnCar.name)] = useMediaAudioChannelOnCar
        }
    }

    suspend fun setTtsVolumeGainDb(ttsVolumeGainDb: Double) = withContext(ioDispatcher) {
        val resolvedVolumeGainDb = ttsVolumeGainDb.coerceIn(
            minimumValue = AppSetting.TTS_VOLUME_GAIN_DB_MIN,
            maximumValue = AppSetting.TTS_VOLUME_GAIN_DB_MAX,
        )
        if (currentSetting().ttsVolumeGainDb == resolvedVolumeGainDb) return@withContext

        preference.edit {
            it[doublePreferencesKey(AppSetting::ttsVolumeGainDb.name)] = resolvedVolumeGainDb
        }
    }

    suspend fun setSpeedAdaptiveTtsGainEnabled(isEnabled: Boolean) = withContext(ioDispatcher) {
        if (currentSetting().isSpeedAdaptiveTtsGainEnabled == isEnabled) return@withContext

        preference.edit {
            it[booleanPreferencesKey(AppSetting::isSpeedAdaptiveTtsGainEnabled.name)] = isEnabled
        }
    }

    suspend fun setSpeedAdaptiveTtsGainMaxDb(maxGainDb: Double) = withContext(ioDispatcher) {
        val resolvedMaxGainDb = maxGainDb.coerceIn(
            minimumValue = AppSetting.SPEED_ADAPTIVE_TTS_GAIN_MAX_DB_MIN,
            maximumValue = AppSetting.SPEED_ADAPTIVE_TTS_GAIN_MAX_DB_MAX,
        )
        if (currentSetting().speedAdaptiveTtsGainMaxDb == resolvedMaxGainDb) return@withContext

        preference.edit {
            it[doublePreferencesKey(AppSetting::speedAdaptiveTtsGainMaxDb.name)] = resolvedMaxGainDb
        }
    }

    suspend fun setGuidanceCategoryEnabled(categoryKey: String, isEnabled: Boolean) = withContext(ioDispatcher) {
        val preferenceKey = stringPreferencesKey(AppSetting::disabledGuidanceCategories.name)
        // edit ブロックは単一 writer で直列実行されるため、現在の永続値を読んで add/remove することで
        // 連続タップでも read-modify-write が原子的になり、後勝ちで更新が失われない。
        preference.edit { preferences ->
            val current = decodeDisabledGuidanceCategories(preferences[preferenceKey])
            val updated = if (isEnabled) current - categoryKey else current + categoryKey
            preferences[preferenceKey] = formatter.encodeToString(SetSerializer(String.serializer()), updated)
        }
    }

    /** 永続化された OFF カテゴリ集合をデコードする。未保存・破損時は既定値にフォールバックする。 */
    private fun decodeDisabledGuidanceCategories(encoded: String?): Set<String> {
        if (encoded == null) return AppSetting.DEFAULT.disabledGuidanceCategories
        return runCatching {
            formatter.decodeFromString(SetSerializer(String.serializer()), encoded)
        }.getOrDefault(AppSetting.DEFAULT.disabledGuidanceCategories)
    }

    /** 永続化された開発者向け機能集合をデコードする。未保存・破損時は既定値にフォールバックする。 */
    private fun decodeEnabledDeveloperFeatures(encoded: String?): Set<DeveloperFeature> {
        if (encoded == null) return AppSetting.DEFAULT.enabledDeveloperFeatures

        return runCatching {
            formatter.decodeFromString(SetSerializer(DeveloperFeature.serializer()), encoded)
        }.getOrDefault(AppSetting.DEFAULT.enabledDeveloperFeatures)
    }

    private fun encodeEnabledDeveloperFeatures(features: Set<DeveloperFeature>): String {
        return formatter.encodeToString(SetSerializer(DeveloperFeature.serializer()), features)
    }

    suspend fun setHasDetectedClusterSession(hasDetected: Boolean) = withContext(ioDispatcher) {
        if (currentSetting().hasDetectedClusterSession == hasDetected) return@withContext

        preference.edit {
            it[booleanPreferencesKey(AppSetting::hasDetectedClusterSession.name)] = hasDetected
        }
    }

    suspend fun getOrCreateExtNavDeviceUuid(): String = withContext(ioDispatcher) {
        val current = currentSetting().extNavDeviceUuid
        if (current.matches(EXT_NAV_DEVICE_UUID_REGEX)) return@withContext current

        // 旧バージョンは標準 UUID (`xxxxxxxx-xxxx-...`) を保存していたが、
        // 外部ナビ API サーバーは独自形式 (`DNS` + 40 桁 hex lowercase) を要求する。
        // 形式不一致だと unknown device 扱いとなりログイン POST が失敗するため、
        // 不正形式の値は破棄して再生成する。
        val bytes = ByteArray(EXT_NAV_DEVICE_UUID_BYTES)
        SecureRandom().nextBytes(bytes)
        val hex = bytes.joinToString("") { "%02x".format(it) }
        val uuid = "$EXT_NAV_DEVICE_UUID_PREFIX$hex"
        preference.edit {
            it[stringPreferencesKey(AppSetting::extNavDeviceUuid.name)] = uuid
        }
        uuid
    }

    private companion object {
        const val EXT_NAV_DEVICE_UUID_PREFIX = "DNS"
        const val EXT_NAV_DEVICE_UUID_BYTES = 20
        val EXT_NAV_DEVICE_UUID_REGEX = Regex("^${EXT_NAV_DEVICE_UUID_PREFIX}[0-9a-f]{40}$")
    }
}
