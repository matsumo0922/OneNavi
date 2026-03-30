package me.matsumo.onenavi.core.datasource

import com.mapbox.geojson.Point
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
import com.mapbox.search.result.SearchSuggestionType
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

    override suspend fun searchMultiple(query: String, latitude: Double?, longitude: Double?): Result<List<SearchResultItem>> {
        if (query.isBlank()) return Result.success(emptyList())

        val proximity = if (latitude != null && longitude != null) {
            Point.fromLngLat(longitude, latitude)
        } else {
            null
        }

        // 1. proximity 付きで suggestion を取得
        val suggestionsResult = getSuggestionsRaw(query, proximity)
        val suggestions = suggestionsResult.getOrElse { return Result.failure(it) }

        if (suggestions.isEmpty()) return Result.success(emptyList())

        // 2. Category 型の suggestion を優先的に select（onResults で近隣の複数結果が返る）
        val categorySuggestion = suggestions.firstOrNull { it.type is SearchSuggestionType.Category }

        if (categorySuggestion != null) {
            val result = selectRaw(categorySuggestion)
            if (result.isSuccess) {
                val items = result.getOrThrow()
                if (items.isNotEmpty()) return Result.success(items.take(MAX_SEARCH_RESULTS))
            }
        }

        // 3. Category 型がなければ最初の suggestion を select
        return selectRaw(suggestions.first()).map { items ->
            items.take(MAX_SEARCH_RESULTS)
        }
    }

    private suspend fun getSuggestionsRaw(
        query: String,
        proximity: Point?,
    ): Result<List<SearchSuggestion>> {
        return suspendCancellableCoroutine { continuation ->
            val options = SearchOptions.Builder()
                .limit(MAX_SUGGESTIONS)
                .apply { if (proximity != null) proximity(proximity) }
                .build()

            val task = searchEngine.search(
                query = query,
                options = options,
                callback = object : SearchSuggestionsCallback {
                    override fun onSuggestions(suggestions: List<SearchSuggestion>, responseInfo: ResponseInfo) {
                        continuation.resume(Result.success(suggestions))
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

    private suspend fun selectRaw(suggestion: SearchSuggestion): Result<List<SearchResultItem>> {
        return suspendCancellableCoroutine { continuation ->
            val task = searchEngine.select(
                suggestion = suggestion,
                callback = object : SearchSelectionCallback {
                    override fun onResult(suggestion: SearchSuggestion, result: SearchResult, responseInfo: ResponseInfo) {
                        continuation.resume(Result.success(listOf(result.toResultItem())))
                    }

                    override fun onResults(suggestion: SearchSuggestion, results: List<SearchResult>, responseInfo: ResponseInfo) {
                        continuation.resume(Result.success(results.map { it.toResultItem() }))
                    }

                    override fun onSuggestions(suggestions: List<SearchSuggestion>, responseInfo: ResponseInfo) {
                        continuation.resume(Result.success(emptyList()))
                    }

                    override fun onError(e: Exception) {
                        continuation.resume(Result.success(emptyList()))
                    }
                },
            )

            continuation.invokeOnCancellation {
                task.cancel()
            }
        }
    }

    companion object {
        private const val MAX_SUGGESTIONS = 10
        private const val MAX_SEARCH_RESULTS = 10
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
