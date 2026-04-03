package me.matsumo.onenavi.core.datasource

import me.matsumo.onenavi.core.model.SearchResultItem
import me.matsumo.onenavi.core.model.SearchSuggestionItem

interface SearchDataSource {
    suspend fun getSuggestions(query: String): Result<List<SearchSuggestionItem>>
    suspend fun select(suggestionId: String): Result<SearchResultItem>
    suspend fun retrieve(id: String): Result<SearchResultItem>
    suspend fun searchMultiple(query: String, latitude: Double?, longitude: Double?): Result<List<SearchResultItem>>
    suspend fun reverseGeocode(latitude: Double, longitude: Double): Result<SearchResultItem?>
}
