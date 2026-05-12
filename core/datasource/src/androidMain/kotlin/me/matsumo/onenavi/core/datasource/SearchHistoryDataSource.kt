package me.matsumo.onenavi.core.datasource

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.matsumo.onenavi.core.datasource.helper.PreferenceHelper
import me.matsumo.onenavi.core.model.SearchHistory

class SearchHistoryDataSource(
    preferenceHelper: PreferenceHelper,
    private val formatter: Json,
    private val ioDispatcher: CoroutineDispatcher,
) {
    private val preference = preferenceHelper.create(PreferencesName.SEARCH_HISTORY)

    val histories: StateFlow<List<SearchHistory>> = preference.data.map { prefs ->
        val json = prefs[HISTORIES_KEY] ?: return@map emptyList()
        runCatching {
            formatter.decodeFromString<List<SearchHistory>>(json)
        }.getOrDefault(emptyList())
    }.stateIn(
        scope = CoroutineScope(ioDispatcher),
        started = SharingStarted.WhileSubscribed(1000),
        initialValue = emptyList(),
    )

    suspend fun addHistory(history: SearchHistory) = withContext(ioDispatcher) {
        val current = histories.value.toMutableList()
        current.removeAll { it.id == history.id }
        current.add(0, history)

        val trimmed = current.take(MAX_HISTORY_COUNT)

        preference.edit {
            it[HISTORIES_KEY] = formatter.encodeToString(trimmed)
        }
    }

    suspend fun removeHistory(historyId: String) = withContext(ioDispatcher) {
        val updated = histories.value.filter { it.id != historyId }

        preference.edit {
            it[HISTORIES_KEY] = formatter.encodeToString(updated)
        }
    }

    companion object {
        private const val MAX_HISTORY_COUNT = 20
        private val HISTORIES_KEY = stringPreferencesKey("search_histories")
    }
}
