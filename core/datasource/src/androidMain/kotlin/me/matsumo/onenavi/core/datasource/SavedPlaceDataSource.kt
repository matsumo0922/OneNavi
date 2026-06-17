package me.matsumo.onenavi.core.datasource

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.matsumo.onenavi.core.datasource.helper.PreferenceHelper
import me.matsumo.onenavi.core.model.SavedPlace

/**
 * 保存地点を Preferences DataStore の JSON へ永続化する data source。
 *
 * @param preferenceHelper DataStore を作成する helper
 * @param formatter 保存地点の JSON 変換に使う formatter
 * @param ioDispatcher DataStore の読み書きに使う dispatcher
 */
class SavedPlaceDataSource(
    preferenceHelper: PreferenceHelper,
    private val formatter: Json,
    private val ioDispatcher: CoroutineDispatcher,
) {
    private val preference = preferenceHelper.create(PreferencesName.SAVED_PLACES)

    val places: StateFlow<List<SavedPlace>> = preference.data.map { preferences ->
        decodeStore(preferences).places
    }.stateIn(
        scope = CoroutineScope(ioDispatcher),
        started = SharingStarted.WhileSubscribed(1000),
        initialValue = emptyList(),
    )

    /** 現在 DataStore に保存されている地点一覧を返す。 */
    suspend fun currentPlaces(): List<SavedPlace> = withContext(ioDispatcher) {
        decodeStore(preference.data.first()).places
    }

    /** 同じ ID の地点を置き換え、存在しない場合は末尾に追加する。 */
    suspend fun upsert(place: SavedPlace) = withContext(ioDispatcher) {
        preference.edit { preferences ->
            val currentStore = decodeStore(preferences)
            val updatedPlaces = currentStore.places.replaceOrAppend(place)

            preferences[PLACES_KEY] = formatter.encodeToString(
                currentStore.copy(places = updatedPlaces),
            )
        }
    }

    /** 指定 ID の地点を削除する。 */
    suspend fun remove(id: String) = withContext(ioDispatcher) {
        preference.edit { preferences ->
            val currentStore = decodeStore(preferences)
            val updatedPlaces = currentStore.places.filter { place -> place.id != id }

            preferences[PLACES_KEY] = formatter.encodeToString(
                currentStore.copy(places = updatedPlaces),
            )
        }
    }

    private fun decodeStore(preferences: Preferences): SavedPlaceStore {
        val json = preferences[PLACES_KEY] ?: return SavedPlaceStore()

        return runCatching {
            formatter.decodeFromString<SavedPlaceStore>(json)
        }.getOrDefault(SavedPlaceStore())
    }

    private fun List<SavedPlace>.replaceOrAppend(place: SavedPlace): List<SavedPlace> {
        val replaceIndex = indexOfFirst { currentPlace -> currentPlace.id == place.id }
        if (replaceIndex == -1) return this + place

        return mapIndexed { index, currentPlace ->
            if (index == replaceIndex) place else currentPlace
        }
    }

    companion object {
        private val PLACES_KEY = stringPreferencesKey(PreferencesName.SAVED_PLACES)
    }
}

/** 保存地点 JSON の migration 用 wrapper。 */
@Serializable
private data class SavedPlaceStore(
    val version: Int = 1,
    val places: List<SavedPlace> = emptyList(),
)
