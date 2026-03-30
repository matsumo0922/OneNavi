package me.matsumo.onenavi.core.datasource

import com.mapbox.search.ResponseInfo
import com.mapbox.search.SearchEngine
import com.mapbox.search.SearchEngineSettings
import com.mapbox.search.SearchOptions
import com.mapbox.search.SearchResultCallback
import com.mapbox.search.SearchSelectionCallback
import com.mapbox.search.SearchSuggestionsCallback
import com.mapbox.search.common.SearchRequestException
import com.mapbox.search.details.DetailsApi
import com.mapbox.search.details.RetrieveDetailsOptions
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

    @OptIn(com.mapbox.annotation.MapboxExperimental::class)
    private val detailsApi = DetailsApi.create()

    private var lastSuggestions: List<SearchSuggestion> = emptyList()

    override suspend fun getSuggestions(query: String): Result<List<SearchSuggestionItem>> {
        if (query.isBlank()) return Result.success(emptyList())

        return suspendCancellableCoroutine { continuation ->
            val options = SearchOptions.Builder()
                .limit(10)
                .build()

            val callback = object : SearchSuggestionsCallback {
                override fun onSuggestions(suggestions: List<SearchSuggestion>, responseInfo: ResponseInfo) {
                    lastSuggestions = suggestions
                    val items = suggestions.map { it.toSuggestionItem() }
                    continuation.resume(Result.success(items))
                }

                override fun onError(e: Exception) {
                    continuation.resume(Result.failure(e))
                }
            }

            val task = searchEngine.search(
                query = query,
                options = options,
                callback = callback,
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
                    override fun onResult(suggestion: SearchSuggestion, result: SearchResult, responseInfo: ResponseInfo) {
                        continuation.resume(Result.success(result.toResultItem()))
                    }

                    override fun onResults(suggestion: SearchSuggestion, results: List<SearchResult>, responseInfo: ResponseInfo) {
                        val result = results.firstOrNull()
                        if (result != null) {
                            continuation.resume(Result.success(result.toResultItem()))
                        } else {
                            continuation.resume(Result.failure(NoSuchElementException("No results")))
                        }
                    }

                    override fun onSuggestions(suggestions: List<SearchSuggestion>, responseInfo: ResponseInfo) {
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

    @OptIn(com.mapbox.annotation.MapboxExperimental::class)
    override suspend fun retrieve(id: String): Result<SearchResultItem> {
        return suspendCancellableCoroutine { continuation ->
            val task = detailsApi.retrieveDetails(
                mapboxId = id,
                options = RetrieveDetailsOptions(),
                callback = object : SearchResultCallback {
                    override fun onResult(result: SearchResult, responseInfo: ResponseInfo) {
                        continuation.resume(Result.success(result.toResultItem()))
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
    val routable = routablePoints?.firstOrNull()

    return SearchResultItem(
        id = id,
        name = name,
        fullAddress = fullAddress ?: address?.formattedAddress(),
        descriptionText = descriptionText,
        matchingName = matchingName,
        accuracy = accuracy?.toString(),
        makiIcon = makiIcon,
        latitude = coordinate.latitude(),
        longitude = coordinate.longitude(),
        boundingBoxSouth = boundingBox?.south(),
        boundingBoxWest = boundingBox?.west(),
        boundingBoxNorth = boundingBox?.north(),
        boundingBoxEast = boundingBox?.east(),
        routableLatitude = routable?.point?.latitude(),
        routableLongitude = routable?.point?.longitude(),
        categories = categories.orEmpty(),
        categoryIds = categoryIds.orEmpty(),
        distanceMeters = distanceMeters,
        etaMinutes = etaMinutes,
        externalIds = externalIDs,
        resultTypes = types.map { it.name },
    )
}
