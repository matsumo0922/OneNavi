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

    suspend fun retrieve(id: String): Result<SearchResultItem> {
        return searchDataSource.retrieve(id)
    }

    suspend fun searchMultiple(query: String, latitude: Double?, longitude: Double?): Result<List<SearchResultItem>> {
        return searchDataSource.searchMultiple(query, latitude, longitude)
    }

    suspend fun reverseGeocode(latitude: Double, longitude: Double): Result<SearchResultItem?> {
        return searchDataSource.reverseGeocode(latitude, longitude)
    }

    suspend fun addHistory(result: SearchResultItem) {
        val history = SearchHistory(
            id = result.placeId,
            name = result.name,
            address = result.formattedAddress,
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
