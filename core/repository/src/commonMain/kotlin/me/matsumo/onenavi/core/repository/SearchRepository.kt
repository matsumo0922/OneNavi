package me.matsumo.onenavi.core.repository

import kotlinx.coroutines.flow.StateFlow
import me.matsumo.onenavi.core.datasource.SearchDataSource
import me.matsumo.onenavi.core.datasource.SearchHistoryDataSource
import me.matsumo.onenavi.core.model.SearchHistory
import me.matsumo.onenavi.core.model.SearchResultItem
import me.matsumo.onenavi.core.model.SearchSuggestionItem

class SearchRepository(
    private val searchDataSource: SearchDataSource,
    private val searchHistoryDataSource: SearchHistoryDataSource,
) {
    val histories: StateFlow<List<SearchHistory>> = searchHistoryDataSource.histories

    suspend fun getSuggestions(query: String): Result<List<SearchSuggestionItem>> {
        return searchDataSource.getSuggestions(query)
    }

    suspend fun select(suggestionId: String): Result<SearchResultItem> {
        return searchDataSource.select(suggestionId)
    }

    suspend fun retrieve(mapboxId: String): Result<SearchResultItem> {
        return searchDataSource.retrieve(mapboxId)
    }

    suspend fun addHistory(result: SearchResultItem) {
        val history = SearchHistory(
            id = result.id,
            mapboxId = result.mapboxId,
            name = result.name,
            address = result.fullAddress,
            latitude = result.latitude,
            longitude = result.longitude,
            searchedAtEpochMillis = kotlin.time.Clock.System.now().toEpochMilliseconds(),
        )
        searchHistoryDataSource.addHistory(history)
    }

    suspend fun removeHistory(historyId: String) {
        searchHistoryDataSource.removeHistory(historyId)
    }
}
