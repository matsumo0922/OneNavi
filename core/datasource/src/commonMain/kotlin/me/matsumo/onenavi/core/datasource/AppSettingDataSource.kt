package me.matsumo.onenavi.core.datasource

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.datastore.preferences.core.booleanPreferencesKey
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
import kotlinx.serialization.json.Json
import me.matsumo.onenavi.core.datasource.helper.PreferenceHelper
import me.matsumo.onenavi.core.datasource.helper.deserialize
import me.matsumo.onenavi.core.model.AppSetting
import me.matsumo.onenavi.core.model.Theme
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class AppSettingDataSource(
    private val preferenceHelper: PreferenceHelper,
    private val formatter: Json,
    private val ioDispatcher: CoroutineDispatcher,
) {
    private val preference = preferenceHelper.create(PreferencesName.SETTING)

    val setting = preference.data.map {
        it.deserialize(formatter, AppSetting.serializer(), AppSetting.DEFAULT)
    }.stateIn(
        scope = CoroutineScope(ioDispatcher),
        started = SharingStarted.WhileSubscribed(1000),
        initialValue = AppSetting.DEFAULT,
    )

    @OptIn(ExperimentalUuidApi::class)
    suspend fun initializeIdIfNeeded() = withContext(ioDispatcher) {
        val current = setting.first()
        if (current.id.isBlank()) {
            val uuid = Uuid.random().toString()
            preference.edit {
                it[stringPreferencesKey(AppSetting::id.name)] = uuid
            }
        }
    }

    suspend fun setId(id: String) = withContext(ioDispatcher) {
        if (setting.first().id == id) return@withContext

        preference.edit {
            it[stringPreferencesKey(AppSetting::id.name)] = id
        }
    }

    suspend fun setTheme(theme: Theme) = withContext(ioDispatcher) {
        if (setting.first().theme == theme) return@withContext

        preference.edit {
            it[stringPreferencesKey(AppSetting::theme.name)] = theme.name
        }
    }

    suspend fun setUseDynamicColor(useDynamicColor: Boolean) = withContext(ioDispatcher) {
        if (setting.first().useDynamicColor == useDynamicColor) return@withContext

        preference.edit {
            it[booleanPreferencesKey(AppSetting::useDynamicColor.name)] = useDynamicColor
        }
    }

    suspend fun setSeedColor(color: Color) = withContext(ioDispatcher) {
        if (setting.first().seedColor == color) return@withContext

        preference.edit {
            it[intPreferencesKey(AppSetting::seedColor.name)] = color.toArgb()
        }
    }

    suspend fun setPlusMode(plusMode: Boolean) = withContext(ioDispatcher) {
        if (setting.first().plusMode == plusMode) return@withContext

        preference.edit {
            it[booleanPreferencesKey(AppSetting::plusMode.name)] = plusMode
        }
    }

    suspend fun setDeveloperMode(developerMode: Boolean) = withContext(ioDispatcher) {
        if (setting.first().developerMode == developerMode) return@withContext

        preference.edit {
            it[booleanPreferencesKey(AppSetting::developerMode.name)] = developerMode
        }
    }
}
