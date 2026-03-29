package me.matsumo.onenavi.core.datasource

import me.matsumo.onenavi.core.model.SearchResultItem
import me.matsumo.onenavi.core.model.SearchSuggestionItem

interface SearchDataSource {
    suspend fun getSuggestions(query: String): Result<List<SearchSuggestionItem>>
    suspend fun select(suggestionId: String): Result<SearchResultItem>
}
