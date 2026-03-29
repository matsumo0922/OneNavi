package me.matsumo.onenavi.core.datasource

import me.matsumo.onenavi.core.model.SearchResultItem
import me.matsumo.onenavi.core.model.SearchSuggestionItem

class IosSearchDataSource : SearchDataSource {
    override suspend fun getSuggestions(query: String): Result<List<SearchSuggestionItem>> {
        return Result.failure(UnsupportedOperationException("Search is not available on iOS yet"))
    }

    override suspend fun select(suggestionId: String): Result<SearchResultItem> {
        return Result.failure(UnsupportedOperationException("Search is not available on iOS yet"))
    }
}
