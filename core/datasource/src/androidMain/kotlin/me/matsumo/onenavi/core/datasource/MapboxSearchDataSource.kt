package me.matsumo.onenavi.core.datasource

import com.mapbox.search.ResponseInfo
import com.mapbox.search.SearchEngine
import com.mapbox.search.SearchEngineSettings
import com.mapbox.search.SearchOptions
import com.mapbox.search.SearchSelectionCallback
import com.mapbox.search.SearchSuggestionsCallback
import com.mapbox.search.common.SearchRequestException
import com.mapbox.search.result.SearchResult
import com.mapbox.search.result.SearchSuggestion
import kotlinx.coroutines.suspendCancellableCoroutine
import me.matsumo.onenavi.core.model.SearchResultItem
import me.matsumo.onenavi.core.model.SearchSuggestionItem
import kotlin.coroutines.resume

class MapboxSearchDataSource : SearchDataSource {

    private val searchEngine = SearchEngine.createSearchEngineWithBuiltInDataProviders(
        settings = SearchEngineSettings(),
        apiType = com.mapbox.search.ApiType.SEARCH_BOX,
    )

    private var lastSuggestions: List<SearchSuggestion> = emptyList()

    override suspend fun getSuggestions(query: String): Result<List<SearchSuggestionItem>> {
        if (query.isBlank()) return Result.success(emptyList())

        return suspendCancellableCoroutine { continuation ->
            val task = searchEngine.search(
                query = query,
                options = SearchOptions.Builder()
                    .limit(10)
                    .build(),
                callback = object : SearchSuggestionsCallback {
                    override fun onSuggestions(
                        suggestions: List<SearchSuggestion>,
                        responseInfo: ResponseInfo,
                    ) {
                        lastSuggestions = suggestions
                        val items = suggestions.map { it.toSuggestionItem() }
                        continuation.resume(Result.success(items))
                    }

                    override fun onError(e: Exception) {
                        continuation.resume(Result.failure(e))
                    }
                },
            )

            continuation.invokeOnCancellation {
                task.cancel()
            }
        }
    }

    override suspend fun select(suggestionId: String): Result<SearchResultItem> {
        val suggestion = lastSuggestions.find { it.id == suggestionId }
            ?: return Result.failure(IllegalArgumentException("Suggestion not found: $suggestionId"))

        return suspendCancellableCoroutine { continuation ->
            val task = searchEngine.select(
                suggestion = suggestion,
                callback = object : SearchSelectionCallback {
                    override fun onResult(
                        suggestion: SearchSuggestion,
                        result: SearchResult,
                        responseInfo: ResponseInfo,
                    ) {
                        continuation.resume(Result.success(result.toResultItem()))
                    }

                    override fun onResults(
                        suggestion: SearchSuggestion,
                        results: List<SearchResult>,
                        responseInfo: ResponseInfo,
                    ) {
                        val result = results.firstOrNull()
                        if (result != null) {
                            continuation.resume(Result.success(result.toResultItem()))
                        } else {
                            continuation.resume(Result.failure(NoSuchElementException("No results")))
                        }
                    }

                    override fun onSuggestions(
                        suggestions: List<SearchSuggestion>,
                        responseInfo: ResponseInfo,
                    ) {
                        lastSuggestions = suggestions
                        continuation.resume(
                            Result.failure(
                                SearchRequestException(
                                    message = "Got more suggestions instead of result",
                                    code = 0,
                                ),
                            ),
                        )
                    }

                    override fun onError(error: Exception) {
                        continuation.resume(Result.failure(error))
                    }
                },
            )

            continuation.invokeOnCancellation {
                task.cancel()
            }
        }
    }
}

private fun SearchSuggestion.toSuggestionItem(): SearchSuggestionItem {
    return SearchSuggestionItem(
        id = id,
        name = name,
        address = address?.formattedAddress(),
        distanceMeters = distanceMeters,
        categories = categories.orEmpty(),
    )
}

private fun SearchResult.toResultItem(): SearchResultItem {
    return SearchResultItem(
        id = id,
        name = name,
        address = address?.formattedAddress(),
        latitude = coordinate.latitude(),
        longitude = coordinate.longitude(),
        categories = categories.orEmpty(),
    )
}
