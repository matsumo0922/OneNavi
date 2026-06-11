package me.matsumo.onenavi.core.datasource

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import me.matsumo.onenavi.core.datasource.helper.PreferenceHelper
import me.matsumo.onenavi.core.datasource.helper.deserialize
import me.matsumo.onenavi.core.model.AppSetting
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
 */
class AppSettingDataSource(
    private val preferenceHelper: PreferenceHelper,
    private val formatter: Json,
    private val ioDispatcher: CoroutineDispatcher,
) {
    private val preference = preferenceHelper.create(PreferencesName.SETTING)

    private val settingFlow = preference.data.map {
        it.deserialize(formatter, AppSetting.serializer(), AppSetting.DEFAULT)
    }

    val setting = settingFlow.stateIn(
        scope = CoroutineScope(ioDispatcher),
        started = SharingStarted.WhileSubscribed(1000),
        initialValue = AppSetting.DEFAULT,
    )

    /**
     * DataStore から読み込み済みの設定値を返す。
     *
     * [setting] の `value` は購読が始まるまで [AppSetting.DEFAULT] を保持し続けるため、
     * 購読の有無に依存せず永続値そのものが必要な箇所ではこちらを使う。
     *
     * @return DataStore に永続化されている [AppSetting]
     */
    suspend fun currentSetting(): AppSetting = withContext(ioDispatcher) {
        settingFlow.first()
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
        if (currentSetting().developerMode == developerMode) return@withContext

        preference.edit {
            it[booleanPreferencesKey(AppSetting::developerMode.name)] = developerMode
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
